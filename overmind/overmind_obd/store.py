"""Stockage SQLite (WAL) de l'historique OBD, avec déduplication par message_id."""

from __future__ import annotations

import json
import os
import sqlite3
import threading
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

DEFAULT_DB = Path(os.environ.get("OVERMIND_OBD_DB", Path.home() / ".local/share/overmind/obd.sqlite3"))

SCHEMA = """
CREATE TABLE IF NOT EXISTS messages (
    message_id TEXT PRIMARY KEY,
    kind TEXT NOT NULL, vehicle_id TEXT NOT NULL, gateway_id TEXT NOT NULL,
    session_id TEXT, sequence INTEGER, ts TEXT, received_at REAL NOT NULL, payload TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_messages_vehicle ON messages(vehicle_id, received_at);
CREATE TABLE IF NOT EXISTS measurements (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    vehicle_id TEXT NOT NULL, session_id TEXT, message_id TEXT,
    key TEXT NOT NULL, pid TEXT, value REAL, unit TEXT,
    ts TEXT NOT NULL, ts_epoch REAL NOT NULL, source TEXT, status TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_meas_vkt ON measurements(vehicle_id, key, ts_epoch);
CREATE INDEX IF NOT EXISTS ix_meas_session ON measurements(session_id, ts_epoch);
CREATE TABLE IF NOT EXISTS latest (
    vehicle_id TEXT NOT NULL, key TEXT NOT NULL,
    pid TEXT, unit TEXT, status TEXT, ts TEXT, ts_epoch REAL, source TEXT,
    last_ok_value REAL, last_ok_ts TEXT, last_ok_epoch REAL,
    PRIMARY KEY (vehicle_id, key)
);
CREATE TABLE IF NOT EXISTS dtc_snapshots (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    vehicle_id TEXT NOT NULL, session_id TEXT, message_id TEXT, ts TEXT, mil_on INTEGER, payload TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS status_snapshots (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    vehicle_id TEXT NOT NULL, gateway_id TEXT, session_id TEXT, ts TEXT, state TEXT, payload TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS health (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    vehicle_id TEXT NOT NULL, gateway_id TEXT NOT NULL, ts TEXT, received_at REAL, state TEXT, payload TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS sessions (
    session_id TEXT PRIMARY KEY,
    vehicle_id TEXT NOT NULL, gateway_id TEXT NOT NULL,
    started_at TEXT, last_at TEXT, ended_at TEXT, n_messages INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS gateways (
    gateway_id TEXT NOT NULL, vehicle_id TEXT NOT NULL,
    last_seen REAL, last_seen_iso TEXT, state TEXT, last_sequence INTEGER,
    PRIMARY KEY (gateway_id, vehicle_id)
);
"""


def parse_ts(ts: str | None) -> float:
    if not ts:
        return time.time()
    try:
        return datetime.fromisoformat(ts.replace("Z", "+00:00")).timestamp()
    except ValueError:
        return time.time()


def iso(epoch: float) -> str:
    return datetime.fromtimestamp(epoch, tz=timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


class Store:
    def __init__(self, path: str | Path | None = None):
        self.path = str(path or DEFAULT_DB)
        if self.path != ":memory:":
            Path(self.path).parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.RLock()
        self._db = sqlite3.connect(self.path, check_same_thread=False, isolation_level=None)
        self._db.row_factory = sqlite3.Row
        if self.path != ":memory:":
            self._db.execute("PRAGMA journal_mode=WAL")
        self._db.execute("PRAGMA synchronous=NORMAL")
        self._db.executescript(SCHEMA)

    def close(self) -> None:
        with self._lock:
            self._db.close()

    def _q(self, sql: str, args: tuple = ()) -> list[dict[str, Any]]:
        with self._lock:
            return [dict(r) for r in self._db.execute(sql, args).fetchall()]

    # ------------------------------------------------------------------ écriture
    def ingest(self, kind: str, vehicle_id: str, gateway_id: str, payload: dict[str, Any],
               received_at: float | None = None) -> bool:
        """Enregistre un message validé. Renvoie False si doublon (message_id déjà vu)."""
        now = received_at or time.time()
        mid = payload.get("message_id")
        sid = payload.get("session_id")
        ts = payload.get("timestamp") or iso(now)
        with self._lock:
            db = self._db
            db.execute("BEGIN")
            try:
                if mid:
                    cur = db.execute(
                        "INSERT OR IGNORE INTO messages VALUES (?,?,?,?,?,?,?,?,?)",
                        (mid, kind, vehicle_id, gateway_id, sid, payload.get("sequence"), ts, now,
                         json.dumps(payload, separators=(",", ":"))))
                    if cur.rowcount == 0:
                        db.execute("ROLLBACK")
                        return False
                if kind == "telemetry":
                    self._ingest_measurements(vehicle_id, sid, mid, payload.get("measurements") or [])
                elif kind == "dtc":
                    d = payload["dtc"]
                    db.execute("INSERT INTO dtc_snapshots(vehicle_id,session_id,message_id,ts,mil_on,payload)"
                               " VALUES (?,?,?,?,?,?)",
                               (vehicle_id, sid, mid, ts, None if d.get("mil_on") is None else int(d["mil_on"]),
                                json.dumps(d)))
                elif kind == "status":
                    st = payload["status"]
                    db.execute("INSERT INTO status_snapshots(vehicle_id,gateway_id,session_id,ts,state,payload)"
                               " VALUES (?,?,?,?,?,?)",
                               (vehicle_id, gateway_id, sid, ts, st.get("state"), json.dumps(st)))
                elif kind == "health":
                    h = payload["health"]
                    db.execute("INSERT INTO health(vehicle_id,gateway_id,ts,received_at,state,payload)"
                               " VALUES (?,?,?,?,?,?)",
                               (vehicle_id, gateway_id, ts, now, h.get("state"), json.dumps(h)))
                if sid and kind != "health":
                    # Une session (période de diagnostic) survit aux reconnexions Bluetooth : un status
                    # « disconnected » marque une fin provisoire, effacée si d'autres messages suivent.
                    ended = ts if kind == "status" and payload["status"].get("state") == "disconnected" else None
                    db.execute(
                        "INSERT INTO sessions(session_id,vehicle_id,gateway_id,started_at,last_at,ended_at,n_messages)"
                        " VALUES (?,?,?,?,?,?,1) ON CONFLICT(session_id) DO UPDATE SET"
                        " last_at=MAX(last_at, excluded.last_at), started_at=MIN(started_at, excluded.started_at),"
                        " ended_at=CASE WHEN excluded.ended_at IS NOT NULL THEN excluded.ended_at"
                        " WHEN excluded.last_at >= COALESCE(ended_at, '') THEN NULL ELSE ended_at END,"
                        " n_messages=n_messages+1",
                        (sid, vehicle_id, gateway_id, ts, ts, ended))
                state = "offline" if kind == "health" and payload["health"].get("state") == "offline" else "online"
                db.execute(
                    "INSERT INTO gateways VALUES (?,?,?,?,?,?) ON CONFLICT(gateway_id,vehicle_id) DO UPDATE SET"
                    " last_seen=excluded.last_seen, last_seen_iso=excluded.last_seen_iso, state=excluded.state,"
                    " last_sequence=COALESCE(MAX(last_sequence, excluded.last_sequence), excluded.last_sequence,"
                    " last_sequence)",
                    (gateway_id, vehicle_id, now, iso(now), state, payload.get("sequence")))
                db.execute("COMMIT")
            except Exception:
                db.execute("ROLLBACK")
                raise
        return True

    def _ingest_measurements(self, vehicle_id: str, sid: str | None, mid: str | None,
                             measurements: list[dict[str, Any]]) -> None:
        db = self._db
        for m in measurements:
            ep = parse_ts(m.get("ts"))
            value = m.get("value") if m.get("status") == "ok" else None
            db.execute(
                "INSERT INTO measurements(vehicle_id,session_id,message_id,key,pid,value,unit,ts,ts_epoch,source,status)"
                " VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                (vehicle_id, sid, mid, m["key"], m.get("pid"), value, m.get("unit"), m["ts"], ep,
                 m.get("source"), m["status"]))
            # On ne remplace la dernière valeur que par une mesure plus récente (rejeu hors ordre possible).
            db.execute(
                "INSERT INTO latest(vehicle_id,key,pid,unit,status,ts,ts_epoch,source,last_ok_value,last_ok_ts,last_ok_epoch)"
                " VALUES (?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(vehicle_id,key) DO UPDATE SET"
                " pid=excluded.pid, unit=excluded.unit, status=excluded.status, ts=excluded.ts,"
                " ts_epoch=excluded.ts_epoch, source=excluded.source WHERE excluded.ts_epoch >= latest.ts_epoch",
                (vehicle_id, m["key"], m.get("pid"), m.get("unit"), m["status"], m["ts"], ep, m.get("source"),
                 value, m["ts"] if value is not None else None, ep if value is not None else None))
            if value is not None:
                db.execute(
                    "UPDATE latest SET last_ok_value=?, last_ok_ts=?, last_ok_epoch=? WHERE vehicle_id=? AND key=?"
                    " AND (last_ok_epoch IS NULL OR last_ok_epoch <= ?)",
                    (value, m["ts"], ep, vehicle_id, m["key"], ep))

    def mark_gateway_state(self, gateway_id: str, vehicle_id: str, state: str) -> None:
        with self._lock:
            self._db.execute("UPDATE gateways SET state=? WHERE gateway_id=? AND vehicle_id=?",
                             (state, gateway_id, vehicle_id))

    # ------------------------------------------------------------------ lecture
    def vehicles(self) -> list[str]:
        rows = self._q("SELECT DISTINCT vehicle_id FROM gateways UNION SELECT DISTINCT vehicle_id FROM latest")
        return sorted(r["vehicle_id"] for r in rows)

    def gateways(self, vehicle_id: str | None = None) -> list[dict[str, Any]]:
        if vehicle_id:
            return self._q("SELECT * FROM gateways WHERE vehicle_id=? ORDER BY last_seen DESC", (vehicle_id,))
        return self._q("SELECT * FROM gateways ORDER BY last_seen DESC")

    def latest(self, vehicle_id: str, max_age_s: float | None = None) -> dict[str, dict[str, Any]]:
        out: dict[str, dict[str, Any]] = {}
        now = time.time()
        for r in self._q("SELECT * FROM latest WHERE vehicle_id=?", (vehicle_id,)):
            age = None if r["last_ok_epoch"] is None else now - r["last_ok_epoch"]
            stale = age is None or (max_age_s is not None and age > max_age_s)
            out[r["key"]] = {
                "pid": r["pid"], "unit": r["unit"], "status": r["status"], "ts": r["ts"], "source": r["source"],
                "value": r["last_ok_value"], "value_ts": r["last_ok_ts"],
                "age_s": None if age is None else round(age, 3), "stale": stale,
            }
        return out

    def timeseries(self, vehicle_id: str, key: str, start: float | None = None, end: float | None = None,
                   limit: int = 2000, session_id: str | None = None) -> list[dict[str, Any]]:
        start = start if start is not None else 0.0
        end = end if end is not None else time.time() + 86400
        where = "vehicle_id=? AND key=? AND status='ok' AND ts_epoch BETWEEN ? AND ?"
        args: list[Any] = [vehicle_id, key, start, end]
        if session_id:
            where += " AND session_id=?"
            args.append(session_id)
        n = self._q(f"SELECT COUNT(*) AS n, MIN(ts_epoch) AS a, MAX(ts_epoch) AS b FROM measurements WHERE {where}",
                    tuple(args))[0]
        if not n["n"]:
            return []
        limit = max(1, min(limit, 20000))
        if n["n"] <= limit:
            rows = self._q(f"SELECT ts, ts_epoch, value FROM measurements WHERE {where} ORDER BY ts_epoch",
                           tuple(args))
            return [{"ts": r["ts"], "value": r["value"]} for r in rows]
        width = max((n["b"] - n["a"]) / limit, 1e-3)
        rows = self._q(
            f"SELECT CAST((ts_epoch - ?) / ? AS INTEGER) AS bkt, AVG(ts_epoch) AS t, AVG(value) AS v,"
            f" MIN(value) AS lo, MAX(value) AS hi, COUNT(*) AS c FROM measurements WHERE {where}"
            f" GROUP BY bkt ORDER BY bkt", tuple([n["a"], width] + args))
        return [{"ts": iso(r["t"]), "value": r["v"], "min": r["lo"], "max": r["hi"], "n": r["c"]} for r in rows]

    def keys(self, vehicle_id: str) -> list[str]:
        return [r["key"] for r in self._q("SELECT key FROM latest WHERE vehicle_id=? ORDER BY key", (vehicle_id,))]

    def latest_dtc(self, vehicle_id: str) -> dict[str, Any] | None:
        r = self._q("SELECT * FROM dtc_snapshots WHERE vehicle_id=? ORDER BY id DESC LIMIT 1", (vehicle_id,))
        if not r:
            return None
        return {"ts": r[0]["ts"], "session_id": r[0]["session_id"], **json.loads(r[0]["payload"])}

    def dtc_history(self, vehicle_id: str, limit: int = 50) -> list[dict[str, Any]]:
        rows = self._q("SELECT * FROM dtc_snapshots WHERE vehicle_id=? ORDER BY id DESC LIMIT ?", (vehicle_id, limit))
        return [{"ts": r["ts"], "session_id": r["session_id"], **json.loads(r["payload"])} for r in rows]

    def latest_status(self, vehicle_id: str, require: str | None = None) -> dict[str, Any] | None:
        """Dernier statut ; avec `require`, le dernier statut contenant ce champ (ex. `elm`, `supported_pids`)."""
        rows = self._q("SELECT * FROM status_snapshots WHERE vehicle_id=? ORDER BY id DESC LIMIT ?",
                       (vehicle_id, 1 if require is None else 200))
        for r in rows:
            payload = json.loads(r["payload"])
            if require is None or payload.get(require) is not None:
                return {"ts": r["ts"], "gateway_id": r["gateway_id"], "session_id": r["session_id"], **payload}
        return None

    def latest_health(self, vehicle_id: str) -> dict[str, Any] | None:
        r = self._q("SELECT * FROM health WHERE vehicle_id=? ORDER BY id DESC LIMIT 1", (vehicle_id,))
        if not r:
            return None
        return {"ts": r[0]["ts"], "gateway_id": r[0]["gateway_id"], "received_at": iso(r[0]["received_at"]),
                **json.loads(r[0]["payload"])}

    def supported_pids(self, vehicle_id: str) -> list[str]:
        st = self.latest_status(vehicle_id, require="supported_pids")
        return list((st or {}).get("supported_pids") or [])

    def sessions(self, vehicle_id: str | None = None, limit: int = 50) -> list[dict[str, Any]]:
        if vehicle_id:
            return self._q("SELECT * FROM sessions WHERE vehicle_id=? ORDER BY started_at DESC LIMIT ?",
                           (vehicle_id, limit))
        return self._q("SELECT * FROM sessions ORDER BY started_at DESC LIMIT ?", (limit,))

    def session(self, session_id: str) -> dict[str, Any] | None:
        r = self._q("SELECT * FROM sessions WHERE session_id=?", (session_id,))
        if not r:
            return None
        s = r[0]
        stats = self._q(
            "SELECT key, unit, COUNT(*) AS n, MIN(value) AS min, MAX(value) AS max, AVG(value) AS avg"
            " FROM measurements WHERE session_id=? AND status='ok' GROUP BY key, unit ORDER BY key", (session_id,))
        s["stats"] = stats
        s["dtc"] = [json.loads(d["payload"]) | {"ts": d["ts"]} for d in
                    self._q("SELECT ts, payload FROM dtc_snapshots WHERE session_id=? ORDER BY id", (session_id,))]
        return s

    def message_count(self) -> int:
        return self._q("SELECT COUNT(*) AS n FROM messages")[0]["n"]

    def frames(self, vehicle_id: str, keys: list[str], start: float, end: float | None = None,
               limit: int = 5000) -> list[dict[str, Any]]:
        """Trames d'acquisition alignées : une ligne par message de télémétrie, {ts, epoch, key: valeur}.

        Seules les mesures `ok` sont présentes ; une clé absente = non lue dans cette trame.
        """
        end = end if end is not None else time.time() + 86400
        if not keys:
            return []
        ph = ",".join("?" * len(keys))
        rows = self._q(
            f"SELECT message_id, key, value, ts_epoch FROM measurements WHERE vehicle_id=? AND status='ok'"
            f" AND key IN ({ph}) AND ts_epoch BETWEEN ? AND ? ORDER BY ts_epoch DESC LIMIT ?",
            tuple([vehicle_id, *keys, start, end, limit * len(keys)]))
        frames: dict[str, dict[str, Any]] = {}
        for r in rows:
            f = frames.setdefault(r["message_id"] or f"_{r['ts_epoch']}", {"epoch": r["ts_epoch"]})
            f[r["key"]] = r["value"]
            f["epoch"] = max(f["epoch"], r["ts_epoch"])
        out = sorted(frames.values(), key=lambda f: f["epoch"])[-limit:]
        for f in out:
            f["ts"] = iso(f["epoch"])
        return out
