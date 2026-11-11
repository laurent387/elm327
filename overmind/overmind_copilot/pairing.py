"""Appairage de l'autoradio par code à 6 chiffres (un jeton de 43 caractères ne se tape pas en voiture).

    python -m overmind_copilot.pair          # affiche un code valable 10 min (via la télécommande MCP)

Le code n'existe qu'en mémoire du service copilote, sert UNE fois, expire après 10 minutes et est
annulé après 5 essais faux (tous codes confondus) ; chaque essai faux coûte 2 s à l'appelant. Un
code valide crée un nouveau jeton de portée `copilot` pour cet autoradio, envoyé une seule fois
dans la WebSocket chiffrée et enregistré dans l'appli (Keystore Android).
"""

from __future__ import annotations

import re
import secrets
import time
from dataclasses import dataclass

CODE_TTL_S = 10 * 60
MAX_FAILURES = 5
CODE_RE = re.compile(r"^\d{6}$")


@dataclass
class _Code:
    code: str
    expires: float


class Pairing:
    def __init__(self):
        self._current: _Code | None = None
        self.failures = 0

    def new_code(self) -> tuple[str, float]:
        """Nouveau code (remplace le précédent) ; renvoie (code, expiration epoch)."""
        code = f"{secrets.randbelow(10**6):06d}"
        self._current = _Code(code, time.time() + CODE_TTL_S)
        self.failures = 0
        return code, self._current.expires

    @property
    def active(self) -> bool:
        return self._current is not None and self._current.expires > time.time()

    def consume(self, code: str | None) -> bool:
        """True si le code est le bon (il est alors consommé) ; sinon compte un échec."""
        cur = self._current
        if cur is None or cur.expires < time.time():
            self._current = None
            return False
        if code and CODE_RE.match(code) and secrets.compare_digest(code, cur.code):
            self._current = None
            return True
        self.failures += 1
        if self.failures >= MAX_FAILURES:
            self._current = None      # trop d'essais : il faut redemander un code
        return False
