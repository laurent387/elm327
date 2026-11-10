"""Registre des passerelles autorisées et des profils véhicules (YAML)."""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import yaml

DEFAULT_PATH = Path(os.environ.get("OVERMIND_OBD_REGISTRY", Path.home() / ".config/overmind/obd_gateways.yaml"))
EXAMPLE_PATH = Path(__file__).resolve().parent.parent / "config" / "gateways.example.yaml"

ASPIRATIONS = {"na", "turbo", "supercharger"}
MAP_LOCATIONS = {"post_compressor", "pre_throttle", "intake_manifold", "unknown"}
ENGINE_TYPES = {"petrol", "diesel", "unknown"}


@dataclass
class VehicleProfile:
    vehicle_id: str
    label: str = ""
    engine_type: str = "unknown"
    aspiration: str = "na"
    map_sensor_location: str = "unknown"
    manufacturer: str | None = None
    engine_code: str | None = None
    displacement_l: float | None = None
    cylinders: int | None = None
    extra: dict[str, Any] = field(default_factory=dict)

    @property
    def forced_induction(self) -> bool:
        return self.aspiration in ("turbo", "supercharger")

    def as_dict(self) -> dict[str, Any]:
        return {
            "vehicle_id": self.vehicle_id, "label": self.label, "engine_type": self.engine_type,
            "aspiration": self.aspiration, "map_sensor_location": self.map_sensor_location,
            "manufacturer": self.manufacturer, "engine_code": self.engine_code,
            "displacement_l": self.displacement_l, "cylinders": self.cylinders,
        }


@dataclass
class GatewayEntry:
    gateway_id: str
    vehicles: list[str]
    label: str = ""
    enabled: bool = True


class Registry:
    def __init__(self, vehicles: dict[str, VehicleProfile], gateways: dict[str, GatewayEntry]):
        self.vehicles = vehicles
        self.gateways = gateways

    @classmethod
    def from_dict(cls, raw: dict[str, Any]) -> "Registry":
        vehicles: dict[str, VehicleProfile] = {}
        for vid, v in (raw.get("vehicles") or {}).items():
            v = dict(v or {})
            known = {k: v.pop(k) for k in list(v) if k in VehicleProfile.__dataclass_fields__ and k != "extra"}
            prof = VehicleProfile(vehicle_id=vid, **known, extra=v)
            if prof.engine_type not in ENGINE_TYPES:
                raise ValueError(f"{vid}: engine_type invalide {prof.engine_type!r}")
            if prof.aspiration not in ASPIRATIONS:
                raise ValueError(f"{vid}: aspiration invalide {prof.aspiration!r}")
            if prof.map_sensor_location not in MAP_LOCATIONS:
                raise ValueError(f"{vid}: map_sensor_location invalide {prof.map_sensor_location!r}")
            vehicles[vid] = prof
        gateways: dict[str, GatewayEntry] = {}
        for gid, g in (raw.get("gateways") or {}).items():
            g = g or {}
            gateways[gid] = GatewayEntry(gateway_id=gid, vehicles=list(g.get("vehicles") or []),
                                         label=g.get("label", ""), enabled=bool(g.get("enabled", True)))
        return cls(vehicles, gateways)

    @classmethod
    def load(cls, path: str | Path | None = None) -> "Registry":
        p = Path(path) if path else DEFAULT_PATH
        if not p.exists():
            p = EXAMPLE_PATH
        with open(p, encoding="utf-8") as fh:
            return cls.from_dict(yaml.safe_load(fh) or {})

    def is_authorized(self, gateway_id: str, vehicle_id: str) -> bool:
        g = self.gateways.get(gateway_id)
        return bool(g and g.enabled and vehicle_id in g.vehicles)

    def profile(self, vehicle_id: str) -> VehicleProfile:
        return self.vehicles.get(vehicle_id) or VehicleProfile(vehicle_id=vehicle_id)
