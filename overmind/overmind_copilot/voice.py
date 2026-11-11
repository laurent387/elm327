"""STT (Vosk) et TTS (Piper) sur le serveur Overmind, modèles chargés une seule fois.

Modèles repris de HoverBot (/opt/hoverbot/models), copiés sous
~/.local/share/overmind/models. Petit modèle Vosk par défaut (≈ 360 Mo de RAM) ; le grand
(vosk-model-fr-0.22, ≈ 4 Go) se choisit avec OVERMIND_COPILOT_VOSK_MODEL.
"""

from __future__ import annotations

import io
import json
import os
import re
import threading
import wave
from pathlib import Path

MODELS_DIR = Path(os.environ.get("OVERMIND_MODELS_DIR", Path.home() / ".local/share/overmind/models"))
VOSK_MODEL = os.environ.get("OVERMIND_COPILOT_VOSK_MODEL", str(MODELS_DIR / "vosk" / "vosk-model-small-fr-0.22"))
PIPER_MODEL = os.environ.get("OVERMIND_COPILOT_PIPER_MODEL", str(MODELS_DIR / "piper" / "fr_FR-siwis-medium.onnx"))
SAMPLE_RATE = 16000
MAX_UTTERANCE_S = 15


class SpeechToText:
    def __init__(self, model_path: str = VOSK_MODEL):
        import vosk  # import tardif : les tests n'en ont pas besoin
        vosk.SetLogLevel(-1)
        self._vosk = vosk
        self.model = vosk.Model(model_path)

    def transcribe(self, pcm16: bytes, sample_rate: int = SAMPLE_RATE) -> str:
        pcm16 = pcm16[: MAX_UTTERANCE_S * sample_rate * 2]
        rec = self._vosk.KaldiRecognizer(self.model, sample_rate)
        rec.AcceptWaveform(pcm16)
        return json.loads(rec.FinalResult()).get("text", "").strip()


_SPOKEN = [
    (re.compile(r"\btr/min\b"), "tours par minute"),
    (re.compile(r"°\s*C\b"), " degrés"),
    (re.compile(r"\bkm/h\b"), "kilomètres heure"),
    (re.compile(r"\bkPa\b"), "kilopascals"),
    (re.compile(r"\bOBD2?\b"), "O B D"),
    (re.compile(r"\bELM327\b"), "E L M trois cent vingt-sept"),
    (re.compile(r"\bAPI\b"), "A P I"),
    (re.compile(r"[*_`#>|]"), " "),
]


def prepare_text(text: str) -> str:
    """Texte → forme prononçable (même rôle que prepare_text de HoverBot head_tts.py)."""
    text = re.sub(r"\s+", " ", text.strip())
    for rx, rep in _SPOKEN:
        text = rx.sub(rep, text)
    return re.sub(r"\s+", " ", text).strip()


class TextToSpeech:
    def __init__(self, model_path: str = PIPER_MODEL, length_scale: float | None = None):
        from piper import PiperVoice
        from piper.config import SynthesisConfig
        self.voice = PiperVoice.load(model_path)
        ls = length_scale if length_scale is not None else float(os.environ.get("OVERMIND_COPILOT_TTS_SPEED", "1.0"))
        self.syn = SynthesisConfig(length_scale=ls)
        self._lock = threading.Lock()  # la session ONNX n'est pas garantie réentrante

    def wav(self, text: str) -> bytes:
        buf = io.BytesIO()
        with self._lock, wave.open(buf, "wb") as w:
            self.voice.synthesize_wav(prepare_text(text), w, syn_config=self.syn)
        return buf.getvalue()
