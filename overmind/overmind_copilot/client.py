"""Client de test du copilote : envoie un WAV (ou un texte) et enregistre la réponse audio.

    python -m overmind_copilot.client --text "quel est le régime ?"
    python -m overmind_copilot.client --wav question.wav --out reponse.wav
    python -m overmind_copilot.client --url wss://88.175.164.245:38097/copilot --text "…"   # via la Freebox
Jeton : variable OVERMIND_COPILOT_TOKEN (jamais en argument de ligne de commande).
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import ssl
import time
import wave

import numpy as np
from websockets.asyncio.client import connect


def wav_to_pcm16k(path: str) -> bytes:
    with wave.open(path, "rb") as r:
        raw, sr, ch = r.readframes(r.getnframes()), r.getframerate(), r.getnchannels()
    a = np.frombuffer(raw, dtype=np.int16).astype(np.float32)
    if ch > 1:
        a = a.reshape(-1, ch).mean(axis=1)
    if sr != 16000:
        n = int(len(a) * 16000 / sr)
        a = np.interp(np.linspace(0, len(a) - 1, n), np.arange(len(a)), a)
    return a.astype(np.int16).tobytes()


async def ask(url: str, token: str, vehicle: str, text: str | None, wav: str | None, out: str | None,
              cafile: str | None = None) -> dict:
    result: dict = {}
    tls = ssl.create_default_context(cafile=cafile) if url.startswith("wss://") else None
    async with connect(url, max_size=8_000_000, ssl=tls) as ws:
        await ws.send(json.dumps({"type": "hello", "token": token, "device_id": "cli", "vehicle_id": vehicle,
                                  "app_version": "cli"}))
        result["welcome"] = json.loads(await ws.recv())
        t0 = time.monotonic()
        ctx = {"time": time.strftime("%A %d %B %Y, %H:%M")}
        if wav:
            await ws.send(json.dumps({"type": "utterance_start", "id": "u1", "sample_rate": 16000, "context": ctx}))
            pcm = wav_to_pcm16k(wav)
            for i in range(0, len(pcm), 6400):
                await ws.send(pcm[i:i + 6400])
            await ws.send(json.dumps({"type": "utterance_end", "id": "u1"}))
        else:
            await ws.send(json.dumps({"type": "text", "id": "u1", "text": text, "context": ctx}))
        while True:
            m = await ws.recv()
            if isinstance(m, bytes):
                if out:
                    with open(out, "wb") as f:
                        f.write(m)
                result["audio_bytes"] = len(m)
                continue
            d = json.loads(m)
            result[d["type"]] = d
            if d["type"] == "done":
                break
        result["wall_ms"] = int((time.monotonic() - t0) * 1000)
    return result


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default="ws://127.0.0.1:8097/copilot")
    ap.add_argument("--vehicle", default="slk_r171")
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--text")
    g.add_argument("--wav")
    ap.add_argument("--out")
    ap.add_argument("--cafile", default=os.path.expanduser("~/.config/overmind/ca/ca.crt"),
                    help="CA privée Overmind (wss://)")
    a = ap.parse_args()
    token = os.environ.get("OVERMIND_COPILOT_TOKEN", "")
    r = asyncio.run(ask(a.url, token, a.vehicle, a.text, a.wav, a.out, a.cafile))
    print(json.dumps(r, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
