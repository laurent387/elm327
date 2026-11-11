"""Affiche un code d'appairage à 6 chiffres pour l'autoradio (service copilote en marche).

    python -m overmind_copilot.pair

Passe par la télécommande MCP locale (127.0.0.1:8789, jeton portée `remote` lu dans
OVERMIND_COPILOT_REMOTE_TOKEN ou ~/.config/overmind/kitt_remote.token).
"""

from __future__ import annotations

import asyncio
import json
import os
from pathlib import Path


async def _code(url: str, token: str) -> dict:
    from mcp import ClientSession
    from mcp.client.streamable_http import streamablehttp_client

    async with streamablehttp_client(url, headers={"Authorization": f"Bearer {token}"}) as (r, w, _):
        async with ClientSession(r, w) as s:
            await s.initialize()
            res = await s.call_tool("headunit_pairing_code", {})
            return json.loads(res.content[0].text)


def main() -> int:
    token = os.environ.get("OVERMIND_COPILOT_REMOTE_TOKEN") or \
        Path(os.environ.get("OVERMIND_COPILOT_REMOTE_TOKEN_FILE",
                            Path.home() / ".config/overmind/kitt_remote.token")).read_text().strip()
    port = os.environ.get("OVERMIND_COPILOT_MCP_PORT", "8789")
    try:
        d = asyncio.run(_code(f"http://127.0.0.1:{port}/mcp", token))
    except Exception as exc:  # service arrêté, jeton refusé…
        print(f"impossible d'obtenir un code (service copilote démarré ?) : {exc}")
        return 1
    print(f"Code d'appairage : {d['code']}   (valable jusqu'à {d['expires']}, une seule fois)")
    print("Réglages Kitt → Serveur : wss://<IP publique>:38097/copilot, champ jeton : ce code.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
