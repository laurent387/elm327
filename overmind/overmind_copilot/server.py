"""Serveur WebSocket du copilote (schemas/COPILOT_PROTOCOL.md).

    python -m overmind_copilot.server

En clair uniquement sur 127.0.0.1 ; sur les autres adresses de OVERMIND_COPILOT_HOSTS (ex.
192.168.1.35, cible d'une redirection Freebox) TLS est obligatoire, avec le certificat de la CA
privée Overmind (celle de Mosquitto). Jamais d'écoute 0.0.0.0.
Jeton obligatoire, portée `copilot` (python -m overmind_obd.tokens create autoradio --scopes copilot).
Même processus : télécommande MCP sur http://127.0.0.1:8789/mcp (remote_mcp.py, jeton portée `remote`).
"""

from __future__ import annotations

import asyncio
import contextlib
import json
import logging
import os
import re
import ssl
import time
from typing import Any

from websockets.asyncio.server import ServerConnection, serve
from websockets.exceptions import ConnectionClosed

from overmind_obd.queries import ObdQueries
from overmind_obd.registry import Registry
from overmind_obd.store import Store
from overmind_obd.tokens import TokenStore

from . import __version__
from .brain import (CopilotBrain, Session, check_wake, make_mechanic, orchestrator_backend_chooser,
                    orchestrator_model_caller)
from .pairing import Pairing
from .remote import Link, RemoteHub
from .tools import VehicleTools
from .voice import SAMPLE_RATE, SpeechToText, TextToSpeech

log = logging.getLogger("overmind_copilot")

MAX_PCM_BYTES = 15 * SAMPLE_RATE * 2   # 15 s d'audio par énoncé
HELLO_TIMEOUT_S = 10
AUTH_FAIL_DELAY_S = 2.0
ID_MAX = 64


class CopilotServer:
    def __init__(self, brain: CopilotBrain, tokens: TokenStore, registry: Registry,
                 stt: SpeechToText | None, tts: TextToSpeech | None, remote: RemoteHub | None = None):
        self.brain = brain
        self.tokens = tokens
        self.registry = registry
        self.stt = stt
        self.tts = tts
        self.sessions: dict[str, Session] = {}   # par device_id : survit à une reconnexion
        self.remote = remote or RemoteHub()       # télécommande (remote_mcp.py)
        self.pairing = Pairing()                  # codes d'appairage à 6 chiffres (pairing.py)

    async def handler(self, ws: ServerConnection) -> None:
        peer = ws.remote_address
        try:
            hello = json.loads(await asyncio.wait_for(ws.recv(), HELLO_TIMEOUT_S))
        except (asyncio.TimeoutError, json.JSONDecodeError, ConnectionClosed, TypeError):
            await ws.close(1008, "hello attendu")
            return
        if not isinstance(hello, dict) or hello.get("type") != "hello":
            await ws.close(1008, "hello attendu")
            return
        vehicle_id = str(hello.get("vehicle_id") or "")
        device_id = str(hello.get("device_id") or "headunit")[:ID_MAX]
        new_token = None
        if hello.get("pairing_code") and not hello.get("token"):
            if not self.pairing.consume(str(hello.get("pairing_code"))):
                log.warning("appairage refusé depuis %s (code faux ou expiré, %d échec(s))", peer, self.pairing.failures)
                await asyncio.sleep(AUTH_FAIL_DELAY_S)
                await ws.close(1008, "code d'appairage invalide ou expiré")
                return
            new_token = self._issue_token(device_id)
            log.info("autoradio %s appairé depuis %s : nouveau jeton copilot", device_id, peer)
        elif not self.tokens.check(hello.get("token"), "copilot"):
            log.warning("connexion refusée depuis %s (jeton absent ou invalide)", peer)
            await asyncio.sleep(AUTH_FAIL_DELAY_S)   # ralentit les essais de jetons depuis Internet
            await ws.close(1008, "jeton invalide")
            return
        if self.registry.vehicles and vehicle_id not in self.registry.vehicles:
            await ws.close(1008, "véhicule inconnu")
            return
        if new_token:
            # envoyé une seule fois, dans la liaison chiffrée ; l'appli le range dans le Keystore
            await ws.send(json.dumps({"type": "paired", "token": new_token}))
        session = self.sessions.setdefault(device_id, Session(vehicle_id))
        session.vehicle_id = vehicle_id
        log.info("autoradio %s connecté depuis %s (véhicule %s)", device_id, peer, vehicle_id)
        asyncio.get_running_loop().run_in_executor(None, self._warmup)
        await ws.send(json.dumps({"type": "welcome", "assistant": self.brain.name, "sample_rate": SAMPLE_RATE,
                                  "server_version": __version__, "stt": self.stt is not None, "tts": self.tts is not None}))

        link = Link(device_id, vehicle_id, f"{peer[0]}:{peer[1]}" if isinstance(peer, tuple) else str(peer),
                    str(hello.get("app_version") or "")[:32], bool(hello.get("remote")), ws)
        self.remote.attach(link)

        current: dict[str, Any] | None = None
        try:
            async for msg in ws:
                if isinstance(msg, bytes):
                    if current is not None and len(current["pcm"]) + len(msg) <= MAX_PCM_BYTES:
                        current["pcm"] += msg
                    continue
                try:
                    m = json.loads(msg)
                except json.JSONDecodeError:
                    continue
                t = m.get("type")
                uid = str(m.get("id") or "")[:ID_MAX]
                if t == "ping":
                    await ws.send('{"type":"pong"}')
                elif t == "utterance_start":
                    current = {"id": uid, "pcm": bytearray(), "context": m.get("context") or {},
                               "rate": int(m.get("sample_rate") or SAMPLE_RATE), "wake": bool(m.get("wake"))}
                elif t == "utterance_end" and current is not None and current["id"] == uid:
                    u, current = current, None
                    await self._process(link, session, uid, None, bytes(u["pcm"]), u["rate"], u["context"], u["wake"])
                elif t == "text":
                    await self._process(link, session, uid, str(m.get("text") or "")[:500], None, SAMPLE_RATE,
                                        m.get("context") or {})
                elif t == "update_get":
                    await self._send_update_chunk(link, m)
                elif t == "remote_result":
                    self.remote.on_result(link, m)
                elif t == "action_result":
                    log.info("action %s : ok=%s %s", uid, m.get("ok"), str(m.get("detail") or "")[:200])
        except ConnectionClosed:
            pass
        finally:
            self.remote.detach(link)
        log.info("autoradio %s déconnecté", device_id)

    def _warmup(self) -> None:
        try:
            self.brain.warmup()
        except Exception as exc:  # jamais bloquant
            log.warning("préchauffage du modèle impossible : %s", exc)

    async def _process(self, link: Link, session: Session, uid: str, text: str | None,
                       pcm: bytes | None, rate: int, context: dict[str, Any], wake: bool = False) -> None:
        ws = link.ws
        t0 = time.monotonic()
        timings = {"stt_ms": 0, "llm_ms": 0, "tts_ms": 0}
        try:
            if text is None:
                if self.stt is None:
                    await ws.send(json.dumps({"type": "error", "id": uid, "code": "stt_unavailable",
                                              "message": "reconnaissance vocale indisponible"}))
                    return
                ts = time.monotonic()
                text = await asyncio.to_thread(self.stt.transcribe, pcm or b"", rate)
                timings["stt_ms"] = int((time.monotonic() - ts) * 1000)
            await ws.send(json.dumps({"type": "transcript", "id": uid, "text": text}, ensure_ascii=False))
            if wake:
                ok, text = check_wake(text)
                if not ok:
                    # Faux réveil (« quitte l'autoroute »…) : on se tait.
                    log.info("[%s] faux réveil ignoré : « %s »", uid, text)
                    await ws.send(json.dumps({"type": "ignored", "id": uid, "reason": "wake_not_confirmed"}))
                    timings["total_ms"] = int((time.monotonic() - t0) * 1000)
                    await ws.send(json.dumps({"type": "done", "id": uid, "timings": timings}))
                    return
                if not text:
                    text = "oui ?"   # « Kitt » seul : le conducteur attend une réponse

            tl = time.monotonic()
            reply = await asyncio.to_thread(self.brain.handle, session, text, context)
            timings["llm_ms"] = int((time.monotonic() - tl) * 1000)
            await ws.send(json.dumps({"type": "reply", "id": uid, "text": reply.text, "intent": reply.intent,
                                      "action": reply.action, "model": reply.model, "tools": reply.tools_used},
                                     ensure_ascii=False))
            log.info("[%s] « %s » → %s (%s, outils %s) « %s »", uid, text, reply.intent, reply.model,
                     reply.tools_used, reply.text)

            if self.tts is not None:
                tt = time.monotonic()
                wav = await asyncio.to_thread(self.tts.wav, reply.text)
                timings["tts_ms"] = int((time.monotonic() - tt) * 1000)
                await self._send_audio(link, uid, wav)
        except ConnectionClosed:
            raise
        except Exception as exc:
            log.exception("échec du traitement %s", uid)
            await ws.send(json.dumps({"type": "error", "id": uid, "code": "server_error", "message": str(exc)[:200]},
                                     ensure_ascii=False))
        timings["total_ms"] = int((time.monotonic() - t0) * 1000)
        await ws.send(json.dumps({"type": "done", "id": uid, "timings": timings}))


    def _issue_token(self, device_id: str) -> str:
        """Nouveau jeton `copilot` ; relit le fichier avant d'écrire (jetons créés depuis le démarrage)."""
        fresh = TokenStore(self.tokens.path, env_tokens="")
        tid = f"autoradio-{re.sub(r'[^A-Za-z0-9_-]', '_', device_id)}-{time.strftime('%Y%m%d-%H%M%S')}"
        token = fresh.create(tid, ["copilot"])
        self.tokens.entries = fresh.entries
        return token

    async def _send_audio(self, link: Link, uid: str, wav: bytes) -> None:
        async with link.send_lock:
            await link.ws.send(json.dumps({"type": "audio", "id": uid, "format": "wav", "bytes": len(wav)}))
            await link.ws.send(wav)

    async def say(self, device_id: str | None, text: str) -> dict[str, Any]:
        """Télécommande : Kitt prononce `text` (voix Piper du GE75) sur l'autoradio."""
        from .remote import RemoteError
        link = self.remote.resolve(device_id)
        if not link.remote_enabled:
            raise RemoteError("télécommande désactivée dans les réglages Kitt de l'autoradio")
        text = " ".join(str(text).split())[:300]
        if not text:
            raise RemoteError("texte vide")
        uid = "s" + os.urandom(3).hex()
        async with link.send_lock:
            await link.ws.send(json.dumps({"type": "reply", "id": uid, "text": text, "intent": "remote_say",
                                           "action": None, "model": "remote", "tools": []}, ensure_ascii=False))
        audio = 0
        if self.tts is not None:
            wav = await asyncio.to_thread(self.tts.wav, text)
            await self._send_audio(link, uid, wav)
            audio = len(wav)
        log.info("[%s] télécommande : Kitt dit « %s »", uid, text)
        return {"device_id": link.device_id, "text": text, "audio_bytes": audio}

    async def _send_update_chunk(self, link: Link, m: dict[str, Any]) -> None:
        """Mise à jour tirée par l'autoradio : un morceau de l'APK préparé par nonce, puis la trame binaire."""
        nonce = str(m.get("id") or "")[:ID_MAX]
        try:
            offset = int(m.get("offset") or 0)
        except (TypeError, ValueError):
            offset = -1
        chunk = self.remote.read_chunk(nonce, offset)
        async with link.send_lock:
            if chunk is None:
                await link.ws.send(json.dumps({"type": "update_data", "id": nonce,
                                               "error": "mise à jour inconnue, expirée ou position invalide"},
                                              ensure_ascii=False))
                return
            data, eof = chunk
            await link.ws.send(json.dumps({"type": "update_data", "id": nonce, "offset": offset,
                                           "bytes": len(data), "eof": eof}))
            await link.ws.send(data)
        if eof:
            log.info("mise à jour transmise à %s", link.device_id)

    def process_request(self, connection, request):
        if request.path != "/copilot":
            return connection.respond(404, "not found\n")
        return None


def build_default(load_voice: bool = True) -> CopilotServer:
    store, registry = Store(), Registry.load()
    queries = ObdQueries(store, registry)
    caller, chooser = orchestrator_model_caller(), orchestrator_backend_chooser()
    tools = VehicleTools(queries, make_mechanic(queries, caller, chooser))
    brain = CopilotBrain(tools, os.environ.get("OVERMIND_COPILOT_NAME", "Kitt"), caller, chooser)
    stt = SpeechToText() if load_voice else None
    tts = TextToSpeech() if load_voice else None
    return CopilotServer(brain, TokenStore(), registry, stt, tts)


LOCAL_HOSTS = {"127.0.0.1", "::1", "localhost"}


def tls_context(cert: str, key: str) -> ssl.SSLContext:
    ctx = ssl.create_default_context(ssl.Purpose.CLIENT_AUTH)
    ctx.minimum_version = ssl.TLSVersion.TLSv1_2
    ctx.load_cert_chain(cert, key)
    return ctx


async def run(server: CopilotServer, hosts: list[str], port: int, tls: ssl.SSLContext | None = None,
              mcp_port: int = 0) -> None:
    """En clair sur localhost uniquement ; toute autre adresse exige TLS (accès Internet via la Freebox)."""
    local = [h for h in hosts if h in LOCAL_HOSTS]
    remote = [h for h in hosts if h not in LOCAL_HOSTS]
    if remote and tls is None:
        raise SystemExit("refus : écoute réseau sans TLS (OVERMIND_COPILOT_TLS_CERT / _KEY)")
    opts = dict(max_size=MAX_PCM_BYTES + 65536, ping_interval=20, process_request=server.process_request)
    async with contextlib.AsyncExitStack() as stack:
        if local:
            await stack.enter_async_context(serve(server.handler, local, port, **opts))
            log.info("copilote à l'écoute sur ws://%s:%d/copilot", ",".join(local), port)
        if remote:
            await stack.enter_async_context(serve(server.handler, remote, port, ssl=tls, **opts))
            log.info("copilote à l'écoute sur wss://%s:%d/copilot (TLS)", ",".join(remote), port)
        if mcp_port:
            from .remote_mcp import serve_mcp
            await serve_mcp(server, mcp_port)   # 127.0.0.1 uniquement, jeton portée `remote`
        else:
            await asyncio.Future()


def main() -> int:
    logging.basicConfig(level=os.environ.get("OVERMIND_COPILOT_LOG", "INFO"),
                        format="%(asctime)s %(levelname)s %(name)s %(message)s")
    hosts = ["127.0.0.1"] + [h for h in os.environ.get("OVERMIND_COPILOT_HOSTS", "").split(",") if h.strip()]
    if any(h.strip() in ("0.0.0.0", "::") for h in hosts):
        raise SystemExit("refus : le copilote ne doit pas écouter sur toutes les interfaces")
    cert, key = os.environ.get("OVERMIND_COPILOT_TLS_CERT"), os.environ.get("OVERMIND_COPILOT_TLS_KEY")
    tls = tls_context(cert, key) if cert and key else None
    server = build_default()
    if not server.tokens.configured:
        raise SystemExit("aucun jeton : python -m overmind_obd.tokens create autoradio --scopes copilot")
    mcp_port = int(os.environ.get("OVERMIND_COPILOT_MCP_PORT", "8789"))
    asyncio.run(run(server, [h.strip() for h in hosts], int(os.environ.get("OVERMIND_COPILOT_PORT", "8097")), tls,
                    mcp_port))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
