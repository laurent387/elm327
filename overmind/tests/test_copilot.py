"""Copilote « Kitt » : cerveau, garde-fous, outils lecture seule et serveur WebSocket.

Le modèle est simulé (pas d'Ollama, pas de Vosk/Piper) : on teste la logique.
"""

from __future__ import annotations

import asyncio
import json
import re

import pytest

from overmind_copilot import AGENT_DIR
from overmind_copilot.brain import CopilotBrain, Session, extract_json, make_mechanic
from overmind_copilot.tools import TOOLS, VehicleTools
from overmind_obd.ingest import Ingestor
from overmind_obd.queries import ObdQueries
from overmind_obd.tokens import TokenStore

BACKEND = {"name": "mistral-ge75", "base_url": "http://x", "model": "m"}


class FakeModel:
    """Renvoie les réponses programmées dans l'ordre et garde les prompts reçus."""

    def __init__(self, *replies):
        self.replies = list(replies)
        self.prompts: list[str] = []

    def __call__(self, base_url, model, prompt):
        if prompt.startswith('Réponds {"ok"'):      # préchauffage du serveur
            return '{"ok": true}'
        self.prompts.append(prompt)
        r = self.replies.pop(0) if self.replies else {"intent": "chat", "speech": "ok"}
        return r if isinstance(r, str) else json.dumps(r, ensure_ascii=False)


@pytest.fixture
def queries(store, registry, msgs):
    ing = Ingestor(store, registry)
    ing.handle(msgs.topic("status"), msgs.status("connected", elm={"version": "ELM327 v1.5"}, vin="WDB171"))
    ing.handle(msgs.topic("telemetry"), msgs.telemetry({"rpm": 2010, "coolant_temp_c": 88, "speed_kmh": 0}))
    ing.handle(msgs.topic("dtc"), msgs.dtc(stored=["P0171"]))
    return ObdQueries(store, registry, lost_after_s=10**9)


def brain(queries, model, **kw):
    return CopilotBrain(VehicleTools(queries, kw.pop("mechanic", None)), "Kitt", model, lambda: BACKEND, **kw)


def test_no_vehicle_write_tool_exists():
    assert set(TOOLS) == {"vehicle_live", "vehicle_dtc", "vehicle_history", "vehicle_diagnostic"}
    for t in TOOLS:
        assert not re.search(r"clear|erase|efface|write|reset|actuat|set_", t)
    schema = json.loads((AGENT_DIR / "output_schema.json").read_text())
    assert set(schema["properties"]["action"]["properties"]["type"]["enum"]) == {"media", "navigate", "call"}


@pytest.mark.parametrize("utt", [
    "efface les codes défaut", "Kitt supprime le défaut P0171", "réinitialise le voyant moteur",
    "lance un test d'actionneur sur l'injecteur", "désactive le FAP", "reprogramme le calculateur",
])
def test_vehicle_write_refused_without_model(queries, utt):
    model = FakeModel()
    r = brain(queries, model).handle(Session("slk_r171"), utt)
    assert r.intent == "refuse" and r.action is None
    assert model.prompts == []          # le modèle n'est même pas consulté


def test_context_contains_measured_values_only(queries):
    model = FakeModel({"intent": "vehicle", "speech": "Deux mille tours."})
    r = brain(queries, model).handle(Session("slk_r171"), "comment va la voiture aujourd'hui ?")
    assert r.text == "Deux mille tours." and r.model == "mistral-ge75"
    p = model.prompts[0]
    assert "rpm = 2010" in p and "P0171" in p and "Tu es **Kitt**" in p
    assert "boost_kpa_relative" not in p      # jamais mesuré → absent, pas inventé


def test_tool_round_then_answer(queries):
    model = FakeModel({"intent": "vehicle", "speech": "", "tool": "vehicle_dtc", "tool_args": {}},
                      {"intent": "vehicle", "speech": "Un code P0171, mélange pauvre."})
    r = brain(queries, model).handle(Session("slk_r171"), "pourquoi j'ai un défaut ?")
    assert r.tools_used == ["vehicle_dtc"] and "P0171" in r.text
    assert "RÉSULTAT OUTIL vehicle_dtc" in model.prompts[1] and "Prise d'air" in model.prompts[1]


def test_tool_rounds_are_bounded(queries):
    loop = {"intent": "vehicle", "speech": "", "tool": "vehicle_live", "tool_args": {}}
    model = FakeModel(loop, loop, loop, loop, loop)
    r = brain(queries, model).handle(Session("slk_r171"), "boucle")
    assert len(r.tools_used) == 2 and len(model.prompts) == 3
    assert r.text  # jamais de silence


def test_invalid_or_chatty_model_output(queries):
    s = Session("slk_r171")
    model = FakeModel('Voici : {"intent": "chat", "speech": "Salut !"} voilà',
                      "Bonjour, je réponds sans JSON.",
                      {"intent": "media", "speech": "Coupé.", "action": {"type": "engine", "command": "stop"}})
    b = brain(queries, model)
    assert b.handle(s, "salut").text == "Salut !"
    r = b.handle(s, "bonjour")
    assert r.text == "Bonjour, je réponds sans JSON." and r.action is None
    r = b.handle(s, "coupe le moteur")     # action hors liste blanche → jetée
    assert r.action is None


@pytest.mark.parametrize("utt,cmd", [
    ("mets la musique en pause", "pause"), ("mais la musique en pause", "pause"), ("pause", "pause"),
    ("coupe la musique", "pause"), ("relance la musique", "play"), ("chanson suivante", "next"),
    ("morceau précédent", "previous"), ("monte le son", "volume_up"), ("baisse le volume", "volume_down"),
])
def test_media_shortcut_without_model(queries, utt, cmd):
    model = FakeModel()
    r = brain(queries, model).handle(Session("slk_r171"), utt)
    assert r.action == {"type": "media", "command": cmd} and model.prompts == []


@pytest.mark.parametrize("utt", ["la température monte ?", "la tension baisse", "est-ce que le turbo monte bien en pression"])
def test_no_media_false_positive(queries, utt):
    model = FakeModel({"intent": "vehicle", "speech": "ok"})
    r = brain(queries, model).handle(Session("slk_r171"), utt)
    assert r.action is None and r.intent != "media"


def test_navigation_action(queries):
    model = FakeModel({"intent": "navigate", "speech": "C'est parti.",
                       "action": {"type": "navigate", "destination": "Gare de Lyon, Paris"}})
    b, s = brain(queries, model), Session("slk_r171")
    assert b.handle(s, "emmène-moi gare de Lyon").action == {"type": "navigate", "destination": "Gare de Lyon, Paris"}


def test_stale_values_never_presented_as_live(store, registry, msgs):
    ing = Ingestor(store, registry)       # données de 2026-11-10 16:00 → très anciennes
    ing.handle(msgs.topic("telemetry"), msgs.telemetry({"rpm": 2010}))
    summary = VehicleTools(ObdQueries(store, registry)).summary("slk_r171")
    assert "EN DIRECT" not in summary and "AUCUNE" in summary and "ANCIENNES" in summary and "rpm = 2010" in summary


def test_call_requires_spoken_confirmation(queries):
    model = FakeModel({"intent": "call", "speech": "J'appelle.", "action": {"type": "call", "contact": "Maman"}},
                      {"intent": "call", "speech": "J'appelle.", "action": {"type": "call", "contact": "Paul"}})
    b, s = brain(queries, model), Session("slk_r171")
    r = b.handle(s, "appelle maman")
    assert r.action is None and "Dis oui" in r.text and s.pending_call == "Maman"
    r = b.handle(s, "oui")
    assert r.action == {"type": "call", "contact": "Maman"} and s.pending_call is None
    r = b.handle(s, "appelle Paul")
    assert r.action is None
    r = b.handle(s, "non")
    assert r.action is None and "annule" in r.text


def test_history_kept_short(queries):
    model = FakeModel(*[{"intent": "chat", "speech": f"r{i}"} for i in range(10)])
    b, s = brain(queries, model), Session("slk_r171")
    for i in range(10):
        b.handle(s, f"q{i}")
    # la 10e requête porte les 6 tours précédents (q3…q8), pas plus
    assert len(s.history) == 6 and "q2\n" not in model.prompts[-1] and "q3\n" in model.prompts[-1]


def test_diagnostic_delegates_to_mechanic(queries):
    mech_model = FakeModel({"agent": "OvermindMechanic", "summary": "Mélange pauvre probable.", "severity": "warning",
                            "hypotheses": [{"cause": "Prise d'air", "confidence": 0.4}], "checks": ["Écouter un sifflement"],
                            "limits": [], "safety_advice": None})
    mech = make_mechanic(queries, mech_model, lambda: BACKEND)
    out = VehicleTools(queries, mech).run("vehicle_diagnostic", {}, "slk_r171")
    assert out["source"] == "agent mécanicien" and out["hypotheses"][0]["cause"] == "Prise d'air"
    assert "Overmind Mechanic" in mech_model.prompts[0]
    # mécanicien non conforme → repli sur le moteur de règles, sans inventer
    bad = make_mechanic(queries, FakeModel("pas du json"), lambda: BACKEND)
    out = VehicleTools(queries, bad).run("vehicle_diagnostic", {}, "slk_r171")
    assert out["source"] == "moteur de règles overmind_obd" and "mechanic_error" in out


def test_extract_json_nested():
    assert extract_json('x {"a": {"b": "}"}, "c": 1} y') == {"a": {"b": "}"}, "c": 1}
    assert extract_json("rien") is None


# --- Serveur WebSocket ---------------------------------------------------------------------------

class FakeSTT:
    def transcribe(self, pcm, rate=16000):
        return "quel est le régime" if pcm else ""


class ChatSTT:
    def transcribe(self, pcm, rate=16000):
        return "comment va la voiture" if pcm else ""


class FakeTTS:
    def wav(self, text):
        return b"RIFF" + text.encode()


def test_websocket_protocol(queries, registry, tmp_path):
    from websockets.asyncio.client import connect
    from websockets.asyncio.server import serve

    from overmind_copilot.server import CopilotServer

    tokens = TokenStore(tmp_path / "t.json", env_tokens="")
    good = tokens.create("autoradio", ["copilot"])
    api_only = tokens.create("dash", ["api"])
    model = FakeModel({"intent": "vehicle", "speech": "Deux mille tours."})
    srv = CopilotServer(brain(queries, model), tokens, registry, ChatSTT(), FakeTTS())

    async def scenario():
        async with serve(srv.handler, "127.0.0.1", 0) as s:
            port = s.sockets[0].getsockname()[1]
            url = f"ws://127.0.0.1:{port}/copilot"
            # jeton d'une autre portée → refusé
            async with connect(url) as ws:
                await ws.send(json.dumps({"type": "hello", "token": api_only, "vehicle_id": "slk_r171"}))
                with pytest.raises(Exception):
                    await ws.recv()
            async with connect(url) as ws:
                await ws.send(json.dumps({"type": "hello", "token": good, "device_id": "hu", "vehicle_id": "slk_r171"}))
                welcome = json.loads(await ws.recv())
                assert welcome["type"] == "welcome" and welcome["assistant"] == "Kitt"
                await ws.send(json.dumps({"type": "utterance_start", "id": "u1", "sample_rate": 16000, "context": {}}))
                await ws.send(b"\x00\x01" * 1600)
                await ws.send(json.dumps({"type": "utterance_end", "id": "u1"}))
                got, audio = {}, None
                while "done" not in got:
                    m = await ws.recv()
                    if isinstance(m, bytes):
                        audio = m
                    else:
                        d = json.loads(m)
                        got[d["type"]] = d
                assert got["transcript"]["text"] == "comment va la voiture"
                assert got["reply"]["text"] == "Deux mille tours." and got["reply"]["action"] is None
                assert got["audio"]["bytes"] == len(audio) and audio.startswith(b"RIFF")
                assert set(got["done"]["timings"]) == {"stt_ms", "llm_ms", "tts_ms", "total_ms"}

    asyncio.run(scenario())


@pytest.mark.parametrize("transcript,ok,rest", [
    ("kit quel est le régime moteur", True, "quel est le régime moteur"),
    ("kit", True, ""),
    ("et quittes mais la radio", True, "mais la radio"),       # « Hé Kitt, mets la radio »
    ("hé kitt, la température ?", True, "la température"),
    ("quitte l'autoroute à la prochaine sortie", False, None),
    ("qui t'a dit ça", False, None),
    ("on a pris le kit de secours", False, None),
])
def test_wake_confirmation(transcript, ok, rest):
    from overmind_copilot.brain import check_wake
    got_ok, got_rest = check_wake(transcript)
    assert got_ok is ok
    if ok:
        assert got_rest == rest


def test_false_wake_is_silent(queries, registry, tmp_path):
    from websockets.asyncio.client import connect
    from websockets.asyncio.server import serve

    from overmind_copilot.server import CopilotServer

    class QuitteSTT:
        def transcribe(self, pcm, rate=16000):
            return "quitte l'autoroute à la prochaine sortie"

    tokens = TokenStore(tmp_path / "t.json", env_tokens="")
    tok = tokens.create("autoradio", ["copilot"])
    model = FakeModel()
    srv = CopilotServer(brain(queries, model), tokens, registry, QuitteSTT(), FakeTTS())

    async def scenario():
        async with serve(srv.handler, "127.0.0.1", 0) as s:
            async with connect(f"ws://127.0.0.1:{s.sockets[0].getsockname()[1]}/copilot") as ws:
                await ws.send(json.dumps({"type": "hello", "token": tok, "device_id": "hu", "vehicle_id": "slk_r171"}))
                await ws.recv()
                await ws.send(json.dumps({"type": "utterance_start", "id": "u1", "wake": True, "context": {}}))
                await ws.send(b"\x00\x01" * 1600)
                await ws.send(json.dumps({"type": "utterance_end", "id": "u1"}))
                types = []
                while "done" not in types:
                    m = await ws.recv()
                    types.append("binary" if isinstance(m, bytes) else json.loads(m)["type"])
                assert types == ["transcript", "ignored", "done"]       # ni réponse ni audio
                assert model.prompts == []

    asyncio.run(scenario())


# --- Lectures factuelles sans modèle ---------------------------------------------------------------

def _now_offset():
    from datetime import datetime, timezone
    from conftest import T0
    return (datetime.now(timezone.utc) - T0).total_seconds()


def test_fact_answer_live_value(store, registry, msgs):
    ing = Ingestor(store, registry)
    t = _now_offset()
    ing.handle(msgs.topic("health"), msgs.health("online", t=t))
    ing.handle(msgs.topic("telemetry"), msgs.telemetry({"rpm": 2013.0, "control_module_v": 14.08}, t=t))
    model = FakeModel()
    b, s = brain(ObdQueries(store, registry), model), Session("slk_r171")
    assert b.handle(s, "Kitt, quel est le régime moteur ?").text == "Le moteur tourne à 2010 tours par minute."
    assert b.handle(s, "et la batterie ?").text == "La tension est de 14,1 volts."
    assert b.handle(s, "il reste combien d'essence ?").text == "Ta voiture ne fournit pas cette mesure."
    assert model.prompts == []


def test_fact_answer_never_presents_old_value_as_live(queries):
    model = FakeModel({"intent": "vehicle", "speech": "Le moteur tourne à zéro tours."})
    r = brain(queries, model).handle(Session("slk_r171"), "quel est le régime moteur ?")
    assert model.prompts == []
    assert "n'arrive pas en ce moment" in r.text or "n'envoie rien" in r.text
    assert "dernière valeur, il y a" in r.text and "2010" in r.text


def test_fact_answer_dtc(queries):
    model = FakeModel()
    r = brain(queries, model).handle(Session("slk_r171"), "est-ce que j'ai des codes défaut ?")
    assert r.text.startswith("Un code défaut : P0171") and "pas un verdict" in r.text and model.prompts == []


def test_explanations_go_to_the_model(queries):
    model = FakeModel({"intent": "vehicle", "speech": "Explication."})
    brain(queries, model).handle(Session("slk_r171"), "pourquoi le régime est si haut ?")
    assert len(model.prompts) == 1


@pytest.mark.parametrize("utt,dest", [
    ("Emmène-moi à la gare de Lyon", "la gare de Lyon"), ("kitt conduis-moi chez Paul", "Paul"),
    ("itinéraire vers Orly", "Orly"), ("on va au Mans", "Le Mans"),
])
def test_navigation_shortcut(queries, utt, dest):
    model = FakeModel()
    r = brain(queries, model).handle(Session("slk_r171"), utt)
    assert r.action == {"type": "navigate", "destination": dest} and model.prompts == []


def test_misplaced_destination_is_recovered(queries):
    model = FakeModel({"intent": "navigate", "speech": "Je te guide.", "tool": None,
                       "tool_args": {"destination": "gare de Lyon, Paris"}, "action": None})
    r = brain(queries, model).handle(Session("slk_r171"), "j'aimerais aller prendre le train à la gare de Lyon")
    assert r.action == {"type": "navigate", "destination": "gare de Lyon, Paris"}


def test_network_listening_requires_tls(queries, registry, tmp_path):
    from overmind_copilot.server import CopilotServer, run
    srv = CopilotServer(brain(queries, FakeModel()), TokenStore(tmp_path / "t.json", env_tokens=""), registry, None, None)
    with pytest.raises(SystemExit, match="sans TLS"):
        asyncio.run(run(srv, ["127.0.0.1", "192.168.1.35"], 0, tls=None))
