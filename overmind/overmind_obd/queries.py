"""Requêtes lecture seule partagées par l'API HTTP et le serveur MCP."""

from __future__ import annotations

import json
import time
from functools import lru_cache
from typing import Any

from . import PID_CATALOG_PATH
from .analysis import analyze
from .knowledge import describe_dtc
from .registry import Registry
from .report import generate_report
from .store import Store, parse_ts

LOST_AFTER_S = 30.0
STALE_AFTER_S = 10.0


@lru_cache(maxsize=1)
def pid_catalog() -> dict[str, dict[str, Any]]:
    with open(PID_CATALOG_PATH, encoding="utf-8") as fh:
        return {p["pid"]: p for p in json.load(fh)["pids"]}


class ObdQueries:
    def __init__(self, store: Store, registry: Registry, lost_after_s: float = LOST_AFTER_S):
        self.store = store
        self.registry = registry
        self.lost_after_s = lost_after_s

    def _check_vehicle(self, vehicle_id: str) -> None:
        if vehicle_id not in self.registry.vehicles and vehicle_id not in self.store.vehicles():
            raise KeyError(f"véhicule inconnu : {vehicle_id}")

    def list_vehicles(self) -> list[dict[str, Any]]:
        ids = sorted(set(self.registry.vehicles) | set(self.store.vehicles()))
        out = []
        for vid in ids:
            st = self.connection_status(vid)
            out.append({**self.registry.profile(vid).as_dict(), "connection": st["gateway_state"],
                        "last_seen": st["last_seen"]})
        return out

    def connection_status(self, vehicle_id: str) -> dict[str, Any]:
        gws = self.store.gateways(vehicle_id)
        now = time.time()
        gw = gws[0] if gws else None
        state = "never_seen"
        if gw:
            state = gw["state"] or "unknown"
            if state == "online" and now - (gw["last_seen"] or 0) > self.lost_after_s:
                state = "lost"
        status = self.store.latest_status(vehicle_id)
        last_elm = self.store.latest_status(vehicle_id, require="elm")
        health = self.store.latest_health(vehicle_id)
        return {
            "vehicle_id": vehicle_id,
            "gateway_id": gw["gateway_id"] if gw else None,
            "gateway_state": state,
            "last_seen": gw["last_seen_iso"] if gw else None,
            "seconds_since_last_message": None if not gw else round(now - gw["last_seen"], 1),
            "obd_state": (status or {}).get("state"),
            "elm": (last_elm or {}).get("elm"),
            "vin": (last_elm or {}).get("vin"),
            "health": health,
        }

    def supported_pids(self, vehicle_id: str) -> dict[str, Any]:
        st = self.store.latest_status(vehicle_id, require="supported_pids") or {}
        cat = pid_catalog()
        sup = st.get("supported_pids") or []
        return {
            "vehicle_id": vehicle_id, "reported_at": st.get("ts"),
            "supported": [{"pid": p, **({k: cat[p][k] for k in ("key", "unit", "label")} if p in cat else
                                        {"key": None, "unit": None, "label": "PID non catalogué"})} for p in sup],
            "polled": st.get("polled_pids") or [],
            "poll_interval_ms": st.get("poll_interval_ms"),
        }

    def live_data(self, vehicle_id: str, keys: list[str] | None = None) -> dict[str, Any]:
        latest = self.store.latest(vehicle_id, max_age_s=STALE_AFTER_S)
        if keys:
            latest = {k: v for k, v in latest.items() if k in keys}
        return {"vehicle_id": vehicle_id, "connection": self.connection_status(vehicle_id)["gateway_state"],
                "values": latest,
                "note": "value = dernière valeur valide ; stale = plus vieille que %d s ou jamais valide" % STALE_AFTER_S}

    def dtc(self, vehicle_id: str) -> dict[str, Any]:
        d = self.store.latest_dtc(vehicle_id)
        if not d:
            return {"vehicle_id": vehicle_id, "available": False}
        p = self.registry.profile(vehicle_id)
        for kind in ("stored", "pending", "permanent"):
            if d.get(kind):
                d[kind] = [{**x, **{k: v for k, v in describe_dtc(x["code"], p.manufacturer, p.engine_code).items()
                                    if k in ("description", "system", "severity", "causes", "checks", "note")}}
                           for x in d[kind]]
        return {"vehicle_id": vehicle_id, "available": True, **d}

    def history(self, vehicle_id: str, key: str, start: str | None = None, end: str | None = None,
                last_minutes: float | None = None, limit: int = 500, session_id: str | None = None) -> dict[str, Any]:
        e = parse_ts(end) if end else time.time()
        s = parse_ts(start) if start else (e - last_minutes * 60 if last_minutes else None)
        pts = self.store.timeseries(vehicle_id, key, s, e, limit, session_id)
        unit = (self.store.latest(vehicle_id).get(key) or {}).get("unit")
        return {"vehicle_id": vehicle_id, "key": key, "unit": unit, "points": pts, "n": len(pts)}

    def sessions(self, vehicle_id: str | None = None, limit: int = 20) -> list[dict[str, Any]]:
        return self.store.sessions(vehicle_id, limit)

    def session(self, session_id: str) -> dict[str, Any] | None:
        return self.store.session(session_id)

    def analyze(self, vehicle_id: str, window_minutes: float = 15) -> dict[str, Any]:
        return analyze(self.store, self.registry, vehicle_id, window_minutes * 60)

    def report(self, vehicle_id: str, window_minutes: float = 15, session_id: str | None = None) -> str:
        return generate_report(self.store, self.registry, vehicle_id, window_minutes * 60, session_id)
