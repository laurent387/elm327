"""Bout-en-bout : simulateur ELM327 -> fake_gateway -> broker MQTT (amqtt) -> ingestion -> API."""

import asyncio
import shutil
import socket
import subprocess
import threading
import time

import pytest
from fastapi.testclient import TestClient

import elm327_sim
import fake_gateway
from overmind_obd.api import create_app
from overmind_obd.ingest import MqttSettings
from overmind_obd.tokens import TokenStore


def free_port() -> int:
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p


def wait_port(port: int, timeout: float = 5.0) -> bool:
    end = time.time() + timeout
    while time.time() < end:
        try:
            socket.create_connection(("127.0.0.1", port), timeout=0.2).close()
            return True
        except OSError:
            time.sleep(0.05)
    return False


@pytest.fixture
def broker_port():
    port = free_port()
    try:
        import amqtt.broker  # noqa: F401
    except Exception:
        amqtt_ok = False
    else:
        amqtt_ok = True
    if amqtt_ok:
        state: dict = {}

        def run():
            from amqtt.broker import Broker
            loop = asyncio.new_event_loop()
            asyncio.set_event_loop(loop)
            cfg = {"listeners": {"default": {"type": "tcp", "bind": f"127.0.0.1:{port}"}}, 
                   "plugins": {"amqtt.plugins.authentication.AnonymousAuthPlugin": {"allow_anonymous": True}}}
            b = Broker(cfg, loop=loop)
            state.update(b=b, loop=loop)
            loop.run_until_complete(b.start())
            loop.run_forever()

        threading.Thread(target=run, daemon=True).start()
        if not wait_port(port):
            pytest.skip("broker amqtt non démarré")
        yield port
        asyncio.run_coroutine_threadsafe(state["b"].shutdown(), state["loop"]).result(10)
        state["loop"].call_soon_threadsafe(state["loop"].stop)
    elif shutil.which("mosquitto"):
        proc = subprocess.Popen(["mosquitto", "-p", str(port)])
        wait_port(port)
        yield port
        proc.terminate()
    else:
        pytest.skip("aucun broker MQTT de test (pip install amqtt ou mosquitto)")


def test_full_pipeline(broker_port, store, registry, tmp_path):
    sim_args = elm327_sim.parse_args(["--host", "127.0.0.1", "--port", str(free_port()), "--latency-ms", "2",
                                      "--dtc", "P0171", "--no-permanent"])
    threading.Thread(target=elm327_sim.serve_tcp, args=(sim_args,), daemon=True).start()
    assert wait_port(sim_args.port)

    app = create_app(store, registry, TokenStore(tmp_path / "t.json", env_tokens="tok"), embed_ingest=True,
                     mqtt_settings=MqttSettings(port=broker_port, client_id="test-ingest"))
    H = {"Authorization": "Bearer tok"}
    with TestClient(app) as client:
        assert app.state.mqtt.connected.wait(10), "ingestion non connectée au broker"
        rc = fake_gateway.main(["--elm", f"127.0.0.1:{sim_args.port}", "--mqtt", f"127.0.0.1:{broker_port}",
                                "--count", "6", "--interval", "0.05", "--duration", "30",
                                "--state-file", str(tmp_path / "seq")])
        assert rc == 0
        deadline = time.time() + 10
        live = {}
        while time.time() < deadline:
            live = client.get("/api/obd/v1/vehicles/slk_r171/latest", headers=H).json()["values"]
            sessions = client.get("/api/obd/v1/vehicles/slk_r171/sessions", headers=H).json()
            if {"rpm", "map_kpa_absolute", "engine_load_pct", "boost_kpa_relative"} <= set(live) and sessions \
                    and sessions[0]["ended_at"]:
                break
            time.sleep(0.1)
        assert live["rpm"]["value"] is not None and live["rpm"]["source"] == "obd2:7E8"
        assert live["map_kpa_absolute"]["unit"] == "kPa"
        assert live["boost_kpa_relative"]["source"] == "derived:010B+0133"
        dtc = client.get("/api/obd/v1/vehicles/slk_r171/dtc", headers=H).json()
        assert [d["code"] for d in dtc["stored"]] == ["P0171"] and dtc["permanent"] is None
        st = client.get("/api/obd/v1/vehicles/slk_r171/status", headers=H).json()
        assert st["elm"]["version"] == "ELM327 v1.5"
        assert sessions[0]["n_messages"] >= 8  # status + dtc + 6 télémétries + status final
        assert app.state.ingestor.stats.rejected == {}
