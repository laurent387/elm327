"""Service d'ingestion MQTT : passerelles Android -> validation -> stockage -> bus Overmind.

    python -m overmind_obd.ingest            # variables OVERMIND_OBD_MQTT_* (voir README)

Le service s'abonne en lecture seule ; il ne publie jamais sur un topic lu par une passerelle
(il n'en existe d'ailleurs aucun) et ne relaie aucune commande vers le véhicule.
"""

from __future__ import annotations

import logging
import os
import ssl
import threading
import time
from collections import Counter
from dataclasses import dataclass, field
from typing import Any

import paho.mqtt.client as mqtt

from .bus import EventBus
from .registry import Registry
from .store import Store, iso
from .validation import Rejected, validate

log = logging.getLogger("overmind_obd.ingest")

SUBSCRIPTIONS = [("overmind/vehicles/+/obd/#", 1), ("overmind/vehicles/+/gateway/health", 1)]


def _derive_data(payload: dict[str, Any]) -> dict[str, float]:
    """Vue compacte normalisée : uniquement les mesures `ok` (jamais de valeur inventée)."""
    out: dict[str, float] = {}
    for m in payload.get("measurements") or []:
        if m.get("status") == "ok" and isinstance(m.get("value"), (int, float)):
            out[m["key"]] = m["value"]
    return out


@dataclass
class IngestStats:
    accepted: int = 0
    duplicates: int = 0
    rejected: Counter = field(default_factory=Counter)

    def as_dict(self) -> dict[str, Any]:
        return {"accepted": self.accepted, "duplicates": self.duplicates, "rejected": dict(self.rejected)}


class Ingestor:
    """Cœur de l'ingestion, indépendant du transport (testable sans broker)."""

    def __init__(self, store: Store, registry: Registry, bus: EventBus | None = None,
                 lost_after_s: float = 30.0):
        self.store = store
        self.registry = registry
        self.bus = bus or EventBus()
        self.lost_after_s = lost_after_s
        self.stats = IngestStats()
        self._lock = threading.Lock()
        self._last_seen: dict[tuple[str, str], float] = {}
        self._state: dict[tuple[str, str], str] = {}

    def handle(self, topic: str, raw: bytes | str | dict) -> str:
        """Traite un message. Renvoie 'accepted', 'duplicate' ou 'rejected:<raison>'."""
        try:
            msg = validate(topic, raw, self.registry)
        except Rejected as exc:
            with self._lock:
                self.stats.rejected[exc.reason] += 1
            log.warning("message rejeté sur %s : %s", topic, exc)
            return f"rejected:{exc.reason}"
        payload = msg.payload
        if msg.kind == "telemetry":
            # normalisation : la vue compacte est recalculée depuis les mesures détaillées
            payload["data"] = _derive_data(payload)
        fresh = self.store.ingest(msg.kind, msg.vehicle_id, msg.gateway_id, payload)
        if not fresh:
            with self._lock:
                self.stats.duplicates += 1
            return "duplicate"
        key = (msg.gateway_id, msg.vehicle_id)
        now = time.time()
        with self._lock:
            self.stats.accepted += 1
            self._last_seen[key] = now
            prev = self._state.get(key)
            new_state = "offline" if msg.is_lwt or (msg.kind == "health" and payload["health"].get("state") == "offline") else "online"
            self._state[key] = new_state
        event = {"type": msg.kind, "vehicle_id": msg.vehicle_id, "gateway_id": msg.gateway_id,
                 "received_at": iso(now), "payload": payload}
        self.bus.publish(event)
        if prev != new_state:
            self._gateway_state(msg.gateway_id, msg.vehicle_id, new_state, "lwt" if msg.is_lwt else "message")
        return "accepted"

    def _gateway_state(self, gateway_id: str, vehicle_id: str, state: str, reason: str) -> None:
        self.store.mark_gateway_state(gateway_id, vehicle_id, state)
        log.info("passerelle %s (%s) -> %s (%s)", gateway_id, vehicle_id, state, reason)
        self.bus.publish({"type": "gateway_state", "vehicle_id": vehicle_id, "gateway_id": gateway_id,
                          "state": state, "reason": reason, "at": iso(time.time())})

    def check_lost(self, now: float | None = None) -> list[tuple[str, str]]:
        """Passe en `lost` les passerelles muettes depuis plus de `lost_after_s`."""
        now = now or time.time()
        lost = []
        with self._lock:
            for key, seen in self._last_seen.items():
                if self._state.get(key) == "online" and now - seen > self.lost_after_s:
                    self._state[key] = "lost"
                    lost.append(key)
        for gw, vid in lost:
            self._gateway_state(gw, vid, "lost", f"aucun message depuis {self.lost_after_s:.0f} s")
        return lost

    def gateway_states(self) -> dict[str, str]:
        with self._lock:
            return {f"{g}/{v}": s for (g, v), s in self._state.items()}


@dataclass
class MqttSettings:
    host: str = "127.0.0.1"
    port: int = 1883
    username: str | None = None
    password: str | None = None
    tls: bool = False
    ca_file: str | None = None
    client_id: str = "overmind-obd-ingest"

    @classmethod
    def from_env(cls) -> "MqttSettings":
        e = os.environ
        return cls(
            host=e.get("OVERMIND_OBD_MQTT_HOST", "127.0.0.1"),
            port=int(e.get("OVERMIND_OBD_MQTT_PORT", "1883")),
            username=e.get("OVERMIND_OBD_MQTT_USER") or None,
            password=e.get("OVERMIND_OBD_MQTT_PASSWORD") or None,
            tls=e.get("OVERMIND_OBD_MQTT_TLS", "0") in ("1", "true", "yes"),
            ca_file=e.get("OVERMIND_OBD_MQTT_CA") or None,
            client_id=e.get("OVERMIND_OBD_MQTT_CLIENT_ID", "overmind-obd-ingest"),
        )


class MqttIngestService:
    """Abonné paho v2 (session persistante, QoS 1, reconnexion automatique) + chien de garde."""

    def __init__(self, ingestor: Ingestor, settings: MqttSettings | None = None, watchdog_period_s: float = 5.0):
        self.ingestor = ingestor
        self.settings = settings or MqttSettings.from_env()
        self.watchdog_period_s = watchdog_period_s
        self.connected = threading.Event()
        self._stop = threading.Event()
        s = self.settings
        self.client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id=s.client_id,
                                  clean_session=False, protocol=mqtt.MQTTv311)
        if s.username:
            self.client.username_pw_set(s.username, s.password)
        if s.tls:
            self.client.tls_set(ca_certs=s.ca_file, cert_reqs=ssl.CERT_REQUIRED, tls_version=ssl.PROTOCOL_TLS_CLIENT)
            self.client.tls_insecure_set(False)
        self.client.reconnect_delay_set(1, 30)
        self.client.on_connect = self._on_connect
        self.client.on_disconnect = self._on_disconnect
        self.client.on_message = self._on_message
        self._wd = threading.Thread(target=self._watchdog, name="obd-watchdog", daemon=True)

    def _on_connect(self, client, userdata, flags, reason_code, properties) -> None:
        if reason_code.is_failure:
            log.error("connexion MQTT refusée : %s", reason_code)
            return
        client.subscribe(SUBSCRIPTIONS)
        log.info("connecté à %s:%s, abonné %s", self.settings.host, self.settings.port, [t for t, _ in SUBSCRIPTIONS])
        self.connected.set()

    def _on_disconnect(self, client, userdata, flags, reason_code, properties) -> None:
        self.connected.clear()
        if not self._stop.is_set():
            log.warning("MQTT déconnecté (%s), reconnexion automatique", reason_code)

    def _on_message(self, client, userdata, msg) -> None:
        try:
            self.ingestor.handle(msg.topic, msg.payload)
        except Exception:
            log.exception("erreur d'ingestion sur %s", msg.topic)

    def _watchdog(self) -> None:
        while not self._stop.wait(self.watchdog_period_s):
            self.ingestor.check_lost()

    def start(self) -> None:
        self.client.connect_async(self.settings.host, self.settings.port, keepalive=30)
        self.client.loop_start()
        self._wd.start()

    def stop(self) -> None:
        self._stop.set()
        self.client.disconnect()
        self.client.loop_stop()


def main() -> int:
    logging.basicConfig(level=os.environ.get("OVERMIND_OBD_LOG", "INFO"),
                        format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    ingestor = Ingestor(Store(), Registry.load(),
                        lost_after_s=float(os.environ.get("OVERMIND_OBD_LOST_AFTER_S", "30")))
    svc = MqttIngestService(ingestor)
    svc.start()
    try:
        while True:
            time.sleep(60)
            log.info("stats %s", ingestor.stats.as_dict())
    except KeyboardInterrupt:
        svc.stop()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
