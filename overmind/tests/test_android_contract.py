"""Contrat Android ↔ serveur : les messages réellement produits par l'appli Kotlin
(MessageFactoryTest les écrit dans android/app/build/sample-messages/, copiés ici en
fixtures) doivent passer la validation d'ingestion et être stockés."""
import json
from pathlib import Path

import pytest

from overmind_obd.registry import Registry
from overmind_obd.validation import validate

FIX = Path(__file__).parent / "fixtures" / "android"
CONFIG = Path(__file__).resolve().parents[1] / "config" / "gateways.example.yaml"
TOPICS = {
    "telemetry": "overmind/vehicles/slk_r171/obd/telemetry",
    "dtc": "overmind/vehicles/slk_r171/obd/dtc",
    "status": "overmind/vehicles/slk_r171/obd/status",
    "health": "overmind/vehicles/slk_r171/gateway/health",
    "lwt": "overmind/vehicles/slk_r171/gateway/health",
}


@pytest.fixture(scope="module")
def registry():
    return Registry.load(CONFIG)


@pytest.mark.parametrize("name", sorted(TOPICS))
def test_android_message_accepted(name, registry):
    raw = (FIX / f"{name}.json").read_text()
    msg = validate(TOPICS[name], raw, registry)
    assert msg is not None


def test_android_telemetry_never_reports_missing_values(registry):
    payload = json.loads((FIX / "telemetry.json").read_text())
    for m in payload["measurements"]:
        if m["status"] != "ok":
            assert m["value"] is None
            assert m["key"] not in payload["data"]
