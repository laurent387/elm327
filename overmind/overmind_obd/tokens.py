"""Jetons d'accès API/MCP Overmind OBD (seules les empreintes SHA-256 sont stockées).

    python -m overmind_obd.tokens create dashboard --scopes api
    python -m overmind_obd.tokens create orchestrator --scopes api,mcp
    python -m overmind_obd.tokens list
    python -m overmind_obd.tokens revoke dashboard

Le jeton en clair est affiché UNE seule fois. Fichier : OVERMIND_OBD_TOKENS_FILE ou
~/.config/overmind/obd_tokens.json (droits 600). Variable OVERMIND_OBD_TOKENS : jetons en
clair séparés par des virgules, jamais écrits sur disque (tous scopes).
"""

from __future__ import annotations

import argparse
import hashlib
import hmac
import json
import os
import secrets
from pathlib import Path

SCOPES = {"api", "mcp"}


def sha256_hex(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def default_path() -> Path:
    return Path(os.environ.get("OVERMIND_OBD_TOKENS_FILE", Path.home() / ".config/overmind/obd_tokens.json"))


class TokenStore:
    def __init__(self, path: str | Path | None = None, env_tokens: str | None = None):
        self.path = Path(path) if path else default_path()
        env = os.environ.get("OVERMIND_OBD_TOKENS", "") if env_tokens is None else env_tokens
        self._env = [sha256_hex(t.strip()) for t in env.split(",") if t.strip()]
        self.entries: dict[str, dict] = {}
        if self.path.exists():
            self.entries = json.loads(self.path.read_text(encoding="utf-8")).get("tokens", {})

    @property
    def configured(self) -> bool:
        return bool(self._env or self.entries)

    def check(self, token: str | None, scope: str) -> str | None:
        """Renvoie l'identifiant du jeton s'il est valide pour ce scope, sinon None."""
        if not token:
            return None
        digest = sha256_hex(token)
        for h in self._env:
            if hmac.compare_digest(h, digest):
                return "env"
        for tid, e in self.entries.items():
            if hmac.compare_digest(e["sha256"], digest) and scope in e.get("scopes", []):
                return tid
        return None

    def save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps({"tokens": self.entries}, indent=2), encoding="utf-8")
        os.chmod(tmp, 0o600)
        tmp.replace(self.path)

    def create(self, token_id: str, scopes: list[str]) -> str:
        bad = set(scopes) - SCOPES
        if bad:
            raise ValueError(f"scopes inconnus : {bad}")
        token = secrets.token_urlsafe(32)
        self.entries[token_id] = {"sha256": sha256_hex(token), "scopes": sorted(scopes)}
        self.save()
        return token


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="python -m overmind_obd.tokens")
    ap.add_argument("--file")
    sub = ap.add_subparsers(dest="cmd", required=True)
    c = sub.add_parser("create")
    c.add_argument("token_id")
    c.add_argument("--scopes", default="api")
    sub.add_parser("list")
    r = sub.add_parser("revoke")
    r.add_argument("token_id")
    args = ap.parse_args(argv)
    store = TokenStore(args.file, env_tokens="")
    if args.cmd == "list":
        for tid, e in store.entries.items():
            print(f"{tid:24s} scopes={','.join(e['scopes'])}")
        return 0
    if args.cmd == "revoke":
        store.entries.pop(args.token_id, None)
        store.save()
        return 0
    token = store.create(args.token_id, [s.strip() for s in args.scopes.split(",") if s.strip()])
    print(token)
    print(f"# empreinte enregistrée dans {store.path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
