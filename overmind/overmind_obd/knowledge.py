"""Base de connaissances extensible : DTC génériques (FR) + fiches constructeur/motorisation."""

from __future__ import annotations

from functools import lru_cache
from pathlib import Path
from typing import Any

import yaml

KNOWLEDGE_DIR = Path(__file__).resolve().parent / "knowledge"

DTC_SYSTEMS = {"P": "Groupe motopropulseur", "C": "Châssis", "B": "Carrosserie", "U": "Réseau / communication"}


@lru_cache(maxsize=1)
def generic_dtc() -> dict[str, dict[str, Any]]:
    with open(KNOWLEDGE_DIR / "dtc_generic_fr.yaml", encoding="utf-8") as fh:
        return yaml.safe_load(fh) or {}


@lru_cache(maxsize=32)
def vehicle_knowledge(manufacturer: str | None, engine_code: str | None) -> dict[str, Any]:
    """Charge knowledge/vehicles/<fabricant>_<moteur>.yaml s'il existe, sinon {}."""
    if not manufacturer or not engine_code:
        return {}
    p = KNOWLEDGE_DIR / "vehicles" / f"{manufacturer}_{engine_code}.yaml"
    if not p.exists():
        return {}
    with open(p, encoding="utf-8") as fh:
        return yaml.safe_load(fh) or {}


def describe_dtc(code: str, manufacturer: str | None = None, engine_code: str | None = None) -> dict[str, Any]:
    code = code.upper()
    entry = dict(generic_dtc().get(code) or {})
    specific = (vehicle_knowledge(manufacturer, engine_code).get("dtc") or {}).get(code)
    if specific:
        entry = {**entry, **specific, "vehicle_specific": True}
    if not entry:
        generic = len(code) == 5 and code[1] in "02"  # P0xxx / P2xxx : codes SAE génériques
        entry = {"description": "Code non répertorié dans la base locale"
                 + ("" if generic else " (probablement spécifique constructeur)"),
                 "causes": [], "checks": []}
    entry.setdefault("system", DTC_SYSTEMS.get(code[:1], "inconnu"))
    entry["code"] = code
    return entry
