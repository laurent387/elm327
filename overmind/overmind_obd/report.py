"""Rapport de diagnostic Markdown en français (à partir de l'analyse et de l'historique)."""

from __future__ import annotations

from typing import Any

from .analysis import analyze
from .knowledge import describe_dtc
from .registry import Registry
from .store import Store

SEV = {"critical": "🔴 critique", "warning": "🟠 attention", "info": "🔵 info"}
NATURE = {"observation": "Observation mesurée", "confirmed": "Défaut confirmé par le calculateur",
          "hypothesis": "Hypothèse à vérifier"}
KEY_LABELS = {
    "rpm": ("Régime", "tr/min"), "speed_kmh": ("Vitesse", "km/h"), "engine_load_pct": ("Charge", "%"),
    "coolant_temp_c": ("Liquide refroid.", "°C"), "map_kpa_absolute": ("MAP abs.", "kPa"),
    "baro_kpa": ("BARO", "kPa"), "boost_kpa_relative": ("Pression relative", "kPa"),
    "intake_air_temp_c": ("Air admission", "°C"), "maf_g_s": ("MAF", "g/s"), "throttle_pct": ("Papillon", "%"),
    "short_fuel_trim_b1_pct": ("STFT B1", "%"), "long_fuel_trim_b1_pct": ("LTFT B1", "%"),
}


def _fmt(v: Any) -> str:
    if v is None:
        return "—"
    return f"{v:.1f}" if isinstance(v, float) and not v.is_integer() else f"{v:.0f}" if isinstance(v, float) else str(v)


def render_report(analysis: dict[str, Any], latest: dict[str, dict[str, Any]], dtc: dict[str, Any] | None,
                  status: dict[str, Any] | None, session: dict[str, Any] | None = None) -> str:
    v = analysis["vehicle"]
    L: list[str] = []
    L.append(f"# Rapport de diagnostic — {v.get('label') or v['vehicle_id']}")
    L.append("")
    L.append(f"*Généré le {analysis['generated_at']} — fenêtre analysée : {analysis['window_s']/60:.0f} min — "
             f"confiance globale : {analysis['overall_confidence']*100:.0f} %*")
    L.append("")
    L.append(f"> {analysis['disclaimer']}")
    L.append("")
    L.append("## Véhicule")
    L.append(f"- Identifiant : `{v['vehicle_id']}` — moteur {v.get('engine_type')} / {v.get('aspiration')}"
             + (f" — {v.get('manufacturer')} {v.get('engine_code')}" if v.get("engine_code") else ""))
    L.append(f"- Emplacement capteur MAP déclaré : {v.get('map_sensor_location')}")
    if status:
        elm = status.get("elm") or {}
        L.append(f"- Interface : {elm.get('version', '?')} — protocole {elm.get('protocol_name') or elm.get('protocol', '?')}"
                 + (f" — VIN {status['vin']}" if status.get("vin") else ""))
    L.append("")
    L.append("## Codes défauts")
    if not dtc:
        L.append("Aucune lecture de codes défauts disponible.")
    else:
        L.append(f"Témoin moteur (MIL) : {'allumé' if dtc.get('mil_on') else 'éteint' if dtc.get('mil_on') is False else 'inconnu'} "
                 f"— lu le {dtc.get('read_at') or dtc.get('ts')}")
        for kind, label in (("stored", "Confirmés"), ("pending", "En attente"), ("permanent", "Permanents")):
            lst = dtc.get(kind)
            if lst is None:
                L.append(f"- {label} : non supporté par le calculateur")
            elif not lst:
                L.append(f"- {label} : aucun")
            else:
                for d in lst:
                    L.append(f"- {label} : **{d['code']}** — {describe_dtc(d['code'], v.get('manufacturer'), v.get('engine_code'))['description']}")
    L.append("")
    L.append("## Dernières mesures")
    L.append("| Paramètre | Valeur | Unité | Âge | Statut |")
    L.append("|---|---|---|---|---|")
    for key, m in sorted(latest.items()):
        label, unit = KEY_LABELS.get(key, (key, m.get("unit") or ""))
        age = "—" if m.get("age_s") is None else f"{m['age_s']:.0f} s"
        L.append(f"| {label} | {_fmt(m.get('value'))} | {m.get('unit') or unit} | {age} | "
                 f"{'périmée' if m.get('stale') else m.get('status')} |")
    L.append("")
    L.append("## Constats")
    if not analysis["findings"]:
        L.append("Aucune anomalie détectée par les règles automatiques sur les données disponibles.")
    for f in analysis["findings"]:
        L.append(f"### {SEV.get(f['severity'], f['severity'])} — {f['title']}")
        L.append(f"*{NATURE.get(f['nature'], f['nature'])} — confiance {f['confidence']*100:.0f} %*")
        L.append("")
        for e in f["evidence"]:
            L.append(f"- {e}")
        if f["checks_suggested"]:
            L.append("")
            L.append("Contrôles complémentaires proposés :")
            for c in f["checks_suggested"]:
                L.append(f"1. {c}")
        L.append("")
    L.append("## Limites de la télémétrie")
    for lim in analysis["limits"] or ["Aucune limite particulière relevée."]:
        L.append(f"- {lim}")
    if session:
        L.append("")
        L.append(f"## Session `{session['session_id']}`")
        L.append(f"Du {session['started_at']} au {session.get('ended_at') or session['last_at']} — "
                 f"{session['n_messages']} messages")
        L.append("| Mesure | min | moy | max | n |")
        L.append("|---|---|---|---|---|")
        for st in session.get("stats", []):
            L.append(f"| {st['key']} ({st['unit']}) | {_fmt(st['min'])} | {_fmt(st['avg'])} | {_fmt(st['max'])} | {st['n']} |")
    L.append("")
    return "\n".join(L)


def generate_report(store: Store, registry: Registry, vehicle_id: str, window_s: float = 900,
                    session_id: str | None = None) -> str:
    a = analyze(store, registry, vehicle_id, window_s)
    return render_report(a, store.latest(vehicle_id), store.latest_dtc(vehicle_id), store.latest_status(vehicle_id, require="elm"),
                         store.session(session_id) if session_id else None)
