"""Outils serveur du copilote — tous en LECTURE SEULE (overmind_obd.queries.ObdQueries).

Il n'existe volontairement aucun outil d'écriture : la liste TOOLS est fermée et vérifiée
par les tests. Le véhicule n'est jamais contacté depuis ici : on lit la base d'ingestion.
"""

from __future__ import annotations

import re
from typing import Any, Callable

from overmind_obd.queries import ObdQueries

TOOLS = ("vehicle_live", "vehicle_dtc", "vehicle_history", "vehicle_diagnostic")

# Grandeurs résumées dans le contexte de chaque requête (évite un aller-retour outil).
SUMMARY_KEYS = (
    "rpm", "speed_kmh", "coolant_temp_c", "engine_load_pct", "intake_air_temp_c",
    "map_kpa_absolute", "boost_kpa_relative", "control_module_v", "fuel_level_pct",
    "ambient_air_temp_c", "short_fuel_trim_b1_pct", "long_fuel_trim_b1_pct", "oil_temp_c",
)


# Lectures factuelles répondues SANS le modèle : jamais de valeur inventée, et instantané.
# (clé, motif de question, gabarit ; {v} = valeur arrondie)
FACTS: list[tuple[str, re.Pattern[str], str]] = [
    ("rpm", re.compile(r"\b(régime|regime|tours? minute|tr/min|rpm)\b", re.I), "Le moteur tourne à {v} tours par minute."),
    ("speed_kmh", re.compile(r"\b(vitesse|je roule à combien|à combien je roule)\b", re.I), "La voiture roule à {v} kilomètres heure."),
    ("oil_temp_c", re.compile(r"\btempérature (de |d')?(l')?huile\b", re.I), "L'huile est à {v} degrés."),
    ("ambient_air_temp_c", re.compile(r"\btempérature (extérieure|dehors)\b|\bil fait (froid|chaud|combien) dehors\b", re.I),
     "Il fait {v} degrés dehors."),
    ("coolant_temp_c", re.compile(r"\btempérature\b(.{0,20}\b(moteur|eau|liquide)\b)?", re.I),
     "Le liquide de refroidissement est à {v} degrés."),
    ("control_module_v", re.compile(r"\b(batterie|tension)\b", re.I), "La tension est de {v} volts."),
    ("fuel_level_pct", re.compile(r"\b(essence|carburant|réservoir|jauge)\b", re.I), "Il reste environ {v} pour cent de carburant."),
]
DTC_QUESTION = re.compile(r"\b(codes?|défauts?|defauts?|voyant|erreurs?)\b", re.I)


def _spoken(key: str, v: float) -> str:
    if key == "rpm":
        return str(int(round(v, -1)))
    if key == "control_module_v":
        return f"{v:.1f}".replace(".", ",")
    return str(int(round(v)))


class ToolError(Exception):
    pass


def _duration(s: float | None) -> str:
    if s is None:
        return "durée inconnue"
    s = int(s)
    if s < 90:
        return f"{s} s"
    if s < 5400:
        return f"{round(s / 60)} min"
    if s < 172800:
        return f"{round(s / 3600)} h"
    return f"{round(s / 86400)} jours"


def _fmt_value(v: dict[str, Any]) -> str:
    if v.get("value") is None:
        return "indisponible"
    val = v["value"]
    txt = f"{round(val, 1) if isinstance(val, float) else val} {v.get('unit') or ''}".strip()
    if v.get("stale"):
        txt += f" (ANCIENNE, il y a {_duration(v.get('age_s'))})"
    return txt


class VehicleTools:
    def __init__(self, queries: ObdQueries, mechanic: Callable[[str], dict[str, Any]] | None = None):
        self.q = queries
        self.mechanic = mechanic

    # --- Contexte injecté dans chaque requête -------------------------------------------------
    def summary(self, vehicle_id: str) -> str:
        """Résumé court et factuel ; ne contient que ce qui a été réellement mesuré."""
        try:
            conn = self.q.connection_status(vehicle_id)
        except KeyError:
            return f"Véhicule {vehicle_id} inconnu d'Overmind."
        state = conn["gateway_state"]
        since = conn.get("seconds_since_last_message")
        lines = [f"Véhicule {vehicle_id} — passerelle OBD : {state}"
                 + (f", dernier message il y a {_duration(since)}" if since is not None else "")]
        live = self.q.live_data(vehicle_id)["values"]
        fresh = {k: v for k, v in live.items() if not v.get("stale") and v.get("value") is not None}
        old = {k: v for k, v in live.items() if v.get("stale") and v.get("value") is not None}
        vals = [f"{k} = {_fmt_value(fresh[k])}" for k in SUMMARY_KEYS if k in fresh]
        if vals and state == "online":
            lines.append("Mesures EN DIRECT : " + "; ".join(vals))
        else:
            lines.append("Mesures en direct : AUCUNE (la voiture n'envoie rien en ce moment). "
                         "Ne présente JAMAIS les valeurs ci-dessous comme actuelles.")
            olds = [f"{k} = {old[k]['value']} {old[k].get('unit') or ''}".strip()
                    + f" (il y a {_duration(old[k].get('age_s'))})" for k in SUMMARY_KEYS if k in old]
            if olds:
                lines.append("Dernières valeurs connues, ANCIENNES : " + "; ".join(olds))
        d = self.q.dtc(vehicle_id)
        if not d.get("available"):
            lines.append("Codes défauts : jamais lus")
        else:
            def label(x: dict[str, Any], suffix: str = "") -> str:
                desc = x.get("description")
                return x["code"] + suffix + (f" = {desc}" if desc else "")
            codes = [label(x) for x in d.get("stored") or []] + [label(x, " (en attente)") for x in d.get("pending") or []]
            lines.append(f"Codes défauts (lus {d.get('ts') or d.get('read_at') or '?'}) : "
                         + (", ".join(codes) if codes else "aucun") + f" ; voyant moteur {'allumé' if d.get('mil_on') else 'éteint'}")
        return "\n".join(lines)

    # --- Réponses factuelles directes ---------------------------------------------------------------
    def fact_answer(self, vehicle_id: str, utterance: str) -> str | None:
        """Répond aux questions simples de lecture à partir des seules données réelles.

        None = question non reconnue (le modèle s'en charge). Une question longue ou qui
        demande une explication (« pourquoi », « normal ») part aussi au modèle.
        """
        u = utterance.lower()
        if len(u.split()) > 12 or re.search(r"\b(pourquoi|normal|explique|grave|inquiet|historique|évolution|monte|baisse|augmente|descend|chauffe)\b", u):
            return None
        if DTC_QUESTION.search(u) and not any(rx.search(u) for _, rx, _ in FACTS):
            return self._dtc_answer(vehicle_id)
        for key, rx, tpl in FACTS:
            if rx.search(u):
                return self._value_answer(vehicle_id, key, tpl)
        return None

    def _value_answer(self, vehicle_id: str, key: str, tpl: str) -> str:
        try:
            conn = self.q.connection_status(vehicle_id)
        except KeyError:
            return "Je ne connais pas encore cette voiture dans Overmind."
        v = self.q.live_data(vehicle_id, [key])["values"].get(key)
        online = conn["gateway_state"] == "online"
        if v and v.get("value") is not None and not v.get("stale") and online:
            return tpl.format(v=_spoken(key, float(v["value"])))
        lead = "La voiture n'envoie rien en ce moment." if not online else "Cette mesure n'arrive pas en ce moment."
        if v and v.get("value") is not None and v.get("age_s") is not None:
            return f"{lead} La dernière valeur, il y a {_duration(v['age_s'])}, était : " + \
                tpl.format(v=_spoken(key, float(v["value"]))).rstrip(".").lower() + "."
        if v is None and online:
            return "Ta voiture ne fournit pas cette mesure."
        return lead

    def _dtc_answer(self, vehicle_id: str) -> str:
        try:
            d = self.q.dtc(vehicle_id)
        except KeyError:
            return "Je ne connais pas encore cette voiture dans Overmind."
        if not d.get("available"):
            return "Je n'ai encore jamais lu les codes défauts de la voiture."
        codes = (d.get("stored") or []) + (d.get("pending") or [])
        if not codes:
            return "Aucun code défaut" + (", et le voyant moteur est éteint." if d.get("mil_on") is False else ".")
        first = codes[0]
        txt = f"{len(codes)} code{'s' if len(codes) > 1 else ''} défaut. " if len(codes) > 1 else "Un code défaut : "
        txt += first["code"]
        if first.get("description"):
            txt += f", {first['description'][0].lower()}{first['description'][1:]}"
        txt += ". C'est une piste, pas un verdict : il faut contrôler avant de changer une pièce."
        return txt

    # --- Outils ---------------------------------------------------------------------------------
    def run(self, name: str, args: dict[str, Any] | None, vehicle_id: str) -> dict[str, Any]:
        if name not in TOOLS:
            raise ToolError(f"outil inconnu : {name}")
        args = args or {}
        if name == "vehicle_live":
            keys = args.get("keys") if isinstance(args.get("keys"), list) else None
            data = self.q.live_data(vehicle_id, keys)
            return {"connection": data["connection"],
                    "values": {k: _fmt_value(v) for k, v in data["values"].items()}}
        if name == "vehicle_dtc":
            d = self.q.dtc(vehicle_id)
            if not d.get("available"):
                return {"available": False}
            def brief(x: dict[str, Any]) -> dict[str, Any]:
                # Deux causes et un contrôle suffisent à l'oral ; le détail reste dans le rapport.
                return {"code": x.get("code"), "description": x.get("description"), "severity": x.get("severity"),
                        "causes_possibles": (x.get("causes") or [])[:2], "controle": (x.get("checks") or [])[:1]}
            return {"available": True, "mil_on": d.get("mil_on"), "read_at": d.get("ts"),
                    **{k: [brief(x) for x in (d.get(k) or [])] for k in ("stored", "pending")},
                    "permanent": None if d.get("permanent") is None else [x["code"] for x in d["permanent"]]}
        if name == "vehicle_history":
            key = str(args.get("key") or "")
            if not key:
                raise ToolError("vehicle_history : paramètre 'key' requis")
            minutes = float(args.get("last_minutes") or 15)
            h = self.q.history(vehicle_id, key, last_minutes=min(max(minutes, 1), 240), limit=200)
            vals = [p["value"] for p in h["points"] if p.get("value") is not None]
            if not vals:
                return {"key": key, "n": 0, "note": "aucune mesure sur la période"}
            return {"key": key, "unit": h["unit"], "n": len(vals), "min": min(vals), "max": max(vals),
                    "first": vals[0], "last": vals[-1], "minutes": minutes}
        # vehicle_diagnostic : délégué à l'agent mécanicien, repli sur le moteur de règles.
        if self.mechanic:
            try:
                return self.mechanic(vehicle_id)
            except Exception as exc:  # repli silencieux mais tracé
                fallback = self._rules(vehicle_id)
                fallback["mechanic_error"] = str(exc)[:200]
                return fallback
        return self._rules(vehicle_id)

    def _rules(self, vehicle_id: str) -> dict[str, Any]:
        a = self.q.analyze(vehicle_id, 15)
        return {"source": "moteur de règles overmind_obd",
                "findings": [{k: f.get(k) for k in ("title", "severity", "nature", "confidence", "checks_suggested")}
                             for f in (a.get("findings") or [])][:5],
                "limits": (a.get("limits") or [])[:5],
                "overall_confidence": a.get("overall_confidence")}
