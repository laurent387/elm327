"""API HTTP + WebSocket temps réel Overmind OBD (lecture seule).

    python -m overmind_obd.api        # :8095, ingestion MQTT embarquée (OVERMIND_OBD_EMBED_INGEST=1)

Routes (Bearer requis) : /api/obd/v1/vehicles, /vehicles/{id}/latest|timeseries|dtc|sessions|status|
supported-pids|analysis|report, /sessions/{sid} ; WebSocket /ws/obd/live (1er message {"token": "..."}).
Page de visualisation : /obd/ (dashboard/obd-live.html). Santé sans auth : /api/obd/v1/health.
"""

from __future__ import annotations

import asyncio
import contextlib
import logging
import os
from pathlib import Path
from typing import Annotated, Any

from fastapi import APIRouter, Depends, FastAPI, Header, HTTPException, Query, WebSocket, WebSocketDisconnect, status
from fastapi.responses import FileResponse, PlainTextResponse, RedirectResponse

from .bus import EventBus
from .ingest import Ingestor, MqttIngestService, MqttSettings
from .queries import ObdQueries
from .registry import Registry
from .store import Store
from .tokens import TokenStore

log = logging.getLogger("overmind_obd.api")
DASHBOARD = Path(__file__).resolve().parent / "dashboard" / "obd-live.html"


def _bearer(authorization: str | None) -> str | None:
    if authorization and authorization.lower().startswith("bearer "):
        return authorization[7:].strip()
    return None


def build_router(queries: ObdQueries, tokens: TokenStore, bus: EventBus, ingestor: Ingestor | None,
                 allow_no_auth: bool = False) -> APIRouter:
    r = APIRouter()

    def auth(authorization: Annotated[str | None, Header()] = None) -> str:
        if not tokens.configured:
            if allow_no_auth:
                return "anonymous"
            raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, "aucun jeton configuré (python -m overmind_obd.tokens)")
        tid = tokens.check(_bearer(authorization), "api")
        if not tid:
            raise HTTPException(status.HTTP_401_UNAUTHORIZED, "jeton invalide", headers={"WWW-Authenticate": "Bearer"})
        return tid

    A = Depends(auth)

    def vehicle(vehicle_id: str) -> str:
        try:
            queries._check_vehicle(vehicle_id)
        except KeyError as exc:
            raise HTTPException(404, str(exc)) from exc
        return vehicle_id

    @r.get("/api/obd/v1/health")
    def health() -> dict[str, Any]:
        return {"ok": True, "ingest": ingestor.stats.as_dict() if ingestor else None,
                "messages_stored": queries.store.message_count()}

    @r.get("/api/obd/v1/vehicles", dependencies=[A])
    def vehicles() -> list[dict[str, Any]]:
        return queries.list_vehicles()

    @r.get("/api/obd/v1/vehicles/{vehicle_id}/status", dependencies=[A])
    def vstatus(vehicle_id: str) -> dict[str, Any]:
        return queries.connection_status(vehicle(vehicle_id))

    @r.get("/api/obd/v1/vehicles/{vehicle_id}/latest", dependencies=[A])
    def latest(vehicle_id: str) -> dict[str, Any]:
        return queries.live_data(vehicle(vehicle_id))

    @r.get("/api/obd/v1/vehicles/{vehicle_id}/supported-pids", dependencies=[A])
    def supported(vehicle_id: str) -> dict[str, Any]:
        return queries.supported_pids(vehicle(vehicle_id))

    @r.get("/api/obd/v1/vehicles/{vehicle_id}/timeseries", dependencies=[A])
    def timeseries(vehicle_id: str, key: str, start: str | None = None, end: str | None = None,
                   last_minutes: float | None = Query(None, gt=0), limit: int = Query(500, ge=1, le=20000),
                   session_id: str | None = None) -> dict[str, Any]:
        return queries.history(vehicle(vehicle_id), key, start, end, last_minutes, limit, session_id)

    @r.get("/api/obd/v1/vehicles/{vehicle_id}/dtc", dependencies=[A])
    def dtc(vehicle_id: str) -> dict[str, Any]:
        return queries.dtc(vehicle(vehicle_id))

    @r.get("/api/obd/v1/vehicles/{vehicle_id}/sessions", dependencies=[A])
    def sessions(vehicle_id: str, limit: int = Query(20, ge=1, le=500)) -> list[dict[str, Any]]:
        return queries.sessions(vehicle(vehicle_id), limit)

    @r.get("/api/obd/v1/sessions/{session_id}", dependencies=[A])
    def session(session_id: str) -> dict[str, Any]:
        s = queries.session(session_id)
        if not s:
            raise HTTPException(404, "session inconnue")
        return s

    @r.get("/api/obd/v1/vehicles/{vehicle_id}/analysis", dependencies=[A])
    def analysis(vehicle_id: str, window_minutes: float = Query(15, gt=0, le=1440)) -> dict[str, Any]:
        return queries.analyze(vehicle(vehicle_id), window_minutes)

    @r.get("/api/obd/v1/vehicles/{vehicle_id}/report", dependencies=[A], response_class=PlainTextResponse)
    def report(vehicle_id: str, window_minutes: float = Query(15, gt=0, le=1440), session_id: str | None = None) -> str:
        return queries.report(vehicle(vehicle_id), window_minutes, session_id)

    @r.get("/obd", include_in_schema=False)
    def dash_redirect() -> RedirectResponse:
        return RedirectResponse("/obd/")

    @r.get("/obd/", include_in_schema=False)
    def dashboard() -> FileResponse:
        return FileResponse(DASHBOARD, media_type="text/html")

    @r.websocket("/ws/obd/live")
    async def live(ws: WebSocket) -> None:
        await ws.accept()
        try:
            first = await asyncio.wait_for(ws.receive_json(), timeout=10)
        except (asyncio.TimeoutError, WebSocketDisconnect, ValueError):
            await ws.close(code=4401)
            return
        ok = (allow_no_auth and not tokens.configured) or tokens.check(str(first.get("token") or ""), "api")
        if not ok:
            await ws.send_json({"type": "error", "error": "unauthorized"})
            await ws.close(code=4401)
            return
        want = first.get("vehicle_id")
        loop = asyncio.get_running_loop()
        q = bus.subscribe_queue(loop)
        try:
            vids = [want] if want else [v["vehicle_id"] for v in queries.list_vehicles()]
            await ws.send_json({"type": "hello", "vehicles": queries.list_vehicles()})
            for vid in vids:
                await ws.send_json({"type": "snapshot", "vehicle_id": vid, "live": queries.live_data(vid),
                                    "status": queries.connection_status(vid)})
            recv = asyncio.ensure_future(ws.receive_text())
            while True:
                get = asyncio.ensure_future(q.get())
                done, _ = await asyncio.wait({get, recv}, return_when=asyncio.FIRST_COMPLETED)
                if recv in done:  # le client ne parle pas après l'auth : toute réception = fin ou ignorée
                    get.cancel()
                    recv.result()
                    recv = asyncio.ensure_future(ws.receive_text())
                    continue
                ev = get.result()
                if want and ev.get("vehicle_id") != want:
                    continue
                await ws.send_json(ev)
        except (WebSocketDisconnect, RuntimeError):
            pass
        finally:
            bus.unsubscribe_queue(q)
            with contextlib.suppress(Exception):
                recv.cancel()

    return r


def create_app(store: Store | None = None, registry: Registry | None = None, tokens: TokenStore | None = None,
               bus: EventBus | None = None, embed_ingest: bool | None = None,
               mqtt_settings: MqttSettings | None = None, allow_no_auth: bool | None = None) -> FastAPI:
    store = store or Store()
    registry = registry or Registry.load()
    tokens = tokens or TokenStore()
    bus = bus or EventBus()
    lost = float(os.environ.get("OVERMIND_OBD_LOST_AFTER_S", "30"))
    ingestor = Ingestor(store, registry, bus, lost_after_s=lost)
    if embed_ingest is None:
        embed_ingest = os.environ.get("OVERMIND_OBD_EMBED_INGEST", "1") in ("1", "true", "yes")
    if allow_no_auth is None:
        allow_no_auth = os.environ.get("OVERMIND_OBD_ALLOW_NO_AUTH", "0") in ("1", "true", "yes")
    svc = MqttIngestService(ingestor, mqtt_settings) if embed_ingest else None

    @contextlib.asynccontextmanager
    async def lifespan(app: FastAPI):
        if svc:
            svc.start()
        yield
        if svc:
            svc.stop()

    app = FastAPI(title="Overmind OBD", version="0.1.0", lifespan=lifespan)
    app.include_router(build_router(ObdQueries(store, registry, lost), tokens, bus, ingestor, allow_no_auth))
    app.state.store, app.state.registry, app.state.bus, app.state.ingestor, app.state.mqtt = store, registry, bus, ingestor, svc
    return app


def main() -> int:
    import uvicorn

    logging.basicConfig(level=os.environ.get("OVERMIND_OBD_LOG", "INFO"),
                        format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    uvicorn.run(create_app(), host=os.environ.get("OVERMIND_OBD_API_HOST", "127.0.0.1"),
                port=int(os.environ.get("OVERMIND_OBD_API_PORT", "8095")), ws_ping_interval=20)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
