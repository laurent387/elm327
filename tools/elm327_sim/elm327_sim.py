#!/usr/bin/env python3
"""Simulateur ELM327 (v1.5) — TCP comme un adaptateur Wi-Fi, ou pseudo-terminal série.

    python3 tools/elm327_sim/elm327_sim.py                       # 0.0.0.0:35000
    python3 tools/elm327_sim/elm327_sim.py --port 35000 --dtc P0171 --pending P0300 --multi-ecu
    python3 tools/elm327_sim/elm327_sim.py --serial-pty          # affiche /dev/pts/N

Simule un moteur essence à compresseur (profil configurable) : ralenti, accélération, croisière,
décélération ; montée en température ; trims ; MAP/BARO ; DTC injectables (modes 03/07/0A) ;
VIN (09 02, multi-trame ISO-TP). Mode 04 (effacement) REFUSÉ (« ? ») : l'application ne doit
jamais effacer de défauts. Usage exclusif : tests et développement, aucune donnée réelle.
"""

from __future__ import annotations

import argparse
import math
import os
import random
import socket
import sys
import threading
import time
from dataclasses import dataclass, field

PROTOCOLS = {
    "1": "SAE J1850 PWM", "2": "SAE J1850 VPW", "3": "ISO 9141-2", "4": "ISO 14230-4 (KWP 5BAUD)",
    "5": "ISO 14230-4 (KWP FAST)", "6": "ISO 15765-4 (CAN 11/500)", "7": "ISO 15765-4 (CAN 29/500)",
    "8": "ISO 15765-4 (CAN 11/250)", "9": "ISO 15765-4 (CAN 29/250)",
}

# PID -> encodeur (état moteur -> octets de données)
def _u8(x: float) -> list[int]:
    return [max(0, min(255, int(round(x))))]


def _u16(x: float) -> list[int]:
    v = max(0, min(65535, int(round(x))))
    return [v >> 8, v & 0xFF]


ENCODERS = {
    0x04: lambda e: _u8(e.load * 255 / 100),
    0x05: lambda e: _u8(e.coolant + 40),
    0x06: lambda e: _u8((e.stft + 100) * 128 / 100),
    0x07: lambda e: _u8((e.ltft + 100) * 128 / 100),
    0x0B: lambda e: _u8(min(e.map_kpa, 255)),
    0x0C: lambda e: _u16(e.rpm * 4),
    0x0D: lambda e: _u8(e.speed),
    0x0E: lambda e: _u8((e.timing + 64) * 2),
    0x0F: lambda e: _u8(e.iat + 40),
    0x10: lambda e: _u16(e.maf * 100),
    0x11: lambda e: _u8(e.throttle * 255 / 100),
    0x14: lambda e: [int(e.o2_v * 200) & 0xFF, 0xFF],
    0x15: lambda e: [int(0.68 * 200), 0xFF],
    0x1F: lambda e: _u16(e.run_time),
    0x2F: lambda e: _u8(62 * 255 / 100),
    0x33: lambda e: _u8(e.baro),
    0x42: lambda e: _u16(e.module_v * 1000),
    0x46: lambda e: _u8(e.ambient + 40),
    0x5C: lambda e: _u8(e.coolant - 4 + 40),
}
# PID de support : 0x00, 0x20, 0x40 ... -> bitmap calculé à partir des PID simulés
SUPPORT_PIDS = (0x00, 0x20, 0x40, 0x60)


@dataclass
class Engine:
    """Modèle moteur simple, déterministe à graine fixe (valeurs simulées, pas des données réelles)."""

    displacement_l: float = 1.8
    supercharged: bool = True
    idle_rpm: float = 720
    baro: float = 101.0
    ambient: float = 18.0
    coolant: float = 25.0
    lean_bias: float = 0.0          # % ajouté aux trims (pour simuler une prise d'air)
    cycle_s: float = 60.0
    start: float = field(default_factory=time.monotonic)
    rng: random.Random = field(default_factory=lambda: random.Random(42))
    # état
    rpm: float = 0
    engine_off: bool = False
    speed: float = 0
    throttle: float = 0
    load: float = 0
    map_kpa: float = 101
    maf: float = 0
    iat: float = 20
    stft: float = 0
    ltft: float = 0
    timing: float = 10
    o2_v: float = 0.45
    module_v: float = 14.1
    run_time: float = 0

    def update(self) -> None:
        t = time.monotonic() - self.start
        self.run_time = t
        phase = (t % self.cycle_s) / self.cycle_s
        if phase < 0.3:                      # ralenti
            thr, target_rpm, spd = 2, self.idle_rpm, 0
        elif phase < 0.45:                   # accélération pleine charge
            k = (phase - 0.3) / 0.15
            thr, target_rpm, spd = 95, 1500 + 4500 * k, 20 + 80 * k
        elif phase < 0.8:                    # croisière
            thr, target_rpm, spd = 22, 2400, 90
        else:                                # décélération
            k = (phase - 0.8) / 0.2
            thr, target_rpm, spd = 0, 2400 - 1500 * k, 90 - 85 * k
        if self.engine_off:                  # contact mis, moteur coupé
            thr, target_rpm, spd = 0, 0, 0
        j = self.rng.uniform(-1, 1)
        self.throttle = max(0.0, thr + j)
        self.rpm = 0.0 if self.engine_off else max(0.0, target_rpm + 15 * j)
        self.speed = max(0.0, spd)
        # pression collecteur : dépression au ralenti, suralimentation en pleine charge
        if self.throttle > 80 and self.supercharged:
            self.map_kpa = self.baro + 55 * min(1.0, self.rpm / 4500) + 2 * j
        elif self.throttle > 80:
            self.map_kpa = self.baro - 3
        else:
            self.map_kpa = 32 + self.throttle * 1.6 + j
        self.load = min(100.0, self.map_kpa / self.baro * 100 * 0.78)
        self.iat = self.ambient + 8 + (25 if self.throttle > 80 and self.supercharged else 0) + j
        density = 1.184 * (self.map_kpa / 101.3) * (298.15 / (self.iat + 273.15))
        self.maf = self.displacement_l * self.rpm / 120 * density * 0.85
        self.coolant = min(90.0, 25 + t * 0.6)
        idle = self.throttle < 5
        self.stft = 2.5 * math.sin(t * 3) + (self.lean_bias if idle else self.lean_bias * 0.2)
        self.ltft = 3.0 + (self.lean_bias * 0.5 if idle else 0.0)
        self.timing = 12 + (8 if idle else 20 - self.load / 10)
        self.o2_v = 0.45 + 0.4 * math.sin(t * 7)
        self.module_v = 14.1 + 0.05 * j


def encode_dtc(code: str) -> list[int]:
    kind = "PCBU".index(code[0])
    first = (kind << 6) | (int(code[1], 16) << 4) | int(code[2], 16)
    return [first, int(code[3:5], 16)]


class ElmState:
    def __init__(self, args: argparse.Namespace):
        self.args = args
        self.engine = Engine(lean_bias=args.lean_bias, supercharged=not args.na, engine_off=args.engine_off)
        self.pids = sorted(ENCODERS) if not args.pids else sorted(int(p, 16) for p in args.pids.split(","))
        self.reset()

    def reset(self) -> None:
        self.echo, self.linefeed, self.spaces, self.headers = True, False, True, False
        self.protocol = "0"          # auto
        self.detected = False

    # ------------------------------------------------------------------ formatage CAN
    def _hex(self, bs: list[int]) -> str:
        return (" " if self.spaces else "").join(f"{b:02X}" for b in bs)

    def frames(self, ecu: int, payload: list[int]) -> list[str]:
        """Réponse ISO-TP telle qu'affichée par l'ELM327 (CAN auto-formatting, ATH0/ATH1)."""
        hdr = f"{ecu:03X}"
        if len(payload) <= 7:
            if self.headers:
                return [hdr + (" " if self.spaces else "") + self._hex([len(payload)] + payload)]
            return [self._hex(payload)]
        lines = []
        if self.headers:
            lines.append(hdr + (" " if self.spaces else "") + self._hex([0x10 | (len(payload) >> 8), len(payload) & 0xFF] + payload[:6]))
            rest, sn = payload[6:], 1
            while rest:
                chunk = rest[:7] + [0x00] * (7 - len(rest[:7]))
                lines.append(hdr + (" " if self.spaces else "") + self._hex([0x20 | (sn & 0xF)] + chunk))
                rest, sn = rest[7:], sn + 1
            return lines
        lines.append(f"{len(payload):03X}")
        lines.append(f"0:{' ' if self.spaces else ''}" + self._hex(payload[:6]))
        rest, sn = payload[6:], 1
        while rest:
            lines.append(f"{sn & 0xF:X}:{' ' if self.spaces else ''}" + self._hex(rest[:7] + [0] * (7 - len(rest[:7]))))
            rest, sn = rest[7:], sn + 1
        return lines

    # ------------------------------------------------------------------ commandes
    def handle(self, raw: str) -> list[str]:
        cmd = raw.strip().upper().replace(" ", "")
        if not cmd:
            return []
        if cmd.startswith("AT"):
            return self.at(cmd[2:])
        if not all(c in "0123456789ABCDEF" for c in cmd):
            return ["?"]
        pre = []
        if self.protocol == "0" and not self.detected:
            pre = ["SEARCHING..."]
            self.detected = True
        if self.args.no_vehicle:
            return pre + ["UNABLE TO CONNECT"]
        return pre + self.obd(cmd)

    def at(self, c: str) -> list[str]:
        if c in ("Z", "WS"):
            self.reset()
            return ["", "ELM327 v1.5"]
        if c == "D":
            self.reset()
            return ["OK"]
        if c == "I":
            return ["ELM327 v1.5"]
        if c == "@1":
            return ["OBDII to RS232 Interpreter"]
        if c == "RV":
            return [f"{(14.1 if self.engine.rpm > 0 else 12.6) + random.uniform(-0.05, 0.05):.1f}V"]
        for flag, attr in (("E", "echo"), ("L", "linefeed"), ("S", "spaces"), ("H", "headers")):
            if c in (flag + "0", flag + "1"):
                setattr(self, attr, c.endswith("1"))
                return ["OK"]
        if c.startswith("SP") or c.startswith("TP"):
            p = c[2:].lstrip("A") or "0"
            if p not in PROTOCOLS and p != "0":
                return ["?"]
            self.protocol, self.detected = p, p != "0"
            return ["OK"]
        if c == "DPN":
            return [("A6" if self.protocol == "0" else self.protocol) if self.detected or self.protocol != "0" else "0"]
        if c == "DP":
            if self.protocol == "0":
                return ["AUTO, " + PROTOCOLS["6"] if self.detected else "AUTO"]
            return [PROTOCOLS.get(self.protocol, "?")]
        if c[:2] in ("AT", "ST", "AL", "CA", "M0", "M1", "BI") or c in ("NL", "CAF0", "CAF1", "CFC1", "R1", "R0"):
            return ["OK"]
        return ["?"]

    def obd(self, cmd: str) -> list[str]:
        if len(cmd) % 2 == 1:  # indicateur de nombre de réponses ("010C1")
            cmd = cmd[:-1]
        mode = int(cmd[:2], 16)
        e = self.engine
        e.update()
        if mode == 0x01:
            pids = [int(cmd[i:i + 2], 16) for i in range(2, len(cmd), 2)][:6]
            if not pids:
                return ["?"]
            payload, ecu2 = [0x41], [0x41]
            for p in pids:
                if p in SUPPORT_PIDS:
                    payload += [p] + self.bitmap(p, self.pids + [0x01])
                    ecu2 += [p] + self.bitmap(p, [0x05, 0x0D, 0x11, 0x1F])
                elif p in self.pids:
                    payload += [p] + ENCODERS[p](e)
                elif p == 0x01:
                    stored = len(self.args.dtc)
                    payload += [0x01, (0x80 if stored else 0) | stored, 0x07, 0x65, 0x00]
            if len(payload) == 1:
                return ["NO DATA"]
            out = self.frames(0x7E8, payload)
            if self.args.multi_ecu and all(p in SUPPORT_PIDS for p in pids):
                out += self.frames(0x7E9, ecu2)
            return out
        if mode in (0x03, 0x07, 0x0A):
            if mode == 0x0A and self.args.no_permanent:
                return ["NO DATA"]
            codes = {0x03: self.args.dtc, 0x07: self.args.pending, 0x0A: self.args.permanent}[mode]
            payload = [0x40 + mode, len(codes)]
            for c in codes:
                payload += encode_dtc(c)
            return self.frames(0x7E8, payload)
        if mode == 0x04:
            # comme un vrai calculateur : refus (7F 04 22, conditions non remplies) moteur tournant
            if e.rpm > 0:
                return self.frames(0x7E8, [0x7F, 0x04, 0x22])
            self.args.dtc, self.args.pending = [], []
            return self.frames(0x7E8, [0x44])
        if mode == 0x09 and cmd[2:4] == "02":
            return self.frames(0x7E8, [0x49, 0x02, 0x01] + list(self.args.vin.encode("ascii")))
        if mode == 0x09 and cmd[2:4] == "00":
            return self.frames(0x7E8, [0x49, 0x00, 0x40, 0x00, 0x00, 0x00])
        return ["NO DATA"]

    @staticmethod
    def bitmap(base: int, pids: list[int]) -> list[int]:
        bits = 0
        for p in pids:
            if base < p <= base + 0x20:
                bits |= 1 << (32 - (p - base))
        # bit « PID suivant supporté » si un PID au-delà existe
        if any(p > base + 0x20 for p in pids):
            bits |= 1
        return [(bits >> 24) & 0xFF, (bits >> 16) & 0xFF, (bits >> 8) & 0xFF, bits & 0xFF]


def serve_stream(read, write, state: ElmState, latency_s: float) -> None:
    buf = b""
    while True:
        chunk = read()
        if not chunk:
            return
        buf += chunk
        while b"\r" in buf:
            line, buf = buf.split(b"\r", 1)
            text = line.decode("ascii", "replace").replace("\n", "")
            out = ""
            if state.echo:
                out += text + "\r"
            if latency_s and not text.upper().startswith("AT"):
                time.sleep(latency_s)
            eol = "\r\n" if state.linefeed else "\r"
            for resp in state.handle(text):
                out += resp + eol
            out += eol + ">"
            write(out.encode("ascii"))


def serve_tcp(args: argparse.Namespace) -> None:
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((args.host, args.port))
    srv.listen(4)
    print(f"ELM327 simulé à l'écoute sur {args.host}:{srv.getsockname()[1]}", flush=True)

    def client(conn: socket.socket) -> None:
        with conn:
            conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            state = ElmState(args)
            try:
                serve_stream(lambda: conn.recv(1024), conn.sendall, state, args.latency_ms / 1000)
            except OSError:
                pass

    while True:
        conn, _ = srv.accept()
        threading.Thread(target=client, args=(conn,), daemon=True).start()


def serve_pty(args: argparse.Namespace) -> None:
    import pty
    import tty

    master, slave = pty.openpty()
    tty.setraw(slave)
    print(f"ELM327 simulé sur {os.ttyname(slave)}", flush=True)
    serve_stream(lambda: os.read(master, 1024), lambda b: os.write(master, b), ElmState(args), args.latency_ms / 1000)


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--port", type=int, default=35000)
    ap.add_argument("--serial-pty", action="store_true", help="pseudo-terminal série au lieu de TCP")
    ap.add_argument("--latency-ms", type=float, default=40, help="délai de réponse OBD simulé")
    ap.add_argument("--dtc", action="append", default=[], help="code confirmé (mode 03), répétable")
    ap.add_argument("--pending", action="append", default=[], help="code en attente (mode 07)")
    ap.add_argument("--permanent", action="append", default=[], help="code permanent (mode 0A)")
    ap.add_argument("--no-permanent", action="store_true", help="mode 0A non supporté (NO DATA)")
    ap.add_argument("--multi-ecu", action="store_true", help="un 2e calculateur (7E9) répond aux PID de support")
    ap.add_argument("--na", action="store_true", help="moteur atmosphérique (pas de suralimentation)")
    ap.add_argument("--lean-bias", type=float, default=0.0, help="%% de correction pauvre ajouté (prise d'air simulée)")
    ap.add_argument("--pids", default="", help="liste hex des PID mode 01 simulés (défaut : tous)")
    ap.add_argument("--no-vehicle", action="store_true", help="contact coupé : UNABLE TO CONNECT")
    ap.add_argument("--engine-off", action="store_true", help="contact mis moteur coupé (régime et vitesse à 0)")
    ap.add_argument("--vin", default="SIMULATEDVIN00001")
    args = ap.parse_args(argv)
    for c in args.dtc + args.pending + args.permanent:
        if len(c) != 5 or c[0] not in "PCBU":
            ap.error(f"code défaut invalide : {c}")
    if len(args.vin) != 17:
        ap.error("le VIN doit faire 17 caractères")
    return args


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        serve_pty(args) if args.serial_pty else serve_tcp(args)
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
