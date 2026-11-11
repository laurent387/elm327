"""Serveur MCP de télécommande de l'autoradio (Kitt), dans le même processus que le copilote.

    http://127.0.0.1:8789/mcp   (OVERMIND_COPILOT_MCP_PORT ; 0 = désactivé)
    Authorization: Bearer <jeton portée `remote`>
    python -m overmind_obd.tokens create claude-remote --scopes remote

N'écoute QUE sur 127.0.0.1 : jamais redirigé par la Freebox. Les commandes passent par la
connexion sortante que l'autoradio maintient déjà vers Overmind (remote.py).

Liste fermée d'outils ; aucun n'agit sur le véhicule (pas de commande OBD, pas d'effacement de
défauts, pas de shell sur l'autoradio). L'installation d'une mise à jour est confirmée sur
l'écran de l'autoradio par l'installeur Android.
"""

from __future__ import annotations

import asyncio
import base64
import logging
import time
from typing import TYPE_CHECKING, Any

from mcp.server.fastmcp import FastMCP, Image
from mcp.server.transport_security import TransportSecuritySettings
from mcp.types import ToolAnnotations

from .remote import MEDIA, RemoteError

if TYPE_CHECKING:
    from .server import CopilotServer

log = logging.getLogger("overmind_copilot.remote_mcp")

RO = ToolAnnotations(readOnlyHint=True, destructiveHint=False, idempotentHint=True, openWorldHint=False)
ACT = ToolAnnotations(readOnlyHint=False, destructiveHint=False, idempotentHint=False, openWorldHint=False)
INSTALL = ToolAnnotations(readOnlyHint=False, destructiveHint=True, idempotentHint=False, openWorldHint=False)

INSTRUCTIONS = (
    "Télécommande de l'autoradio Android (appli « Overmind Kitt ») de la voiture : état, journaux, "
    "capture d'écran, faire parler Kitt, musique, lancer une appli, redémarrer Kitt, pousser une mise à "
    "jour de l'APK. Aucun outil n'agit sur le véhicule (bus OBD en lecture seule). Le conducteur est "
    "peut-être en train de rouler : ne pas lancer d'appli ni faire parler Kitt sans raison, rester bref. "
    "device_id est facultatif quand un seul autoradio est connecté."
)


def _err(exc: Exception) -> dict[str, Any]:
    return {"error": str(exc)}


def build_remote_mcp(server: "CopilotServer", port: int = 8789) -> FastMCP:
    hub = server.remote
    hosts = [f"127.0.0.1:{port}", f"localhost:{port}"]
    security = TransportSecuritySettings(enable_dns_rebinding_protection=True, allowed_hosts=hosts,
                                         allowed_origins=[f"http://{h}" for h in hosts])
    mcp = FastMCP("Overmind Kitt — télécommande autoradio", instructions=INSTRUCTIONS, host="127.0.0.1",
                  port=port, transport_security=security)

    async def call(device_id: str | None, cmd: str, args: dict[str, Any] | None = None) -> dict[str, Any]:
        try:
            return await hub.command(device_id, cmd, args)
        except RemoteError as exc:
            return _err(exc)

    @mcp.tool(annotations=RO)
    def headunit_list() -> dict[str, Any]:
        """Autoradios actuellement connectés à Overmind (device_id, véhicule, adresse, version, télécommande autorisée)."""
        return {"connected": [link.info() for link in hub.links.values()]}

    @mcp.tool(annotations=RO)
    async def headunit_status(device_id: str | None = None) -> dict[str, Any]:
        """État de l'autoradio : appli (version, état de Kitt, liaison Overmind), passerelle OBD (ELM327, session, erreurs), réseau, audio (musique, volume, appel), mémoire, écran au premier plan."""
        return await call(device_id, "status")

    @mcp.tool(annotations=RO)
    async def headunit_display(device_id: str | None = None) -> dict[str, Any]:
        """Écran détecté par l'appli : résolution en px, densité (dpi), taille en dp, échelle de police, orientation."""
        return await call(device_id, "display")

    @mcp.tool(annotations=RO)
    async def headunit_logs(device_id: str | None = None, lines: int = 200, grep: str | None = None) -> dict[str, Any]:
        """Journal Android (logcat) de l'appli Kitt uniquement (pas des autres applis). `grep` filtre les lignes (sous-chaîne, insensible à la casse)."""
        return await call(device_id, "logs", {"lines": max(10, min(lines, 2000)), "grep": grep or ""})

    @mcp.tool(annotations=RO)
    async def headunit_screenshot(device_id: str | None = None) -> Any:
        """Capture de l'écran de l'appli Kitt (JPEG). Limite Android : seulement quand Kitt est au premier plan ; les autres applis ne sont pas capturables sans root."""
        data = await call(device_id, "screenshot")
        if "error" in data:
            return data
        try:
            return Image(data=base64.b64decode(data["jpeg_base64"]), format="jpeg")
        except (KeyError, ValueError) as exc:
            return _err(RemoteError(f"capture invalide : {exc}"))

    @mcp.tool(annotations=RO)
    async def headunit_list_apps(device_id: str | None = None) -> dict[str, Any]:
        """Applis lançables installées sur l'autoradio (nom, package), pour headunit_launch_app."""
        return await call(device_id, "list_apps")

    @mcp.tool(annotations=ACT)
    async def headunit_say(text: str, device_id: str | None = None) -> dict[str, Any]:
        """Fait dire `text` à Kitt sur l'autoradio (voix Piper du GE75, musique baissée). 300 caractères max, en français, phrase courte."""
        try:
            return await server.say(device_id, text)
        except RemoteError as exc:
            return _err(exc)

    @mcp.tool(annotations=ACT)
    async def headunit_media(command: str, device_id: str | None = None) -> dict[str, Any]:
        """Commande musique de l'autoradio : play, pause, toggle, next, previous, volume_up, volume_down."""
        if command not in MEDIA:
            return {"error": f"commande inconnue ; possibles : {', '.join(sorted(MEDIA))}"}
        return await call(device_id, "media", {"command": command})

    @mcp.tool(annotations=ACT)
    async def headunit_launch_app(package: str, device_id: str | None = None) -> dict[str, Any]:
        """Ouvre une appli de l'autoradio par son package (voir headunit_list_apps), ex. com.waze."""
        return await call(device_id, "launch_app", {"package": package.strip()[:200]})

    @mcp.tool(annotations=ACT)
    async def headunit_restart_kitt(device_id: str | None = None) -> dict[str, Any]:
        """Redémarre l'appli Kitt sur l'autoradio (micro, liaison Overmind, passerelle OBD). Elle se reconnecte seule en quelques secondes."""
        return await call(device_id, "restart")

    @mcp.tool(annotations=ACT)
    def headunit_pairing_code() -> dict[str, Any]:
        """Crée un code d'appairage à 6 chiffres, valable 10 min et une seule fois : à taper dans Réglages Kitt à la place du jeton. L'autoradio reçoit alors son propre jeton `copilot`. Annule le code précédent."""
        code, expires = server.pairing.new_code()
        return {"code": code, "expires": time.strftime("%H:%M:%S", time.localtime(expires)),
                "server_url_hint": "wss://<IP publique>:38097/copilot"}

    @mcp.tool(annotations=INSTALL)
    async def headunit_install_update(apk: str | None = None, device_id: str | None = None) -> dict[str, Any]:
        """Envoie un APK de la variante autoradio (par défaut le plus récent de android/app/build/outputs/apk/headunit) : l'autoradio le télécharge par morceaux dans la liaison chiffrée existante, vérifie l'empreinte SHA-256 et le package, puis l'installeur Android demande confirmation SUR L'ÉCRAN de l'autoradio. Même signature obligatoire."""
        try:
            hub.resolve(device_id)
            nonce, staged = hub.stage_update(apk)
        except RemoteError as exc:
            return _err(exc)
        res = await call(device_id, "update", {"nonce": nonce, "sha256": staged.sha256,
                                               "size": staged.size})
        if "error" not in res:
            res.setdefault("apk", staged.path.name)
            res.setdefault("sha256", staged.sha256)
        return res

    return mcp


def remote_token_configured(tokens) -> bool:
    return bool(getattr(tokens, "_env", None)) or any(
        "remote" in e.get("scopes", []) for e in getattr(tokens, "entries", {}).values())


async def serve_mcp(server: "CopilotServer", port: int) -> None:
    """Lance le MCP dans la boucle asyncio du copilote ; sans jeton `remote`, il reste éteint."""
    import uvicorn

    from overmind_obd.mcp_server import BearerMiddleware

    if not remote_token_configured(server.tokens):
        log.warning("télécommande MCP désactivée : aucun jeton de portée « remote » "
                    "(python -m overmind_obd.tokens create claude-remote --scopes remote)")
        await asyncio.Future()
    app = BearerMiddleware(build_remote_mcp(server, port).streamable_http_app(), server.tokens, scope="remote")
    log.info("télécommande MCP sur http://127.0.0.1:%d/mcp (jeton portée remote)", port)
    cfg = uvicorn.Config(app, host="127.0.0.1", port=port, log_level="warning", lifespan="on")
    try:
        await uvicorn.Server(cfg).serve()
    except (SystemExit, OSError) as exc:   # port occupé… : le copilote vocal continue sans télécommande
        log.error("télécommande MCP arrêtée (%s) ; le copilote continue", exc)
    await asyncio.Future()
