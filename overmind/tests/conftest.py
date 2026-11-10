from __future__ import annotations

import sys
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "overmind"))
sys.path.insert(0, str(ROOT / "tools"))
sys.path.insert(0, str(ROOT / "tools" / "elm327_sim"))

from overmind_obd.registry import Registry  # noqa: E402
from overmind_obd.store import Store  # noqa: E402

REGISTRY = {
    "vehicles": {
        "slk_r171": {"label": "SLK test", "engine_type": "petrol", "aspiration": "supercharger",
                     "map_sensor_location": "unknown", "manufacturer": "mercedes", "engine_code": "m271_kompressor",
                     "displacement_l": 1.8, "cylinders": 4},
        "golf": {"engine_type": "petrol", "aspiration": "na", "map_sensor_location": "intake_manifold",
                 "displacement_l": 1.6},
    },
    "gateways": {"android_01": {"vehicles": ["slk_r171", "golf"]}, "disabled_gw": {"vehicles": ["slk_r171"], "enabled": False}},
}

T0 = datetime(2026, 11, 10, 16, 0, 0, tzinfo=timezone.utc)


def ts(seconds: float = 0) -> str:
    return (T0 + timedelta(seconds=seconds)).isoformat(timespec="milliseconds").replace("+00:00", "Z")


@pytest.fixture
def registry() -> Registry:
    return Registry.from_dict(REGISTRY)


@pytest.fixture
def store():
    s = Store(":memory:")
    yield s
    s.close()


class MsgFactory:
    def __init__(self, vehicle: str = "slk_r171", gateway: str = "android_01"):
        self.vehicle, self.gateway, self.seq = vehicle, gateway, 0
        self.session = str(uuid.uuid4())

    def base(self, kind: str, t: float = 0) -> dict:
        self.seq += 1
        return {"schema_version": "1.0", "kind": kind, "message_id": str(uuid.uuid4()), "vehicle_id": self.vehicle,
                "gateway_id": self.gateway, "session_id": self.session, "sequence": self.seq,
                "timestamp": ts(t), "engine_type": "petrol"}

    def telemetry(self, values: dict[str, float], t: float = 0, missing: tuple[str, ...] = ()) -> dict:
        m = self.base("telemetry", t)
        meas = [{"key": k, "pid": None, "value": v, "unit": None, "ts": ts(t), "source": "obd2:7E8", "status": "ok"}
                for k, v in values.items()]
        meas += [{"key": k, "pid": None, "value": None, "unit": None, "ts": ts(t), "source": "obd2", "status": "no_data"}
                 for k in missing]
        m.update(data=dict(values), measurements=meas)
        return m

    def dtc(self, stored=(), pending=(), permanent=None, mil=True, t: float = 0) -> dict:
        m = self.base("dtc", t)
        m["dtc"] = {"mil_on": mil, "stored": [{"code": c, "ecu": "7E8"} for c in stored],
                    "pending": [{"code": c, "ecu": "7E8"} for c in pending],
                    "permanent": None if permanent is None else [{"code": c} for c in permanent], "read_at": ts(t)}
        return m

    def status(self, state: str = "connected", t: float = 0, **extra) -> dict:
        m = self.base("status", t)
        m["status"] = {"state": state, **extra}
        return m

    def health(self, state: str = "online", t: float = 0) -> dict:
        m = self.base("health", t)
        m["health"] = {"state": state}
        return m

    def topic(self, kind: str) -> str:
        return f"overmind/vehicles/{self.vehicle}/" + ("gateway/health" if kind == "health" else f"obd/{kind}")


@pytest.fixture
def msgs() -> MsgFactory:
    return MsgFactory()
