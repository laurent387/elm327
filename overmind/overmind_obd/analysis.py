"""Analyse moteur à règles (lecture seule) pour l'agent Overmind Mechanic.

Entrées : dernières valeurs, trames alignées récentes, DTC, profil véhicule. Sortie : constats
qui distinguent **observation** (fait mesuré), **confirmed** (code défaut effectivement présent)
et **hypothesis** (cause possible, jamais une pièce déclarée défectueuse), avec un niveau de
confiance et la liste des limites de la télémétrie disponible.
"""

from __future__ import annotations

import statistics
import time
from dataclasses import asdict, dataclass, field
from typing import Any

from .knowledge import describe_dtc, vehicle_knowledge
from .registry import Registry, VehicleProfile
from .store import Store, iso

AIR_DENSITY_G_L = 1.184  # air à 25 °C, 101,3 kPa
ANALYSIS_KEYS = [
    "rpm", "speed_kmh", "engine_load_pct", "coolant_temp_c", "map_kpa_absolute", "baro_kpa",
    "intake_air_temp_c", "ambient_air_temp_c", "throttle_pct", "maf_g_s",
    "short_fuel_trim_b1_pct", "long_fuel_trim_b1_pct", "short_fuel_trim_b2_pct", "long_fuel_trim_b2_pct",
    "control_module_v", "run_time_s", "boost_kpa_relative",
]


@dataclass
class Finding:
    id: str
    title: str
    severity: str            # info | warning | critical
    nature: str              # observation | confirmed | hypothesis
    confidence: float
    evidence: list[str] = field(default_factory=list)
    checks_suggested: list[str] = field(default_factory=list)
    related_dtc: list[str] = field(default_factory=list)


@dataclass
class Snapshot:
    profile: VehicleProfile
    latest: dict[str, dict[str, Any]]
    frames: list[dict[str, Any]]
    dtc: dict[str, Any] | None
    supported_pids: list[str]
    window_s: float
    knowledge: dict[str, Any] = field(default_factory=dict)


def _avg(xs: list[float]) -> float | None:
    return statistics.fmean(xs) if xs else None


def _vals(frames: list[dict[str, Any]], key: str, pred=None) -> list[float]:
    return [f[key] for f in frames if key in f and (pred is None or pred(f))]


def _is_idle(f: dict[str, Any]) -> bool:
    return 450 <= f.get("rpm", 0) <= 1100 and f.get("speed_kmh", 0) <= 3 and f.get("throttle_pct", 0) <= 25


def _is_load(f: dict[str, Any]) -> bool:
    return f.get("rpm", 0) >= 2000 and f.get("engine_load_pct", 0) >= 50


def _codes(dtc: dict[str, Any] | None, kind: str) -> list[str]:
    return [d["code"] for d in ((dtc or {}).get(kind) or [])]


# --------------------------------------------------------------------------------------- règles
def rule_fuel_trims(s: Snapshot, out: list[Finding], limits: list[str]) -> None:
    codes = set(_codes(s.dtc, "stored") + _codes(s.dtc, "pending"))
    for bank, lean_codes, rich_codes in (("b1", {"P0171", "P2187"}, {"P0172", "P2188"}),
                                         ("b2", {"P0174"}, {"P0175"})):
        st, lt = f"short_fuel_trim_{bank}_pct", f"long_fuel_trim_{bank}_pct"
        both = [f for f in s.frames if st in f and lt in f]
        if not both:
            if bank == "b1":
                limits.append("Corrections carburant (0106/0107) non disponibles : richesse non évaluable.")
            continue
        total = [f[st] + f[lt] for f in both]
        avg = _avg(total)
        idle = [f[st] + f[lt] for f in both if _is_idle(f)]
        load = [f[st] + f[lt] for f in both if _is_load(f)]
        a_idle, a_load = _avg(idle), _avg(load)
        ev = [f"Correction totale moyenne {bank.upper()} = {avg:+.1f} % sur {len(total)} trames"]
        if a_idle is not None:
            ev.append(f"au ralenti : {a_idle:+.1f} % ({len(idle)} trames)")
        if a_load is not None:
            ev.append(f"en charge : {a_load:+.1f} % ({len(load)} trames)")
        if avg > 10 or (a_idle is not None and a_idle > 10) or (a_load is not None and a_load > 10):
            related = sorted(codes & lean_codes)
            conf = 0.45 + (0.15 if related else 0) + (0.1 if len(total) >= 30 else 0)
            checks = ["Test fumigène du circuit d'admission", "Mesure de la pression carburant au manomètre",
                      "Contrôle du débitmètre (comparaison au débit théorique)"]
            if a_idle is not None and a_load is not None and a_idle > 10 and a_load < a_idle - 5:
                title = f"Mélange pauvre surtout au ralenti ({bank.upper()}) — prise d'air probable"
                ev.append("La correction diminue en charge : signature typique d'une entrée d'air non mesurée.")
            elif a_idle is not None and a_load is not None and a_load > 10 and a_load > a_idle + 3:
                title = f"Mélange pauvre surtout en charge ({bank.upper()}) — alimentation carburant ou MAF"
                ev.append("La correction augmente avec la charge : débit carburant insuffisant ou MAF sous-évaluant.")
                checks = checks[1:] + checks[:1]
            else:
                title = f"Mélange pauvre ({bank.upper()}) — origine à déterminer"
                if a_idle is None or a_load is None:
                    limits.append(f"{bank.upper()} : pas assez de trames ralenti ET charge pour localiser la cause du mélange pauvre.")
            out.append(Finding(f"fuel_trim_lean_{bank}", title, "warning", "hypothesis", round(min(conf, 0.8), 2),
                               ev, checks, related))
        elif avg < -10 or (a_idle is not None and a_idle < -10) or (a_load is not None and a_load < -10):
            related = sorted(codes & rich_codes)
            out.append(Finding(
                f"fuel_trim_rich_{bank}", f"Mélange riche ({bank.upper()})", "warning", "hypothesis",
                round(min(0.45 + (0.15 if related else 0), 0.75), 2), ev,
                ["Contrôler la purge canister", "Rechercher un injecteur qui fuit",
                 "Vérifier la pression carburant", "Contrôler le filtre à air et le MAF"], related))


def rule_maf_consistency(s: Snapshot, out: list[Finding], limits: list[str]) -> None:
    disp = s.profile.displacement_l
    fr = [f for f in s.frames if "maf_g_s" in f and "rpm" in f and f["rpm"] >= 1500]
    if not any("maf_g_s" in f for f in s.frames):
        limits.append("Débit d'air MAF (0110) absent : cohérence débit d'air non vérifiable "
                      "(fréquent sur moteurs à mesure par pression MAP seule).")
        return
    if not disp:
        limits.append("Cylindrée inconnue dans le profil véhicule : comparaison MAF / débit théorique impossible.")
        return
    ratios = []
    for f in fr:
        map_kpa = f.get("map_kpa_absolute")
        if map_kpa is None:
            continue
        iat = f.get("intake_air_temp_c", 25.0)
        density = AIR_DENSITY_G_L * (map_kpa / 101.3) * (298.15 / (iat + 273.15))
        theo = disp * f["rpm"] / 120.0 * density * 0.85  # 4 temps, rendement volumétrique ~0,85
        if theo > 1:
            ratios.append(f["maf_g_s"] / theo)
    if len(ratios) < 5:
        limits.append("Moins de 5 trames ≥ 1500 tr/min avec MAF + MAP : cohérence MAF non évaluée.")
        return
    r = statistics.median(ratios)
    ev = [f"MAF mesuré / débit théorique (speed-density, VE≈0,85) médiane = {r:.2f} sur {len(ratios)} trames"]
    if r < 0.65:
        out.append(Finding("maf_low", "Débit d'air mesuré nettement inférieur au débit théorique", "warning",
                           "hypothesis", 0.35, ev,
                           ["Inspecter/nettoyer le débitmètre", "Rechercher une prise d'air après MAF",
                            "Contrôler le filtre à air"], []))
    elif r > 1.4:
        out.append(Finding("maf_high", "Débit d'air mesuré supérieur au débit théorique", "info", "hypothesis",
                           0.3, ev + ["Peut aussi refléter une suralimentation non prise en compte ou un VE réel plus élevé."],
                           ["Contrôler le débitmètre", "Vérifier la cylindrée déclarée dans le profil"], []))


def rule_idle_map(s: Snapshot, out: list[Finding], limits: list[str]) -> None:
    if s.profile.engine_type == "diesel":
        return  # pas de papillon : dépression de ralenti non significative
    idle = [f for f in s.frames if _is_idle(f) and "map_kpa_absolute" in f]
    if not idle:
        if any("map_kpa_absolute" in f for f in s.frames):
            limits.append("Aucune trame de ralenti stabilisé : dépression de ralenti non évaluée.")
        return
    loc = s.profile.map_sensor_location
    if loc in ("pre_throttle",):
        limits.append("Capteur MAP déclaré avant papillon : la dépression de ralenti n'est pas mesurable.")
        return
    m = statistics.median(f["map_kpa_absolute"] for f in idle)
    baro = s.latest.get("baro_kpa", {}).get("value")
    typ = (s.knowledge.get("typical") or {}).get("idle_map_kpa_abs") or [25, 45]
    ev = [f"MAP médiane au ralenti = {m:.0f} kPa abs sur {len(idle)} trames (repère {typ[0]}–{typ[1]} kPa, indicatif)"]
    if baro:
        ev.append(f"BARO = {baro:.0f} kPa → dépression ≈ {baro - m:.0f} kPa")
    conf = 0.4 if loc == "intake_manifold" else 0.25
    if loc == "unknown":
        limits.append("Emplacement du capteur MAP inconnu : interprétation de la dépression de ralenti à confiance réduite.")
    if m > max(typ[1] + 10, 55):
        out.append(Finding("idle_map_high", "Dépression de ralenti faible (MAP élevée au ralenti)", "warning",
                           "hypothesis", conf, ev,
                           ["Rechercher une prise d'air (fumigène)", "Vérifier ratés d'allumage et calage",
                            "Contrôler la durite du capteur MAP"], []))


def rule_boost(s: Snapshot, out: list[Finding], limits: list[str]) -> None:
    p = s.profile
    if not p.forced_induction:
        return
    if p.map_sensor_location != "post_compressor":
        limits.append(f"Moteur suralimenté ({p.aspiration}) mais capteur MAP déclaré « {p.map_sensor_location} » : "
                      "la pression relative MAP − BARO ne peut pas être interprétée comme pression de suralimentation.")
        return
    baro = s.latest.get("baro_kpa", {}).get("value")
    load = [f for f in s.frames if "map_kpa_absolute" in f and f.get("throttle_pct", 0) >= 80 and f.get("rpm", 0) >= 3000]
    if baro is None and not any("boost_kpa_relative" in f for f in load):
        limits.append("BARO (0133) indisponible : pression relative de suralimentation non calculable.")
        return
    if not load:
        limits.append("Aucune trame pleine charge (papillon ≥ 80 %, ≥ 3000 tr/min) : suralimentation non évaluée.")
        return
    boosts = [f["boost_kpa_relative"] if "boost_kpa_relative" in f else f["map_kpa_absolute"] - (f.get("baro_kpa") or baro)
              for f in load if "boost_kpa_relative" in f or (f.get("baro_kpa") or baro) is not None]
    if not boosts:
        limits.append("BARO indisponible sur les trames pleine charge : suralimentation non calculable.")
        return
    mx = max(boosts)
    typ = (s.knowledge.get("typical") or {}).get("boost_max_kpa_rel")
    ev = [f"Pression relative max pleine charge = {mx:.0f} kPa ({len(load)} trames)"]
    if typ:
        ev.append(f"repère indicatif {typ[0]}–{typ[1]} kPa (fiche moteur NON vérifiée)")
    threshold = typ[0] * 0.6 if typ else 20
    if mx < threshold:
        related = sorted(set(_codes(s.dtc, "stored") + _codes(s.dtc, "pending")) & {"P0299"})
        out.append(Finding("boost_low", "Suralimentation plus faible qu'attendu en pleine charge", "warning",
                           "hypothesis", 0.5 if typ else 0.35, ev,
                           ["Test d'étanchéité du circuit de suralimentation",
                            "Contrôler vanne bypass / wastegate", "Contrôler courroie/embrayage du compresseur"],
                           related))


def rule_coolant(s: Snapshot, out: list[Finding], limits: list[str]) -> None:
    cool = [(f["epoch"], f["coolant_temp_c"]) for f in s.frames if "coolant_temp_c" in f]
    if not cool:
        limits.append("Température liquide (0105) non disponible.")
        return
    mx = max(v for _, v in cool)
    if mx >= 112:
        out.append(Finding("coolant_overheat", f"Température liquide élevée mesurée ({mx:.0f} °C)", "critical",
                           "observation", 0.8, [f"Maximum mesuré {mx:.0f} °C"],
                           ["S'arrêter dès que possible en sécurité", "Contrôler niveau de liquide, ventilateur, pompe à eau"],
                           []))
    run = s.latest.get("run_time_s", {}).get("value")
    span = cool[-1][0] - cool[0][0]
    warmup = max(span, run or 0)
    if warmup < 900:
        limits.append("Moins de 15 min de fonctionnement observées : diagnostic thermostat non concluant.")
        return
    late = [v for t, v in cool if t >= cool[-1][0] - 300]
    if late and max(late) < 80:
        related = sorted(set(_codes(s.dtc, "stored") + _codes(s.dtc, "pending")) & {"P0128", "P0125"})
        out.append(Finding("thermostat_open", "Moteur qui ne monte pas en température", "warning", "hypothesis",
                           0.6 if related else 0.45,
                           [f"Après {warmup/60:.0f} min, température max des 5 dernières minutes = {max(late):.0f} °C"],
                           ["Contrôler le thermostat (bloqué ouvert)", "Comparer avec un thermomètre externe (capteur)",
                            "Vérifier que le motoventilateur ne tourne pas en permanence"], related))


def rule_intake_temp(s: Snapshot, out: list[Finding], limits: list[str]) -> None:
    fr = [f for f in s.frames if "intake_air_temp_c" in f]
    if not fr:
        return
    amb = s.latest.get("ambient_air_temp_c", {}).get("value")
    moving = [f["intake_air_temp_c"] for f in fr if f.get("speed_kmh", 0) >= 50]
    if moving and amb is not None and _avg(moving) - amb > 30:
        out.append(Finding("iat_high", "Air d'admission très chaud en roulage", "info", "hypothesis", 0.35,
                           [f"IAT moyenne en roulage {_avg(moving):.0f} °C, ambiante {amb:.0f} °C"],
                           ["Vérifier l'échangeur / intercooler et son circuit (moteur suralimenté)",
                            "Vérifier l'emplacement du capteur IAT (après compresseur = normalement plus chaud)"], []))
    elif max(f["intake_air_temp_c"] for f in fr) > 75:
        out.append(Finding("iat_very_high", "Température d'air d'admission > 75 °C mesurée", "info", "observation",
                           0.5, [f"Max {max(f['intake_air_temp_c'] for f in fr):.0f} °C"],
                           ["Contrôler refroidissement de l'air de suralimentation"], []))


def rule_voltage(s: Snapshot, out: list[Finding], limits: list[str]) -> None:
    fr = [f for f in s.frames if "control_module_v" in f]
    if not fr:
        limits.append("Tension module (0142) non disponible ; utiliser ATRV (tension prise OBD) dans le statut passerelle.")
        return
    running = [f["control_module_v"] for f in fr if f.get("rpm", 0) > 500]
    if running:
        v = statistics.median(running)
        if v < 13.2:
            out.append(Finding("charging_low", "Tension de charge basse moteur tournant", "warning", "hypothesis", 0.5,
                               [f"Tension médiane moteur tournant {v:.2f} V (attendu ~13,5–14,7 V)"],
                               ["Mesurer la tension aux bornes batterie au multimètre", "Contrôler alternateur et courroie"],
                               []))
        elif v > 15.0:
            out.append(Finding("charging_high", "Surtension moteur tournant", "warning", "hypothesis", 0.5,
                               [f"Tension médiane {v:.2f} V"], ["Contrôler le régulateur d'alternateur"], []))


def rule_dtc(s: Snapshot, out: list[Finding], limits: list[str]) -> None:
    if s.dtc is None:
        limits.append("Aucune lecture de codes défauts reçue.")
        return
    p = s.profile
    for kind, label, nature in (("stored", "confirmé (mémorisé)", "confirmed"),
                                ("pending", "en attente (non confirmé)", "hypothesis"),
                                ("permanent", "permanent", "confirmed")):
        lst = s.dtc.get(kind)
        if lst is None:
            if kind == "permanent":
                limits.append("Codes permanents (mode 0A) non supportés par le calculateur.")
            continue
        for d in lst:
            info = describe_dtc(d["code"], p.manufacturer, p.engine_code)
            ev = [f"Code {d['code']} {label}" + (f" — calculateur {d['ecu']}" if d.get("ecu") else ""),
                  info["description"]]
            if info.get("causes"):
                ev.append("Causes possibles (hypothèses) : " + " ; ".join(info["causes"][:4]))
            if info.get("note"):
                ev.append(info["note"])
            ev.append("Un code défaut seul ne permet pas de conclure qu'une pièce est défectueuse.")
            out.append(Finding(f"dtc_{kind}_{d['code']}", f"{d['code']} — {info['description']}",
                               info.get("severity", "warning"), nature, 0.9 if nature == "confirmed" else 0.5,
                               ev, info.get("checks", []), [d["code"]]))
    if s.dtc.get("mil_on"):
        out.append(Finding("mil_on", "Témoin moteur (MIL) allumé", "warning", "observation", 0.95,
                           ["Le calculateur signale MIL = ON"], [], []))


RULES = [rule_dtc, rule_fuel_trims, rule_maf_consistency, rule_idle_map, rule_boost, rule_coolant,
         rule_intake_temp, rule_voltage]


def analyze_snapshot(s: Snapshot) -> dict[str, Any]:
    findings: list[Finding] = []
    limits: list[str] = []
    for rule in RULES:
        rule(s, findings, limits)
    available = sorted({k for f in s.frames for k in f if k not in ("epoch", "ts")})
    if not s.frames:
        limits.insert(0, "Aucune trame de télémétrie dans la fenêtre analysée.")
    if s.knowledge and not s.knowledge.get("verified", False):
        limits.append("Les repères de la fiche moteur sont indicatifs et non vérifiés.")
    order = {"critical": 0, "warning": 1, "info": 2}
    findings.sort(key=lambda f: (order.get(f.severity, 3), -f.confidence))
    n = len(s.frames)
    overall = 0.2 if n < 10 else 0.4 if n < 60 else 0.6 if n < 600 else 0.7
    if len(limits) > 4:
        overall -= 0.1
    return {
        "vehicle": s.profile.as_dict(),
        "generated_at": iso(time.time()),
        "window_s": s.window_s,
        "data_quality": {"frames": n, "keys_available": available, "supported_pids": s.supported_pids},
        "findings": [asdict(f) for f in findings],
        "limits": limits,
        "overall_confidence": round(max(overall, 0.1), 2),
        "disclaimer": "Analyse automatique indicative, en lecture seule. Les hypothèses doivent être confirmées "
                      "par des contrôles physiques avant toute réparation.",
    }


def build_snapshot(store: Store, registry: Registry, vehicle_id: str, window_s: float = 900,
                   end: float | None = None) -> Snapshot:
    end = end or time.time()
    prof = registry.profile(vehicle_id)
    return Snapshot(
        profile=prof,
        latest=store.latest(vehicle_id),
        frames=store.frames(vehicle_id, ANALYSIS_KEYS, end - window_s, end),
        dtc=store.latest_dtc(vehicle_id),
        supported_pids=store.supported_pids(vehicle_id),
        window_s=window_s,
        knowledge=vehicle_knowledge(prof.manufacturer, prof.engine_code),
    )


def analyze(store: Store, registry: Registry, vehicle_id: str, window_s: float = 900) -> dict[str, Any]:
    return analyze_snapshot(build_snapshot(store, registry, vehicle_id, window_s))
