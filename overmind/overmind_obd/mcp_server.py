"""Serveur MCP Overmind OBD — outils en LECTURE SEULE pour les agents (et ChatGPT si connecteur configuré).

    python -m overmind_obd.mcp_server                       # streamable-http 127.0.0.1:8796/mcp
    OVERMIND_OBD_MCP_TRANSPORT=stdio python -m overmind_obd.mcp_server

Aucun outil n'écrit vers le véhicule : pas d'effacement de défauts, pas de commande OBD, pas
d'actionneur. Le serveur ne lit que la base d'historique alimentée par l'ingestion.
Auth : si des jetons de scope `mcp` existent (python -m overmind_obd.tokens create agent --scopes mcp)
ou si OVERMIND_OBD_TOKENS est défini, l'en-tête `Authorization: Bearer <jeton>` est exigé.
"""

from __future__ import annotations

import json
import logging
import os
from typing import Any

from mcp.server.fastmcp import FastMCP
from mcp.server.transport_security import TransportSecuritySettings
from mcp.types import ToolAnnotations

from .queries import ObdQueries
from .registry import Registry
from .store import Store
from .tokens import TokenStore

log = logging.getLogger("overmind_obd.mcp")
RO = ToolAnnotations(readOnlyHint=True, destructiveHint=False, idempotentHint=True, openWorldHint=False)

INSTRUCTIONS = (
    "Outils Overmind OBD en lecture seule : télémétrie OBD2 de véhicules remontée par une passerelle Android "
    "ELM327. Aucun outil ne peut agir sur le véhicule. Distinguer faits mesurés, codes confirmés et hypothèses ; "
    "toujours citer les limites (PID absents, emplacement du capteur MAP, données périmées)."
)


def _err(exc: Exception) -> dict[str, Any]:
    return {"error": str(exc)}


def build_mcp(queries: ObdQueries, host: str = "127.0.0.1", port: int = 8796,
              allowed_hosts: list[str] | None = None) -> FastMCP:
    security = TransportSecuritySettings(
        enable_dns_rebinding_protection=bool(allowed_hosts),
        allowed_hosts=allowed_hosts or [], allowed_origins=[f"http://{h}" for h in (allowed_hosts or [])])
    mcp = FastMCP("Overmind OBD", instructions=INSTRUCTIONS, host=host, port=port, transport_security=security)

    @mcp.tool(annotations=RO)
    def obd_list_vehicles() -> list[dict[str, Any]]:
        """Liste les véhicules connus (profil moteur, état de connexion de la passerelle, dernier message)."""
        return queries.list_vehicles()

    @mcp.tool(annotations=RO)
    def obd_get_connection_status(vehicle_id: str) -> dict[str, Any]:
        """État de la passerelle (online/offline/lost), de l'ELM327 (version, protocole, tension) et santé du téléphone."""
        try:
            queries._check_vehicle(vehicle_id)
            return queries.connection_status(vehicle_id)
        except KeyError as exc:
            return _err(exc)

    @mcp.tool(annotations=RO)
    def obd_get_supported_pids(vehicle_id: str) -> dict[str, Any]:
        """PID OBD2 (mode 01) réellement annoncés par le calculateur et PID effectivement interrogés."""
        try:
            queries._check_vehicle(vehicle_id)
            return queries.supported_pids(vehicle_id)
        except KeyError as exc:
            return _err(exc)

    @mcp.tool(annotations=RO)
    def obd_get_live_data(vehicle_id: str, keys: list[str] | None = None) -> dict[str, Any]:
        """Dernières valeurs mesurées (valeur, unité, horodatage, âge, statut, source). `stale` = donnée périmée."""
        try:
            queries._check_vehicle(vehicle_id)
            return queries.live_data(vehicle_id, keys)
        except KeyError as exc:
            return _err(exc)

    @mcp.tool(annotations=RO)
    def obd_get_dtc(vehicle_id: str) -> dict[str, Any]:
        """Derniers codes défauts lus (confirmés, en attente, permanents) avec description FR et causes possibles."""
        try:
            queries._check_vehicle(vehicle_id)
            return queries.dtc(vehicle_id)
        except KeyError as exc:
            return _err(exc)

    @mcp.tool(annotations=RO)
    def obd_get_history(vehicle_id: str, key: str, last_minutes: float = 15, start: str | None = None,
                        end: str | None = None, limit: int = 300, session_id: str | None = None) -> dict[str, Any]:
        """Série temporelle d'une mesure (ex. key='rpm', 'map_kpa_absolute'). Sous-échantillonnée (moyenne/min/max) au-delà de `limit` points."""
        try:
            queries._check_vehicle(vehicle_id)
            return queries.history(vehicle_id, key, start, end, None if start else last_minutes,
                                   max(1, min(limit, 5000)), session_id)
        except KeyError as exc:
            return _err(exc)

    @mcp.tool(annotations=RO)
    def obd_get_session(session_id: str | None = None, vehicle_id: str | None = None, limit: int = 10) -> dict[str, Any]:
        """Détail d'une session {"session": …} (statistiques min/moy/max par mesure, DTC lus) ; sans session_id : {"sessions": [...]} (dernières sessions)."""
        if session_id:
            s = queries.session(session_id)
            return {"session": s} if s else {"error": "session inconnue"}
        return {"sessions": queries.sessions(vehicle_id, max(1, min(limit, 100)))}

    @mcp.tool(annotations=RO)
    def obd_analyze_engine(vehicle_id: str, window_minutes: float = 15) -> dict[str, Any]:
        """Analyse à règles (richesse, MAF, dépression ralenti, suralimentation, thermostat, tension, DTC) : constats avec nature (observation/confirmed/hypothesis), confiance et limites."""
        try:
            queries._check_vehicle(vehicle_id)
            return queries.analyze(vehicle_id, max(1, min(window_minutes, 1440)))
        except KeyError as exc:
            return _err(exc)

    @mcp.tool(annotations=RO)
    def obd_generate_report(vehicle_id: str, window_minutes: float = 15, session_id: str | None = None) -> str:
        """Rapport de diagnostic Markdown en français (véhicule, DTC, mesures, constats, contrôles, limites)."""
        try:
            queries._check_vehicle(vehicle_id)
            return queries.report(vehicle_id, max(1, min(window_minutes, 1440)), session_id)
        except KeyError as exc:
            return json.dumps(_err(exc), ensure_ascii=False)

    return mcp


class BearerMiddleware:
    """Exige `Authorization: Bearer <jeton mcp>` sur toutes les requêtes HTTP."""

    def __init__(self, app, tokens: TokenStore):
        self.app, self.tokens = app, tokens

    async def __call__(self, scope, receive, send):
        if scope["type"] == "http":
            headers = dict(scope.get("headers") or [])
            raw = headers.get(b"authorization", b"").decode("latin-1")
            token = raw[7:].strip() if raw.lower().startswith("bearer ") else None
            if not self.tokens.check(token, "mcp"):
                await send({"type": "http.response.start", "status": 401,
                            "headers": [(b"content-type", b"application/json"), (b"www-authenticate", b"Bearer")]})
                await send({"type": "http.response.body", "body": b'{"error":"unauthorized"}'})
                return
        await self.app(scope, receive, send)


def main() -> int:
    logging.basicConfig(level=os.environ.get("OVERMIND_OBD_LOG", "INFO"))
    host = os.environ.get("OVERMIND_OBD_MCP_HOST", "127.0.0.1")
    port = int(os.environ.get("OVERMIND_OBD_MCP_PORT", "8796"))
    allowed = [h for h in os.environ.get("OVERMIND_OBD_MCP_ALLOWED_HOSTS", "").split(",") if h]
    queries = ObdQueries(Store(), Registry.load())
    mcp = build_mcp(queries, host, port, allowed)
    if os.environ.get("OVERMIND_OBD_MCP_TRANSPORT", "streamable-http") == "stdio":
        mcp.run("stdio")
        return 0
    import uvicorn

    tokens = TokenStore()
    app = mcp.streamable_http_app()
    if tokens.configured:
        app = BearerMiddleware(app, tokens)
    elif host not in ("127.0.0.1", "localhost", "::1"):
        log.error("refus de démarrer sur %s sans jeton MCP (python -m overmind_obd.tokens create … --scopes mcp)", host)
        return 2
    else:
        log.warning("MCP sans authentification, limité à %s", host)
    uvicorn.run(app, host=host, port=port)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
