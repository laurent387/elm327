"""Télécommande de l'autoradio (schemas/COPILOT_PROTOCOL.md, section « Télécommande »).

L'autoradio garde sa connexion SORTANTE vers Overmind ; les commandes passent par cette même
WebSocket. Rien n'est ouvert sur l'autoradio, et le serveur MCP qui expose ces commandes
n'écoute que sur 127.0.0.1 (remote_mcp.py).

Liste FERMÉE de commandes, vérifiée ici ET sur l'autoradio. Aucune ne touche le véhicule : pas
de commande OBD, pas d'écriture sur le bus, pas de shell.
"""

from __future__ import annotations

import asyncio
import hashlib
import json
import os
import secrets
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

# commande → délai de réponse (s)
COMMANDS: dict[str, float] = {
    "status": 10, "display": 10, "logs": 15, "screenshot": 20, "list_apps": 15,
    "media": 10, "launch_app": 10, "restart": 10, "update": 30,
}
READ_ONLY = {"status", "display", "logs", "screenshot", "list_apps"}
MEDIA = {"play", "pause", "toggle", "next", "previous", "volume_up", "volume_down"}
UPDATE_TTL_S = 30 * 60
UPDATE_CHUNK = 512 * 1024
MAX_APK_BYTES = 200 * 1024 * 1024


def default_update_dir() -> Path:
    """Seuls les APK de ce dossier peuvent être poussés (sortie Gradle de la variante autoradio)."""
    repo = Path(__file__).resolve().parents[2]
    return Path(os.environ.get("OVERMIND_COPILOT_UPDATE_DIR",
                               repo / "android/app/build/outputs/apk/headunit"))


class RemoteError(Exception):
    pass


@dataclass
class Link:
    """Une connexion autoradio en cours."""
    device_id: str
    vehicle_id: str
    peer: str
    app_version: str
    remote_enabled: bool
    ws: Any
    connected_at: float = field(default_factory=time.time)
    pending: dict[str, asyncio.Future] = field(default_factory=dict)
    # une annonce `audio` et sa trame binaire ne doivent jamais être séparées par un autre envoi
    send_lock: asyncio.Lock = field(default_factory=asyncio.Lock)

    def info(self) -> dict[str, Any]:
        return {"device_id": self.device_id, "vehicle_id": self.vehicle_id, "peer": self.peer,
                "app_version": self.app_version, "remote_enabled": self.remote_enabled,
                "connected_since": time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(self.connected_at))}


@dataclass
class StagedUpdate:
    path: Path
    sha256: str
    size: int
    expires: float


class RemoteHub:
    def __init__(self, update_dir: Path | None = None):
        self.links: dict[str, Link] = {}
        self.update_dir = (update_dir or default_update_dir()).resolve()
        self.updates: dict[str, StagedUpdate] = {}

    # --- connexions -----------------------------------------------------------------------------
    def attach(self, link: Link) -> None:
        old = self.links.get(link.device_id)
        if old is not None:
            self._fail_pending(old, "autoradio reconnecté")
        self.links[link.device_id] = link

    def detach(self, link: Link) -> None:
        if self.links.get(link.device_id) is link:
            del self.links[link.device_id]
        self._fail_pending(link, "autoradio déconnecté")

    @staticmethod
    def _fail_pending(link: Link, why: str) -> None:
        for fut in link.pending.values():
            if not fut.done():
                fut.set_exception(RemoteError(why))
        link.pending.clear()

    def resolve(self, device_id: str | None) -> Link:
        if device_id:
            link = self.links.get(device_id)
            if link is None:
                raise RemoteError(f"autoradio « {device_id} » non connecté")
            return link
        if not self.links:
            raise RemoteError("aucun autoradio connecté (Kitt hors ligne ou non configuré)")
        if len(self.links) > 1:
            raise RemoteError("plusieurs autoradios connectés : préciser device_id " + ", ".join(self.links))
        return next(iter(self.links.values()))

    def on_result(self, link: Link, msg: dict[str, Any]) -> None:
        fut = link.pending.pop(str(msg.get("id") or ""), None)
        if fut is not None and not fut.done():
            fut.set_result(msg)

    # --- commandes ------------------------------------------------------------------------------
    async def command(self, device_id: str | None, cmd: str, args: dict[str, Any] | None = None) -> dict[str, Any]:
        if cmd not in COMMANDS:
            raise RemoteError(f"commande inconnue : {cmd}")
        link = self.resolve(device_id)
        if not link.remote_enabled:
            raise RemoteError("télécommande désactivée dans les réglages Kitt de l'autoradio")
        rid = "r" + secrets.token_hex(4)
        fut = asyncio.get_running_loop().create_future()
        link.pending[rid] = fut
        try:
            async with link.send_lock:
                await link.ws.send(json.dumps({"type": "remote", "id": rid, "cmd": cmd, "args": args or {}},
                                              ensure_ascii=False))
            msg = await asyncio.wait_for(fut, COMMANDS[cmd])
        except asyncio.TimeoutError:
            raise RemoteError(f"pas de réponse de l'autoradio en {COMMANDS[cmd]:.0f} s") from None
        finally:
            link.pending.pop(rid, None)
        if not msg.get("ok"):
            raise RemoteError(str(msg.get("error") or "échec sur l'autoradio")[:300])
        data = msg.get("data")
        return data if isinstance(data, dict) else {"result": data}

    # --- mise à jour de l'APK -------------------------------------------------------------------
    def stage_update(self, apk: str | None = None) -> tuple[str, StagedUpdate]:
        """Prépare un APK du dossier autorisé ; renvoie un nonce que l'autoradio utilise pour en demander les morceaux."""
        if apk:
            path = Path(apk).expanduser().resolve()
        else:
            found = sorted(self.update_dir.glob("**/*.apk"), key=lambda p: p.stat().st_mtime, reverse=True)
            if not found:
                raise RemoteError(f"aucun APK dans {self.update_dir} (./gradlew assembleHeadunitDebug)")
            path = found[0].resolve()
        if path.suffix != ".apk" or not path.is_file():
            raise RemoteError(f"pas un fichier APK : {path}")
        if self.update_dir not in path.parents:
            raise RemoteError(f"APK refusé : seuls ceux de {self.update_dir} peuvent être envoyés")
        size = path.stat().st_size
        if size > MAX_APK_BYTES:
            raise RemoteError("APK trop gros")
        sha = hashlib.sha256(path.read_bytes()).hexdigest()
        now = time.time()
        self.updates = {k: v for k, v in self.updates.items() if v.expires > now}
        nonce = secrets.token_urlsafe(24)
        staged = StagedUpdate(path, sha, size, now + UPDATE_TTL_S)
        self.updates[nonce] = staged
        return nonce, staged

    def read_chunk(self, nonce: str, offset: int) -> tuple[bytes, bool] | None:
        """Morceau de l'APK préparé à partir de `offset` ; None si nonce inconnu/expiré ou offset invalide."""
        staged = self.updates.get(nonce)
        if staged is None or staged.expires < time.time() or not 0 <= offset <= staged.size:
            return None
        with staged.path.open("rb") as f:
            f.seek(offset)
            data = f.read(UPDATE_CHUNK)
        eof = offset + len(data) >= staged.size
        if eof:
            self.updates.pop(nonce, None)   # transfert terminé : le nonce ne sert plus
        return data, eof
