"""Validation des messages passerelle : schéma JSON + règles du contrat (PROTOCOL.md)."""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from functools import lru_cache
from typing import Any

from jsonschema import Draft202012Validator

from . import SCHEMA_PATH
from .registry import Registry

TOPIC_RE = re.compile(
    r"^overmind/vehicles/(?P<vehicle>[a-z0-9_\-]{1,64})/(?P<scope>obd/(?:telemetry|dtc|status)|gateway/health)$"
)
TOPIC_KIND = {"obd/telemetry": "telemetry", "obd/dtc": "dtc", "obd/status": "status", "gateway/health": "health"}
MAX_PAYLOAD = 256 * 1024


class Rejected(Exception):
    """Message refusé ; `reason` est un code court journalisé."""

    def __init__(self, reason: str, detail: str = ""):
        super().__init__(f"{reason}: {detail}" if detail else reason)
        self.reason = reason
        self.detail = detail


@dataclass
class ValidMessage:
    topic: str
    kind: str
    vehicle_id: str
    gateway_id: str
    payload: dict[str, Any]
    is_lwt: bool = False

    @property
    def message_id(self) -> str | None:
        return self.payload.get("message_id")


@lru_cache(maxsize=1)
def _validator() -> Draft202012Validator:
    with open(SCHEMA_PATH, encoding="utf-8") as fh:
        return Draft202012Validator(json.load(fh))


def parse_topic(topic: str) -> tuple[str, str]:
    m = TOPIC_RE.match(topic)
    if not m:
        raise Rejected("bad_topic", topic)
    return m.group("vehicle"), TOPIC_KIND[m.group("scope")]


def validate(topic: str, raw: bytes | str | dict, registry: Registry) -> ValidMessage:
    vehicle_from_topic, kind_from_topic = parse_topic(topic)
    if isinstance(raw, dict):
        payload = raw
    else:
        if len(raw) > MAX_PAYLOAD:
            raise Rejected("too_large", str(len(raw)))
        try:
            payload = json.loads(raw)
        except (ValueError, UnicodeDecodeError) as exc:
            raise Rejected("bad_json", str(exc)) from exc
    if not isinstance(payload, dict):
        raise Rejected("bad_json", "objet attendu")
    errors = sorted(_validator().iter_errors(payload), key=lambda e: list(e.path))
    if errors:
        e = errors[0]
        raise Rejected("schema", f"{'/'.join(map(str, e.path)) or '<racine>'}: {e.message}")
    if payload["kind"] != kind_from_topic:
        raise Rejected("kind_mismatch", f"{payload['kind']} sur {kind_from_topic}")
    if payload["vehicle_id"] != vehicle_from_topic:
        raise Rejected("vehicle_mismatch", f"{payload['vehicle_id']} != {vehicle_from_topic}")
    gw = payload["gateway_id"]
    if not registry.is_authorized(gw, vehicle_from_topic):
        raise Rejected("gateway_not_authorized", f"{gw} -> {vehicle_from_topic}")
    is_lwt = kind_from_topic == "health" and "message_id" not in payload
    if is_lwt and payload["health"].get("state") != "offline":
        raise Rejected("schema", "health sans message_id réservé au LWT offline")
    return ValidMessage(topic, kind_from_topic, vehicle_from_topic, gw, payload, is_lwt)
