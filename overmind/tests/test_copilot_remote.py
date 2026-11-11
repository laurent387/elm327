"""Télécommande de l'autoradio : canal par la WebSocket sortante, outils MCP, mise à jour d'APK.

Un faux autoradio répond aux commandes `remote` ; les outils MCP sont appelés en mémoire.
"""

from __future__ import annotations

import asyncio
import base64
import hashlib
import json

import pytest

from overmind_copilot.remote import COMMANDS, RemoteError, RemoteHub
from overmind_obd.tokens import TokenStore
from test_copilot import FakeModel, FakeTTS, brain, queries  # noqa: F401  (fixture)

JPEG = b"\xff\xd8\xff\xe0fake-jpeg"


def make_server(queries, registry, tmp_path, update_dir=None):
    from overmind_copilot.server import CopilotServer
    tokens = TokenStore(tmp_path / "t.json", env_tokens="")
    tok = tokens.create("autoradio", ["copilot"])
    srv = CopilotServer(brain(queries, FakeModel()), tokens, registry, None, FakeTTS(),
                        RemoteHub(update_dir or tmp_path / "apk"))
    return srv, tok


async def fake_headunit(url, tok, remote=True, seen=None):
    """Autoradio simulé : exécute la liste fermée de commandes comme l'appli."""
    from websockets.asyncio.client import connect
    seen = seen if seen is not None else []
    ws = await connect(url)
    await ws.send(json.dumps({"type": "hello", "token": tok, "device_id": "hu", "vehicle_id": "slk_r171",
                              "app_version": "0.3.0", "remote": remote}))
    assert json.loads(await ws.recv())["type"] == "welcome"

    async def loop():
        async for raw in ws:
            if isinstance(raw, bytes):
                seen.append(("binary", raw))
                continue
            m = json.loads(raw)
            seen.append((m["type"], m))
            if m["type"] != "remote":
                continue
            cmd, data = m["cmd"], None
            if cmd == "status":
                data = {"app": {"version": "0.3.0"}, "kitt": {"state": "IDLE"}}
            elif cmd == "screenshot":
                data = {"jpeg_base64": base64.b64encode(JPEG).decode(), "width": 1024, "height": 600}
            elif cmd == "media":
                data = {"done": m["args"]["command"]}
            res = {"type": "remote_result", "id": m["id"], "ok": data is not None}
            res.update({"data": data} if data is not None else {"error": f"non géré : {cmd}"})
            await ws.send(json.dumps(res))

    return ws, asyncio.create_task(loop())


def run_with(srv, scenario):
    from websockets.asyncio.server import serve

    async def main():
        async with serve(srv.handler, "127.0.0.1", 0, process_request=srv.process_request) as s:
            port = s.sockets[0].getsockname()[1]
            await scenario(f"ws://127.0.0.1:{port}/copilot", port)

    asyncio.run(main())


def tool_payload(result):
    """FastMCP.call_tool → contenu (liste de blocs) ou (contenu, structuré)."""
    blocks = result[0] if isinstance(result, tuple) else result
    return blocks


def test_commands_are_a_closed_list_without_vehicle_access():
    assert set(COMMANDS) == {"status", "display", "logs", "screenshot", "list_apps", "media", "launch_app",
                             "restart", "update"}
    for word in ("obd", "dtc", "clear", "shell", "exec", "write", "can"):
        assert not any(word in c for c in COMMANDS)


def test_mcp_tools_have_no_vehicle_write(queries, registry, tmp_path):
    from overmind_copilot.remote_mcp import build_remote_mcp
    srv, _ = make_server(queries, registry, tmp_path)
    names = {t.name for t in asyncio.run(build_remote_mcp(srv).list_tools())}
    assert names == {"headunit_list", "headunit_status", "headunit_display", "headunit_logs", "headunit_screenshot",
                     "headunit_list_apps", "headunit_say", "headunit_media", "headunit_launch_app",
                     "headunit_restart_kitt", "headunit_install_update", "headunit_pairing_code"}
    assert not any(w in n for n in names for w in ("obd", "dtc", "clear", "shell", "exec"))


def test_remote_roundtrip_through_mcp(queries, registry, tmp_path):
    from overmind_copilot.remote_mcp import build_remote_mcp
    srv, tok = make_server(queries, registry, tmp_path)
    mcp = build_remote_mcp(srv)

    async def scenario(url, port):
        ws, task = await fake_headunit(url, tok)
        try:
            listed = json.loads(tool_payload(await mcp.call_tool("headunit_list", {}))[0].text)
            assert [d["device_id"] for d in listed["connected"]] == ["hu"]
            status = json.loads(tool_payload(await mcp.call_tool("headunit_status", {}))[0].text)
            assert status["kitt"]["state"] == "IDLE"
            img = tool_payload(await mcp.call_tool("headunit_screenshot", {}))[0]
            assert img.type == "image" and img.mimeType == "image/jpeg" and base64.b64decode(img.data) == JPEG
            res = json.loads(tool_payload(await mcp.call_tool("headunit_media", {"command": "pause"}))[0].text)
            assert res == {"done": "pause"}
            bad = json.loads(tool_payload(await mcp.call_tool("headunit_media", {"command": "clear_dtc"}))[0].text)
            assert "error" in bad
            # commande non gérée par l'autoradio → erreur remontée, pas d'exception
            err = json.loads(tool_payload(await mcp.call_tool("headunit_display", {}))[0].text)
            assert "non géré" in err["error"]
        finally:
            task.cancel()
            await ws.close()

    run_with(srv, scenario)


def test_say_sends_reply_then_audio(queries, registry, tmp_path):
    srv, tok = make_server(queries, registry, tmp_path)
    seen = []

    async def scenario(url, port):
        ws, task = await fake_headunit(url, tok, seen=seen)
        try:
            res = await srv.say(None, "  Bonjour   Laurent  ")
            assert res["text"] == "Bonjour Laurent"
            for _ in range(50):
                if any(t == "binary" for t, _ in seen):
                    break
                await asyncio.sleep(0.02)
        finally:
            task.cancel()
            await ws.close()
        types = [t for t, _ in seen]
        assert types == ["reply", "audio", "binary"]      # pas de `done` : aucune latence affichée
        assert seen[0][1]["intent"] == "remote_say" and seen[0][1]["action"] is None
        assert seen[2][1] == b"RIFFBonjour Laurent"

    run_with(srv, scenario)


def test_remote_refused_when_disabled_on_headunit(queries, registry, tmp_path):
    srv, tok = make_server(queries, registry, tmp_path)

    async def scenario(url, port):
        ws, task = await fake_headunit(url, tok, remote=False)
        try:
            with pytest.raises(RemoteError, match="désactivée"):
                await srv.remote.command(None, "status")
            with pytest.raises(RemoteError, match="désactivée"):
                await srv.say(None, "coucou")
        finally:
            task.cancel()
            await ws.close()

    run_with(srv, scenario)


def test_no_headunit_and_unknown_command(queries, registry, tmp_path):
    srv, _ = make_server(queries, registry, tmp_path)

    async def go():
        with pytest.raises(RemoteError, match="aucun autoradio"):
            await srv.remote.command(None, "status")
        with pytest.raises(RemoteError, match="inconnue"):
            await srv.remote.command(None, "shell", {"cmd": "reboot"})

    asyncio.run(go())


def test_disconnect_fails_pending_and_unregisters(queries, registry, tmp_path):
    from websockets.asyncio.client import connect
    srv, tok = make_server(queries, registry, tmp_path)

    async def scenario(url, port):
        ws = await connect(url)
        await ws.send(json.dumps({"type": "hello", "token": tok, "device_id": "hu", "vehicle_id": "slk_r171",
                                  "remote": True}))
        await ws.recv()
        pending = asyncio.create_task(srv.remote.command(None, "status"))
        await ws.recv()            # la commande arrive… et l'autoradio se déconnecte sans répondre
        await ws.close()
        with pytest.raises(RemoteError, match="déconnecté"):
            await pending
        assert srv.remote.links == {}

    run_with(srv, scenario)


def test_update_pulled_in_chunks_over_websocket(queries, registry, tmp_path, monkeypatch):
    import overmind_copilot.remote as remote_mod
    from websockets.asyncio.client import connect
    monkeypatch.setattr(remote_mod, "UPDATE_CHUNK", 1000)
    apk_dir = tmp_path / "apk" / "debug"
    apk_dir.mkdir(parents=True)
    apk = apk_dir / "app-headunit-debug.apk"
    apk.write_bytes(b"PK\x03\x04" + bytes(range(256)) * 20)        # 5124 octets → 6 morceaux
    outside = tmp_path / "evil.apk"
    outside.write_bytes(b"PK")
    srv, tok = make_server(queries, registry, tmp_path)
    from overmind_copilot.remote_mcp import build_remote_mcp
    mcp = build_remote_mcp(srv)

    async def scenario(url, port):
        ws = await connect(url)
        await ws.send(json.dumps({"type": "hello", "token": tok, "device_id": "hu", "vehicle_id": "slk_r171",
                                  "remote": True}))
        await ws.recv()
        refused = json.loads(tool_payload(await mcp.call_tool("headunit_install_update", {"apk": str(outside)}))[0].text)
        assert "refusé" in refused["error"]
        call = asyncio.create_task(mcp.call_tool("headunit_install_update", {}))
        order = json.loads(await ws.recv())
        assert order["cmd"] == "update"
        args = order["args"]
        await ws.send(json.dumps({"type": "remote_result", "id": order["id"], "ok": True, "data": {"accepted": True}}))
        res = json.loads(tool_payload(await call)[0].text)
        assert res["accepted"] and res["apk"] == apk.name
        got, offset, n = bytearray(), 0, 0
        while True:
            await ws.send(json.dumps({"type": "update_get", "id": args["nonce"], "offset": offset}))
            meta = json.loads(await ws.recv())
            assert meta["type"] == "update_data" and meta["offset"] == offset
            data = await ws.recv()
            assert isinstance(data, bytes) and len(data) == meta["bytes"]
            got += data; offset += len(data); n += 1
            if meta["eof"]:
                break
        assert bytes(got) == apk.read_bytes() and n == 6
        assert hashlib.sha256(got).hexdigest() == args["sha256"] and args["size"] == len(got)
        # nonce consommé après la fin, nonce inventé, position invalide → erreur sans trame binaire
        for nonce, off in ((args["nonce"], 0), ("devine", 0)):
            await ws.send(json.dumps({"type": "update_get", "id": nonce, "offset": off}))
            assert "error" in json.loads(await ws.recv())
        nonce2, _ = srv.remote.stage_update()
        await ws.send(json.dumps({"type": "update_get", "id": nonce2, "offset": 10**9}))
        assert "error" in json.loads(await ws.recv())
        await ws.close()

    run_with(srv, scenario)


def test_mcp_requires_remote_scope_token(tmp_path):
    from overmind_copilot.remote_mcp import remote_token_configured
    tokens = TokenStore(tmp_path / "t.json", env_tokens="")
    tokens.create("autoradio", ["copilot"])
    assert not remote_token_configured(tokens)
    tokens.create("claude", ["remote"])
    assert remote_token_configured(tokens)
    assert tokens.check(tokens.create("x", ["copilot"]), "remote") is None


# --- Appairage par code à 6 chiffres ----------------------------------------------------------------

def test_pairing_code_rules(monkeypatch):
    import overmind_copilot.pairing as pm
    p = pm.Pairing()
    assert not p.consume("123456")                    # aucun code émis
    code, _ = p.new_code()
    assert len(code) == 6 and code.isdigit()
    assert not p.consume("abcdef") and p.active
    assert p.consume(code) and not p.active           # usage unique
    assert not p.consume(code)
    code, _ = p.new_code()
    for _ in range(pm.MAX_FAILURES):
        p.consume("000000" if code != "000000" else "111111")
    assert not p.active and not p.consume(code)       # trop d'essais : code annulé
    code, _ = p.new_code()
    monkeypatch.setattr(pm.time, "time", lambda: 10**12)
    assert not p.consume(code)                        # expiré


def test_pairing_issues_copilot_token(queries, registry, tmp_path, monkeypatch):
    import overmind_copilot.server as server_mod
    from websockets.asyncio.client import connect
    monkeypatch.setattr(server_mod, "AUTH_FAIL_DELAY_S", 0)
    srv, _ = make_server(queries, registry, tmp_path)
    from overmind_copilot.remote_mcp import build_remote_mcp
    mcp = build_remote_mcp(srv)

    async def scenario(url, port):
        code = json.loads(tool_payload(await mcp.call_tool("headunit_pairing_code", {}))[0].text)["code"]
        bad = "000000" if code != "000000" else "111111"
        async with connect(url) as ws:                  # mauvais code → refusé
            await ws.send(json.dumps({"type": "hello", "pairing_code": bad, "device_id": "hu", "vehicle_id": "slk_r171"}))
            with pytest.raises(Exception):
                await ws.recv()
        async with connect(url) as ws:
            await ws.send(json.dumps({"type": "hello", "pairing_code": code, "device_id": "hu", "vehicle_id": "slk_r171"}))
            paired = json.loads(await ws.recv())
            assert paired["type"] == "paired" and len(paired["token"]) >= 40
            assert json.loads(await ws.recv())["type"] == "welcome"
        token = paired["token"]
        assert srv.tokens.check(token, "copilot") and not srv.tokens.check(token, "remote")
        assert TokenStore(tmp_path / "t.json", env_tokens="").check(token, "copilot")   # écrit sur disque
        async with connect(url) as ws:                  # le code ne resert pas
            await ws.send(json.dumps({"type": "hello", "pairing_code": code, "device_id": "hu", "vehicle_id": "slk_r171"}))
            with pytest.raises(Exception):
                await ws.recv()
        async with connect(url) as ws:                  # le jeton reçu fonctionne
            await ws.send(json.dumps({"type": "hello", "token": token, "device_id": "hu", "vehicle_id": "slk_r171"}))
            assert json.loads(await ws.recv())["type"] == "welcome"

    run_with(srv, scenario)
