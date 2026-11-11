"""Overmind Copilot (« Kitt ») : assistant vocal de l'autoradio.

Chaîne côté serveur : PCM de l'autoradio → STT Vosk → agent `copilot` (modèles routés par
l'orchestrateur HoverMind : Mistral GE75, repli Qwen HoverBot) → TTS Piper → autoradio.
Lecture seule côté véhicule : aucun outil n'écrit sur le bus OBD.
Protocole : schemas/COPILOT_PROTOCOL.md.
"""

from pathlib import Path

__version__ = "0.1.0"

REPO_ROOT = Path(__file__).resolve().parents[2]
AGENT_DIR = REPO_ROOT / "agents" / "copilot"
MECHANIC_DIR = REPO_ROOT / "agents" / "obd_mechanic"
