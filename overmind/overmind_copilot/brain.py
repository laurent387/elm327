"""Cerveau du copilote : agent `copilot` routé par l'orchestrateur HoverMind.

Réutilise ce qui existe dans ~/hovermind/orchestrator :
- model_router.choose_model  → Mistral GE75 si disponible, sinon Qwen HoverBot ;
- model_client.call_ollama   → appel du modèle ;
- agent_runner.DEFAULT_BACKENDS, ge75_health.is_ge75_available.

Garde-fous déterministes (avant et après le modèle) :
- toute demande d'écriture véhicule (effacer les défauts, actionneur, codage…) est refusée
  SANS interroger le modèle ;
- seules les actions autoradio de la liste blanche passent ; un appel téléphonique n'est
  émis qu'après un « oui » explicite ;
- au plus MAX_TOOL_ROUNDS appels d'outils par énoncé.
"""

from __future__ import annotations

import json
import os
import re
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable

import jsonschema

from . import AGENT_DIR, MECHANIC_DIR
from .tools import TOOLS, ToolError, VehicleTools

ORCHESTRATOR_DIR = Path(os.environ.get("OVERMIND_ORCHESTRATOR_DIR", Path.home() / "hovermind" / "orchestrator"))
MAX_TOOL_ROUNDS = 2
HISTORY_TURNS = 6
MEDIA_COMMANDS = {"play", "pause", "toggle", "next", "previous", "volume_up", "volume_down"}

# (base_url, model, prompt) -> texte, comme orchestrator.model_client.call_ollama
ModelCaller = Callable[[str, str, str], str]
# () -> {"name", "base_url", "model"}
BackendChooser = Callable[[], dict[str, str]]

VEHICLE_WRITE = re.compile(
    r"\b(efface\w*|effacer|supprim\w*|réinitialis\w*|reinitialis\w*|remet\w* à zéro|reset|clear)\b.{0,40}"
    r"\b(défauts?|defauts?|codes?|voyants?|erreurs?|dtc|mémoire)\b"
    r"|\b(actionneurs?|test\w* d'actionneur|cod(er|age) (le |du |un )?(calculateur|boîtier|module)"
    r"|reprogramm\w*|cartographie|remap\w*|flash\w* (le |du )?(calculateur|ecu)"
    r"|désactiv\w* (l'|le |la )?(abs|esp|airbag|fap|egr|adblue))\b",
    re.IGNORECASE,
)
# Commandes média reconnues sans le modèle (instantané, et le modèle s'est déjà trompé de sens).
# Tolère les erreurs de transcription courantes (« mais la musique en pause »).
_MEDIA_RULES: list[tuple[re.Pattern[str], str, str]] = [
    (re.compile(r"\b(pause|coupe (la |le )?(musique|son|radio)|arr[êe]te (la |le )?(musique|son|radio)|silence)\b", re.I),
     "pause", "Pause."),
    (re.compile(r"\b(reprends?|relance|remets?|joue|lance) (la |le )?(musique|lecture|son|radio)\b", re.I),
     "play", "C'est reparti."),
    (re.compile(r"\b(morceau|chanson|titre|piste) suivante?\b|\bsuivant\b|\bpasse (à la suivante|au suivant)\b", re.I),
     "next", "Suivant."),
    (re.compile(r"\b(morceau|chanson|titre|piste) précédente?\b|\bprécédent\b", re.I), "previous", "Précédent."),
    (re.compile(r"\b(monte|augmente)\b.{0,10}\b(son|volume|musique)\b|\bplus fort\b", re.I), "volume_up", "Plus fort."),
    (re.compile(r"\b(baisse|diminue)\b.{0,10}\b(son|volume|musique)\b|\bmoins fort\b", re.I), "volume_down", "Moins fort."),
]


_WAKE_FORMS = {"kit", "kitt", "kits"}
_WAKE_LEADS = {"hé", "he", "et", "eh", "ok", "okay", "dis", "salut", "allo", "allô"}
_WAKE_AFTER_LEAD = _WAKE_FORMS | {"quitte", "quittes", "quitter"}


def check_wake(transcript: str, wake_forms: set[str] | None = None) -> tuple[bool, str]:
    """2e vérification du mot de réveil sur la transcription complète (vocabulaire entier).

    Le détecteur de l'autoradio (grammaire réduite) confond « Kitt » avec « quitte » ou
    « qui t'a » ; le vocabulaire complet les distingue. Renvoie (accepté, énoncé sans le mot).
    """
    forms = wake_forms or _WAKE_FORMS
    words = re.sub(r"[,.!?;:]", " ", transcript.lower()).split()
    if words and words[0] in forms:
        return True, " ".join(words[1:])
    if len(words) >= 2 and words[0] in _WAKE_LEADS and (words[1] in forms or words[1] in _WAKE_AFTER_LEAD):
        return True, " ".join(words[2:])
    return False, transcript


def media_shortcut(utterance: str) -> tuple[str, str] | None:
    u = utterance.lower()
    if len(u.split()) > 9:      # phrase longue : laisser le modèle comprendre
        return None
    for rx, cmd, say in _MEDIA_RULES:
        if rx.search(u):
            return cmd, say
    return None


NAVIGATE = re.compile(
    r"^(?:kitt?,?\s+)?(?:emmène|emmene|conduis|guide|ramène|ramene)[- ]moi\s+(?P<p1>à|a|au|aux|chez|vers|jusqu'à)\s+(?P<d1>.+)$"
    r"|^(?:kitt?,?\s+)?(?:itinéraire|itineraire|direction|route)\s+(?:vers|pour|jusqu'à)?\s*(?P<d2>.+)$"
    r"|^(?:kitt?,?\s+)?(?:on va|je vais|va)\s+(?P<p3>à|a|au|aux|chez)\s+(?P<d3>.+)$",
    re.IGNORECASE,
)


def navigate_shortcut(utterance: str) -> str | None:
    m = NAVIGATE.match(utterance.strip().rstrip(" .!?"))
    if not m:
        return None
    dest = next(g for g in (m.group("d1"), m.group("d2"), m.group("d3")) if g).strip()
    prep = (m.group("p1") or m.group("p3") or "").lower()
    if prep in ("au", "aux"):        # « au Mans » → « Le Mans », « aux Halles » → « Les Halles »
        dest = ("Le " if prep == "au" else "Les ") + dest
    return dest if 2 <= len(dest) <= 120 else None


AFFIRMATIVE = re.compile(r"^\s*(oui|ouais|ouai|yes|vas-y|vas y|confirme|confirmé|d'accord|ok|okay|c'est ça|appelle)\b", re.I)
NEGATIVE = re.compile(r"^\s*(non|annule|laisse tomber|stop|pas maintenant)\b", re.I)


def _load_schema() -> dict[str, Any]:
    return json.loads((AGENT_DIR / "output_schema.json").read_text(encoding="utf-8"))


def load_system_prompt(name: str) -> str:
    return (AGENT_DIR / "system_prompt.md").read_text(encoding="utf-8").replace("{name}", name)


def orchestrator_backend_chooser() -> BackendChooser:
    """Branche le routage de modèles HoverMind existant (import par chemin, comme ses tests)."""
    if str(ORCHESTRATOR_DIR) not in sys.path:
        sys.path.insert(0, str(ORCHESTRATOR_DIR))
    from agent_runner import DEFAULT_BACKENDS  # type: ignore
    from ge75_health import is_ge75_available  # type: ignore
    from model_router import choose_model  # type: ignore

    health_url = os.environ.get("OVERMIND_GE75_HEALTH_URL")

    def choose() -> dict[str, str]:
        up = is_ge75_available(health_url) if health_url else is_ge75_available()
        name = choose_model("copilot_agent", up, "medium")
        return {"name": name, **DEFAULT_BACKENDS[name]}

    return choose


def orchestrator_model_caller() -> ModelCaller:
    if str(ORCHESTRATOR_DIR) not in sys.path:
        sys.path.insert(0, str(ORCHESTRATOR_DIR))
    from model_client import call_ollama  # type: ignore
    return call_ollama


def extract_json(raw: str) -> dict[str, Any] | None:
    """Premier objet JSON équilibré de la réponse (les petits modèles bavardent parfois autour)."""
    raw = raw.strip()
    try:
        obj = json.loads(raw)
        return obj if isinstance(obj, dict) else None
    except json.JSONDecodeError:
        pass
    start = raw.find("{")
    while start != -1:
        depth, in_str, esc = 0, False, False
        for i in range(start, len(raw)):
            c = raw[i]
            if in_str:
                esc = (c == "\\") and not esc
                if c == '"' and not esc:
                    in_str = False
                continue
            if c == '"':
                in_str = True
            elif c == "{":
                depth += 1
            elif c == "}":
                depth -= 1
                if depth == 0:
                    try:
                        obj = json.loads(raw[start:i + 1])
                        return obj if isinstance(obj, dict) else None
                    except json.JSONDecodeError:
                        break
        start = raw.find("{", start + 1)
    return None


def clean_speech(text: str, limit: int = 300, max_sentences: int = 3) -> str:
    """Réponse orale : sans markdown, 3 phrases et ~300 caractères au plus (on conduit)."""
    text = re.sub(r"[*_`#]", "", text or "")
    text = re.sub(r"\s+", " ", text).strip()
    sentences = re.split(r"(?<=[.!?])\s+", text)
    text = " ".join(sentences[:max_sentences])
    if len(text) > limit:
        cut = text[:limit]
        end = max(cut.rfind(". "), cut.rfind("! "), cut.rfind("? "), cut.rfind(", "))
        text = (cut[:end] if end > 40 else cut.rsplit(" ", 1)[0]).rstrip(",") + "."
    return text


@dataclass
class Turn:
    user: str
    assistant: str


@dataclass
class Session:
    vehicle_id: str
    history: list[Turn] = field(default_factory=list)
    pending_call: str | None = None


@dataclass
class Reply:
    text: str
    intent: str
    action: dict[str, Any] | None = None
    model: str | None = None
    tools_used: list[str] = field(default_factory=list)
    llm_ms: int = 0


class CopilotBrain:
    def __init__(self, tools: VehicleTools, name: str = "Kitt",
                 model_caller: ModelCaller | None = None, backend_chooser: BackendChooser | None = None,
                 clock: Callable[[], float] = time.time):
        self.tools = tools
        self.name = name
        self.call = model_caller or orchestrator_model_caller()
        self.choose = backend_chooser or orchestrator_backend_chooser()
        self.system_prompt = load_system_prompt(name)
        self.schema = _load_schema()
        self.clock = clock

    # --- Point d'entrée ---------------------------------------------------------------------
    def handle(self, session: Session, utterance: str, context: dict[str, Any] | None = None) -> Reply:
        utterance = (utterance or "").strip()
        if not utterance:
            return Reply("Je n'ai pas compris. Tu peux répéter ?", "unknown")

        # 1. Confirmation d'un appel en attente (jamais d'appel sans « oui »).
        if session.pending_call:
            contact, session.pending_call = session.pending_call, None
            if AFFIRMATIVE.search(utterance):
                return self._remember(session, utterance, Reply(f"J'appelle {contact}.", "call",
                                                                {"type": "call", "contact": contact}))
            if NEGATIVE.search(utterance):
                return self._remember(session, utterance, Reply("D'accord, j'annule l'appel.", "call"))

        # 2. Écriture véhicule : refus déterministe, le modèle n'est pas consulté.
        if VEHICLE_WRITE.search(utterance):
            return self._remember(session, utterance, Reply(
                "Je ne peux pas faire ça à la voix : je n'ai qu'un accès en lecture à la voiture. "
                "Pour effacer les défauts, arrête-toi, coupe le moteur et utilise l'écran Défauts de la passerelle.",
                "refuse"))

        # 3. Commandes média simples : sans le modèle.
        shortcut = media_shortcut(utterance)
        if shortcut:
            cmd, say = shortcut
            return self._remember(session, utterance, Reply(say, "media", {"type": "media", "command": cmd}))

        # 3b. Navigation explicite (« emmène-moi à … ») : sans le modèle.
        dest = navigate_shortcut(utterance)
        if dest:
            return self._remember(session, utterance, Reply(f"C'est parti pour {dest}.", "navigate",
                                                            {"type": "navigate", "destination": dest}))

        # 4. Lecture simple d'une valeur ou des codes défauts : réponse factuelle, sans le modèle.
        fact = self.tools.fact_answer(session.vehicle_id, utterance)
        if fact:
            return self._remember(session, utterance, Reply(fact, "vehicle"))

        # 5. Agent + outils.
        t0 = self.clock()
        backend = self.choose()
        prompt = self._prompt(session, utterance, context)
        tools_used: list[str] = []
        out: dict[str, Any] | None = None
        for _round in range(MAX_TOOL_ROUNDS + 1):
            out = self._ask(backend, prompt)
            tool = out.get("tool")
            if not tool or len(tools_used) >= MAX_TOOL_ROUNDS:
                break
            if tool not in TOOLS:
                result: Any = {"erreur": f"outil inconnu {tool}"}
            else:
                try:
                    result = self.tools.run(tool, out.get("tool_args") or {}, session.vehicle_id)
                except (ToolError, KeyError, ValueError) as exc:
                    result = {"erreur": str(exc)}
            tools_used.append(tool)
            prompt += (f"\n\n## RÉSULTAT OUTIL {tool}\n{json.dumps(result, ensure_ascii=False, default=str)[:4000]}"
                       "\n\nRéponds maintenant définitivement (tool = null), en JSON.")
        llm_ms = int((self.clock() - t0) * 1000)

        assert out is not None
        speech = clean_speech(str(out.get("speech") or ""))
        if not speech:
            speech = "Je n'ai pas de réponse fiable à te donner."
        action = out.get("action") or self._action_from_misplaced_args(out)
        reply = Reply(speech, str(out.get("intent") or "chat"), self._safe_action(action, session),
                      backend.get("name"), tools_used, llm_ms)
        if reply.action and reply.action["type"] == "call":
            # Le modèle ne déclenche jamais l'appel lui-même : on demande confirmation.
            session.pending_call = reply.action["contact"]
            reply.text = f"J'appelle {reply.action['contact']} ? Dis oui pour confirmer."
            reply.action = None
        return self._remember(session, utterance, reply)

    def warmup(self) -> None:
        """Charge le modèle en mémoire (Ollama) dès qu'un autoradio se connecte."""
        backend = self.choose()
        self.call(backend["base_url"], backend["model"], 'Réponds {"ok": true}')

    # --- Interne ------------------------------------------------------------------------------
    def _ask(self, backend: dict[str, str], prompt: str) -> dict[str, Any]:
        raw = self.call(backend["base_url"], backend["model"], prompt)
        obj = extract_json(raw)
        if obj is None:
            # Réponse non JSON : on la dit telle quelle, sans action ni outil.
            return {"intent": "chat", "speech": raw, "tool": None, "action": None}
        try:
            jsonschema.validate(obj, self.schema)
        except jsonschema.ValidationError:
            # On garde la parole, on jette tout ce qui n'est pas conforme.
            return {"intent": "chat", "speech": str(obj.get("speech") or ""),
                    "tool": obj.get("tool") if obj.get("tool") in TOOLS else None,
                    "tool_args": obj.get("tool_args") if isinstance(obj.get("tool_args"), dict) else {},
                    "action": None}
        return obj

    @staticmethod
    def _action_from_misplaced_args(out: dict[str, Any]) -> dict[str, Any] | None:
        """Les petits modèles rangent parfois la destination dans tool_args : on la récupère."""
        args = out.get("tool_args") if isinstance(out.get("tool_args"), dict) else {}
        intent = out.get("intent")
        if intent == "navigate" and args.get("destination"):
            return {"type": "navigate", "destination": args["destination"]}
        if intent == "media" and args.get("command"):
            return {"type": "media", "command": args["command"]}
        if intent == "call" and args.get("contact"):
            return {"type": "call", "contact": args["contact"]}
        return None

    @staticmethod
    def _safe_action(action: Any, session: Session) -> dict[str, Any] | None:
        if not isinstance(action, dict):
            return None
        t = action.get("type")
        if t == "media" and action.get("command") in MEDIA_COMMANDS:
            return {"type": "media", "command": action["command"]}
        if t == "navigate" and isinstance(action.get("destination"), str) and action["destination"].strip():
            return {"type": "navigate", "destination": action["destination"].strip()[:200]}
        if t == "call" and isinstance(action.get("contact"), str) and action["contact"].strip():
            return {"type": "call", "contact": action["contact"].strip()[:80]}
        return None

    def _prompt(self, session: Session, utterance: str, context: dict[str, Any] | None) -> str:
        ctx = context or {}
        parts = [self.system_prompt, "\n## CONTEXTE"]
        parts.append("Heure : " + str(ctx.get("time") or time.strftime("%A %d %B %Y, %H:%M")))
        loc = ctx.get("location") or {}
        if loc.get("lat") is not None:
            parts.append(f"Position GPS : {loc.get('lat'):.5f}, {loc.get('lon'):.5f}"
                         + (f", vitesse GPS {round(loc['speed_kmh'])} km/h" if loc.get("speed_kmh") is not None else "")
                         + (f", adresse approximative : {loc['address']}" if loc.get("address") else ""))
        else:
            parts.append("Position GPS : indisponible")
        media = ctx.get("media") or {}
        if media:
            parts.append("Musique : " + ("en lecture" if media.get("playing") else "à l'arrêt")
                         + (f" — {media.get('title')}" if media.get("title") else "")
                         + (f" ({media.get('artist')})" if media.get("artist") else ""))
        parts.append(self.tools.summary(session.vehicle_id))
        if session.history:
            parts.append("\n## CONVERSATION RÉCENTE")
            for t in session.history[-HISTORY_TURNS:]:
                parts.append(f"Conducteur : {t.user}\n{self.name} : {t.assistant}")
        parts.append(f"\n## CONDUCTEUR\n{utterance}\n\nRéponds en JSON.")
        return "\n".join(parts)

    @staticmethod
    def _remember(session: Session, utterance: str, reply: Reply) -> Reply:
        session.history.append(Turn(utterance, reply.text))
        del session.history[:-HISTORY_TURNS]
        return reply


def make_mechanic(queries, model_caller: ModelCaller, backend_chooser: BackendChooser):
    """Délègue le diagnostic à l'agent obd_mechanic existant (même routage de modèles)."""
    system = (MECHANIC_DIR / "system_prompt.md").read_text(encoding="utf-8")

    def mechanic(vehicle_id: str) -> dict[str, Any]:
        analysis = queries.analyze(vehicle_id, 15)
        dtc = queries.dtc(vehicle_id)
        backend = backend_chooser()
        prompt = (f"{system}\n\n## Tâche\naction=analyze_engine vehicle_id={vehicle_id}\n"
                  f"analyse_regles={json.dumps(analysis, ensure_ascii=False, default=str)[:6000]}\n"
                  f"dtc={json.dumps(dtc, ensure_ascii=False, default=str)[:3000]}\n\n"
                  "Réponds uniquement avec un objet JSON conforme au schéma.")
        out = extract_json(model_caller(backend["base_url"], backend["model"], prompt))
        if not out or "summary" not in out:
            raise ValueError("sortie mécanicien non conforme")
        hyps = sorted(out.get("hypotheses") or [], key=lambda h: -float(h.get("confidence") or 0))[:3]
        return {"source": "agent mécanicien", "summary": out.get("summary"), "severity": out.get("severity"),
                "safety_advice": out.get("safety_advice"),
                "hypotheses": [{"cause": h.get("cause"), "confidence": h.get("confidence")} for h in hyps],
                "checks": (out.get("checks") or [])[:3], "limits": (out.get("limits") or [])[:3]}

    return mechanic
