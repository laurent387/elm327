#!/usr/bin/env python3
"""Passerelle factice : ELM327 (TCP, ex. simulateur) -> MQTT au format exact du contrat v1.0.

Sert à tester la chaîne serveur Overmind sans téléphone :

    python3 tools/elm327_sim/elm327_sim.py --port 35000 &
    python3 tools/fake_gateway.py --elm 127.0.0.1:35000 --mqtt 127.0.0.1:1883 \
        --vehicle slk_r171 --gateway android_01 --duration 60

Comme l'application Android : lecture seule (modes 01, 03, 07, 0A, 09), jamais de mode 04,
aucun abonnement MQTT (aucune commande ne peut redescendre vers le véhicule).
"""

from __future__ import annotations

import argparse
import json
import re
import socket
import ssl
import sys
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path

CATALOG = Path(__file__).resolve().parent.parent / "schemas" / "pid_catalog.json"
SAFE_FORMULA = re.compile(r"^[0-9A-D+\-*/(). ]+$")
ALLOWED_MODES = {"01", "03", "07", "0A", "09"}


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def load_catalog() -> dict[str, dict]:
    return {p["pid"]: p for p in json.loads(CATALOG.read_text(encoding="utf-8"))["pids"]
            if SAFE_FORMULA.match(p["formula"])}


def decode(formula: str, data: list[int]) -> float:
    env = dict(zip("ABCD", data + [0] * 4))
    return round(float(eval(formula, {"__builtins__": {}}, env)), 3)  # formules filtrées par SAFE_FORMULA


class Elm:
    def __init__(self, host: str, port: int, timeout: float = 5.0):
        self.sock = socket.create_connection((host, port), timeout=timeout)

    def cmd(self, c: str) -> list[str]:
        if not c.upper().startswith("AT") and c[:2].upper() not in ALLOWED_MODES:
            raise ValueError(f"commande refusée (lecture seule) : {c}")
        self.sock.sendall((c + "\r").encode())
        buf = b""
        while not buf.endswith(b">"):
            chunk = self.sock.recv(4096)
            if not chunk:
                raise ConnectionError("ELM327 fermé")
            buf += chunk
        lines = [ln.strip() for ln in buf[:-1].decode("ascii", "replace").replace("\n", "\r").split("\r")]
        return [ln for ln in lines if ln and ln != c and ln != "SEARCHING..."]

    def init(self) -> dict:
        self.cmd("ATZ")
        for c in ("ATE0", "ATL0", "ATS0", "ATH1", "ATAT1", "ATSP0"):
            self.cmd(c)
        version = self.cmd("ATI")[0]
        self.cmd("0100")  # déclenche la détection de protocole
        return {"version": version, "protocol": self.cmd("ATDPN")[0].lstrip("A"),
                "protocol_name": self.cmd("ATDP")[0], "voltage_v": float(self.cmd("ATRV")[0].rstrip("Vv"))}

    def request(self, c: str) -> dict[str, list[int]]:
        """Renvoie {ecu: octets de réponse} (ATH1, ATS0, CAN 11 bits, ISO-TP réassemblé)."""
        out: dict[str, list[int]] = {}
        pending: dict[str, tuple[int, list[int]]] = {}
        for ln in self.cmd(c):
            if ln in ("NO DATA", "?", "UNABLE TO CONNECT", "CAN ERROR", "STOPPED") or len(ln) < 5:
                continue
            ecu, body = ln[:3], [int(ln[i:i + 2], 16) for i in range(3, len(ln) - 1, 2)]
            pci = body[0] >> 4
            if pci == 0:
                out[ecu] = body[1:1 + body[0]]
            elif pci == 1:
                pending[ecu] = (((body[0] & 0xF) << 8) | body[1], body[2:])
            elif pci == 2 and ecu in pending:
                size, acc = pending[ecu]
                acc = acc + body[1:]
                if len(acc) >= size:
                    out[ecu] = acc[:size]
                    del pending[ecu]
                else:
                    pending[ecu] = (size, acc)
        return out

    def supported_pids(self) -> list[str]:
        sup: set[int] = set()
        base = 0
        while base <= 0xC0:
            resp = self.request(f"01{base:02X}")
            if not resp:
                break
            nxt = False
            for data in resp.values():
                if len(data) >= 6 and data[0] == 0x41 and data[1] == base:
                    bits = int.from_bytes(bytes(data[2:6]), "big")
                    for i in range(32):
                        if bits & (1 << (31 - i)):
                            sup.add(base + i + 1)
                    nxt = nxt or bool(bits & 1)
            if not nxt:
                break
            base += 0x20
        return [f"01{p:02X}" for p in sorted(sup) if p % 0x20 != 0]

    def dtcs(self, mode: str) -> list[dict] | None:
        resp = self.request(mode)
        if not resp:
            return None
        codes = []
        for ecu, data in resp.items():
            if not data or data[0] != 0x40 + int(mode, 16):
                continue
            body = data[2:]  # CAN : octet de comptage
            for i in range(0, len(body) - 1, 2):
                a, b = body[i], body[i + 1]
                if a == 0 and b == 0:
                    continue
                codes.append({"code": "PCBU"[a >> 6] + f"{(a >> 4) & 3}{a & 0xF:X}{b:02X}", "ecu": ecu})
        return codes

    def vin(self) -> str | None:
        for data in self.request("0902").values():
            if data[:2] == [0x49, 0x02]:
                return bytes(b for b in data[3:] if 32 < b < 127).decode()
        return None


class Gateway:
    def __init__(self, args: argparse.Namespace):
        import paho.mqtt.client as mqtt

        self.args = args
        self.seq_file = Path(args.state_file)
        self.seq = int(self.seq_file.read_text()) if self.seq_file.exists() else 0
        self.session_id = str(uuid.uuid4())
        self.base_topic = f"overmind/vehicles/{args.vehicle}"
        self.client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id=args.gateway, clean_session=False)
        if args.user:
            self.client.username_pw_set(args.user, args.password)
        if args.tls:
            self.client.tls_set(ca_certs=args.ca, cert_reqs=ssl.CERT_REQUIRED)
        lwt = {"schema_version": "1.0", "kind": "health", "vehicle_id": args.vehicle, "gateway_id": args.gateway,
               "health": {"state": "offline"}}
        self.client.will_set(f"{self.base_topic}/gateway/health", json.dumps(lwt), qos=1)
        host, port = args.mqtt.rsplit(":", 1)
        self.client.connect(host, int(port), keepalive=30)
        self.client.loop_start()
        self.published = 0

    def envelope(self, kind: str) -> dict:
        self.seq += 1
        self.seq_file.write_text(str(self.seq))
        return {"schema_version": "1.0", "kind": kind, "message_id": str(uuid.uuid4()),
                "vehicle_id": self.args.vehicle, "gateway_id": self.args.gateway, "session_id": self.session_id,
                "sequence": self.seq, "timestamp": now_iso(), "engine_type": self.args.engine_type}

    def publish(self, topic: str, msg: dict, retain: bool = False) -> None:
        info = self.client.publish(f"{self.base_topic}/{topic}", json.dumps(msg, separators=(",", ":")), qos=1,
                                   retain=retain)
        info.wait_for_publish(timeout=10)
        self.published += 1

    def run(self) -> int:
        a = self.args
        host, port = a.elm.rsplit(":", 1)
        elm = Elm(host, int(port))
        info = elm.init()
        supported = elm.supported_pids()
        catalog = load_catalog()
        polled = [p for p in (a.pids.split(",") if a.pids else catalog) if p in supported and p in catalog]
        st = self.envelope("status")
        st["status"] = {"state": "connected", "elm": info, "vin": elm.vin(), "supported_pids": supported,
                        "polled_pids": polled, "poll_interval_ms": int(a.interval * 1000)}
        self.publish("obd/status", st, retain=True)
        dtc = self.envelope("dtc")
        dtc["dtc"] = {"stored": elm.dtcs("03") or [], "pending": elm.dtcs("07") or [], "permanent": elm.dtcs("0A"),
                      "read_at": now_iso()}
        mil = elm.request("0101")
        dtc["dtc"]["mil_on"] = next((bool(d[2] & 0x80) for d in mil.values() if d[:2] == [0x41, 0x01]), None)
        self.publish("obd/dtc", dtc)
        baro: tuple[float, float] | None = None
        start = time.monotonic()
        n = 0
        while time.monotonic() - start < a.duration and (a.count <= 0 or n < a.count):
            t0 = time.monotonic()
            meas, data = [], {}
            for pid in polled:
                c = catalog[pid]
                resp = elm.request(pid)
                ts = now_iso()
                hit = next(((ecu, d) for ecu, d in resp.items() if d[:2] == [0x41, int(pid[2:], 16)]), None)
                if hit:
                    val = decode(c["formula"], hit[1][2:])
                    meas.append({"key": c["key"], "pid": pid, "value": val, "unit": c["unit"], "ts": ts,
                                 "source": f"obd2:{hit[0]}", "status": "ok"})
                    data[c["key"]] = val
                    if pid == "0133":
                        baro = (val, time.monotonic())
                else:
                    meas.append({"key": c["key"], "pid": pid, "value": None, "unit": c["unit"], "ts": ts,
                                 "source": "obd2", "status": "no_data"})
            # contrat : boost relatif seulement si MAP lu dans la trame ET BARO lu il y a < 60 s
            if "map_kpa_absolute" in data and baro and time.monotonic() - baro[1] < 60:
                b = round(data["map_kpa_absolute"] - baro[0], 1)
                meas.append({"key": "boost_kpa_relative", "pid": None, "value": b, "unit": "kPa", "ts": now_iso(),
                             "source": "derived:010B+0133", "status": "ok"})
                data["boost_kpa_relative"] = b
            msg = self.envelope("telemetry")
            msg.update(data=data, measurements=meas,
                       context={"aspiration": a.aspiration, "map_sensor_location": a.map_location})
            self.publish("obd/telemetry", msg)
            n += 1
            if n % 10 == 1:
                h = self.envelope("health")
                h["health"] = {"state": "online", "app_version": "fake-gateway", "bt_connected": True,
                               "network": "wifi", "outbox_pending": 0, "uptime_s": int(time.monotonic() - start)}
                self.publish("gateway/health", h)
            time.sleep(max(0.0, a.interval - (time.monotonic() - t0)))
        end = self.envelope("status")
        end["status"] = {"state": "disconnected"}
        self.publish("obd/status", end, retain=True)
        self.client.disconnect()
        self.client.loop_stop()
        print(f"{self.published} messages publiés (session {self.session_id})")
        return 0


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--elm", default="127.0.0.1:35000")
    ap.add_argument("--mqtt", default="127.0.0.1:1883")
    ap.add_argument("--user")
    ap.add_argument("--password")
    ap.add_argument("--tls", action="store_true")
    ap.add_argument("--ca")
    ap.add_argument("--vehicle", default="slk_r171")
    ap.add_argument("--gateway", default="android_01")
    ap.add_argument("--engine-type", default="petrol", choices=["petrol", "diesel", "unknown"])
    ap.add_argument("--aspiration", default="supercharger")
    ap.add_argument("--map-location", default="unknown")
    ap.add_argument("--pids", default="010C,010B,0104,010D,0105,0133,0106,0107,0110,0111,010F",
                    help="PID à interroger (intersection avec les PID supportés)")
    ap.add_argument("--interval", type=float, default=0.5)
    ap.add_argument("--duration", type=float, default=30)
    ap.add_argument("--count", type=int, default=0, help="nombre de trames (0 = selon --duration)")
    ap.add_argument("--state-file", default=str(Path.home() / ".cache" / "overmind_fake_gateway.seq"))
    return Gateway(ap.parse_args(argv)).run()


if __name__ == "__main__":
    sys.exit(main())
