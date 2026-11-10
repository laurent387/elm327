import json

import pytest
from fastapi.testclient import TestClient

from overmind_obd.api import create_app
from overmind_obd.tokens import TokenStore

TOKEN = "test-token-123"


@pytest.fixture
def client(store, registry, tmp_path):
    tokens = TokenStore(tmp_path / "tokens.json", env_tokens=TOKEN)
    app = create_app(store, registry, tokens, embed_ingest=False)
    with TestClient(app) as c:
        c.app_ref = app
        yield c


H = {"Authorization": f"Bearer {TOKEN}"}


def test_auth_required(client):
    assert client.get("/api/obd/v1/vehicles").status_code == 401
    assert client.get("/api/obd/v1/vehicles", headers={"Authorization": "Bearer nope"}).status_code == 401
    assert client.get("/api/obd/v1/health").status_code == 200


def test_no_token_configured_is_503(store, registry, tmp_path):
    app = create_app(store, registry, TokenStore(tmp_path / "none.json", env_tokens=""), embed_ingest=False,
                     allow_no_auth=False)
    with TestClient(app) as c:
        assert c.get("/api/obd/v1/vehicles").status_code == 503


def test_routes(client, msgs):
    ing = client.app_ref.state.ingestor
    ing.handle(msgs.topic("status"), json.dumps(msgs.status(supported_pids=["010C"])))
    ing.handle(msgs.topic("telemetry"), json.dumps(msgs.telemetry({"rpm": 850})))
    ing.handle(msgs.topic("dtc"), json.dumps(msgs.dtc(stored=["P0171"])))
    vs = client.get("/api/obd/v1/vehicles", headers=H).json()
    assert any(v["vehicle_id"] == "slk_r171" and v["connection"] == "online" for v in vs)
    assert client.get("/api/obd/v1/vehicles/slk_r171/latest", headers=H).json()["values"]["rpm"]["value"] == 850
    ts = client.get("/api/obd/v1/vehicles/slk_r171/timeseries", params={"key": "rpm"}, headers=H).json()
    assert ts["points"][0]["value"] == 850
    assert client.get("/api/obd/v1/vehicles/slk_r171/dtc", headers=H).json()["stored"][0]["code"] == "P0171"
    assert client.get("/api/obd/v1/vehicles/slk_r171/sessions", headers=H).json()[0]["n_messages"] == 3
    assert client.get("/api/obd/v1/vehicles/slk_r171/status", headers=H).json()["gateway_state"] == "online"
    assert "Rapport" in client.get("/api/obd/v1/vehicles/slk_r171/report", headers=H).text
    assert client.get("/api/obd/v1/vehicles/nope/latest", headers=H).status_code == 404
    assert "OBD LIVE" in client.get("/obd/").text


def test_websocket_live(client, msgs):
    with client.websocket_connect("/ws/obd/live") as ws:
        ws.send_json({"token": "bad"})
        assert ws.receive_json()["type"] == "error"
    with client.websocket_connect("/ws/obd/live") as ws:
        ws.send_json({"token": TOKEN, "vehicle_id": "slk_r171"})
        assert ws.receive_json()["type"] == "hello"
        assert ws.receive_json()["type"] == "snapshot"
        client.app_ref.state.ingestor.handle(msgs.topic("telemetry"), json.dumps(msgs.telemetry({"rpm": 2222})))
        ev = ws.receive_json()
        assert ev["type"] == "telemetry" and ev["payload"]["data"]["rpm"] == 2222
