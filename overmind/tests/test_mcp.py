import asyncio
import json

from overmind_obd.mcp_server import build_mcp
from overmind_obd.queries import ObdQueries

EXPECTED = {"obd_list_vehicles", "obd_get_connection_status", "obd_get_supported_pids", "obd_get_live_data",
            "obd_get_dtc", "obd_get_history", "obd_get_session", "obd_analyze_engine", "obd_generate_report"}


def _call(mcp, name, args):
    res = asyncio.run(mcp.call_tool(name, args))
    if isinstance(res, tuple) and isinstance(res[1], dict):
        structured = res[1]
        return structured["result"] if set(structured) == {"result"} else structured
    blocks = res[0] if isinstance(res, tuple) else res
    text = "".join(getattr(b, "text", "") for b in blocks)
    try:
        return json.loads(text)
    except ValueError:
        return text


def test_tools_are_exactly_the_read_only_set(store, registry):
    mcp = build_mcp(ObdQueries(store, registry))
    tools = asyncio.run(mcp.list_tools())
    assert {t.name for t in tools} == EXPECTED
    assert all(t.annotations.readOnlyHint and not t.annotations.destructiveHint for t in tools)


def test_tools_return_data(store, registry, msgs):
    store.ingest("status", "slk_r171", "android_01",
                 msgs.status(supported_pids=["010C", "010B"], elm={"version": "ELM327 v1.5", "protocol": "6"}))
    store.ingest("telemetry", "slk_r171", "android_01", msgs.telemetry({"rpm": 812, "map_kpa_absolute": 36}))
    store.ingest("dtc", "slk_r171", "android_01", msgs.dtc(stored=["P0016"]))
    mcp = build_mcp(ObdQueries(store, registry))
    assert {v["vehicle_id"] for v in _call(mcp, "obd_list_vehicles", {})} >= {"slk_r171"}
    st = _call(mcp, "obd_get_connection_status", {"vehicle_id": "slk_r171"})
    assert st["gateway_id"] == "android_01" and st["elm"]["version"] == "ELM327 v1.5"
    sup = _call(mcp, "obd_get_supported_pids", {"vehicle_id": "slk_r171"})
    assert [p["key"] for p in sup["supported"]] == ["rpm", "map_kpa_absolute"]
    live = _call(mcp, "obd_get_live_data", {"vehicle_id": "slk_r171"})
    assert live["values"]["rpm"]["value"] == 812
    dtc = _call(mcp, "obd_get_dtc", {"vehicle_id": "slk_r171"})
    assert "chaîne" in dtc["stored"][0]["note"]  # surcharge fiche M271
    hist = _call(mcp, "obd_get_history", {"vehicle_id": "slk_r171", "key": "rpm", "start": "2026-01-01T00:00:00Z"})
    assert hist["points"][0]["value"] == 812
    sess = _call(mcp, "obd_get_session", {"vehicle_id": "slk_r171"})["sessions"]
    assert sess[0]["vehicle_id"] == "slk_r171"
    assert _call(mcp, "obd_get_session", {"session_id": sess[0]["session_id"]})["session"]["n_messages"] == 3
    an = _call(mcp, "obd_analyze_engine", {"vehicle_id": "slk_r171"})
    assert "limits" in an and "findings" in an
    rep = _call(mcp, "obd_generate_report", {"vehicle_id": "slk_r171"})
    assert rep.startswith("# Rapport de diagnostic")
    assert "error" in _call(mcp, "obd_get_dtc", {"vehicle_id": "inconnu"})
