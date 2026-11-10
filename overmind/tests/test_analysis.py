from overmind_obd.analysis import analyze_snapshot, build_snapshot
from overmind_obd.registry import Registry
from overmind_obd.report import render_report
from overmind_obd.store import parse_ts
from conftest import REGISTRY, MsgFactory, ts


def feed(store, msgs, frames):
    for i, f in enumerate(frames):
        store.ingest("telemetry", msgs.vehicle, "android_01", msgs.telemetry(f, t=i))
    return parse_ts(ts(len(frames)))


def run(store, registry, vehicle, end):
    return analyze_snapshot(build_snapshot(store, registry, vehicle, window_s=3600, end=end))


IDLE = {"rpm": 750, "speed_kmh": 0, "throttle_pct": 3, "engine_load_pct": 22, "map_kpa_absolute": 34}
LOAD = {"rpm": 3000, "speed_kmh": 90, "throttle_pct": 60, "engine_load_pct": 70, "map_kpa_absolute": 80}


def test_lean_at_idle_points_to_air_leak(store, registry):
    msgs = MsgFactory("golf")
    frames = [{**IDLE, "short_fuel_trim_b1_pct": 9, "long_fuel_trim_b1_pct": 12} for _ in range(20)]
    frames += [{**LOAD, "short_fuel_trim_b1_pct": 1, "long_fuel_trim_b1_pct": 3} for _ in range(20)]
    end = feed(store, msgs, frames)
    store.ingest("dtc", "golf", "android_01", msgs.dtc(stored=["P0171"]))
    a = run(store, registry, "golf", end)
    f = next(x for x in a["findings"] if x["id"] == "fuel_trim_lean_b1")
    assert f["nature"] == "hypothesis" and "prise d'air" in f["title"] and f["related_dtc"] == ["P0171"]
    assert 0 < f["confidence"] <= 0.8
    dtc = next(x for x in a["findings"] if x["id"] == "dtc_stored_P0171")
    assert dtc["nature"] == "confirmed"
    assert any("ne permet pas de conclure" in e for e in dtc["evidence"])


def test_lean_under_load_points_to_fuel_or_maf(store, registry):
    msgs = MsgFactory("golf")
    frames = [{**IDLE, "short_fuel_trim_b1_pct": 1, "long_fuel_trim_b1_pct": 2} for _ in range(20)]
    frames += [{**LOAD, "short_fuel_trim_b1_pct": 8, "long_fuel_trim_b1_pct": 9} for _ in range(20)]
    a = run(store, registry, "golf", feed(store, msgs, frames))
    f = next(x for x in a["findings"] if x["id"] == "fuel_trim_lean_b1")
    assert "en charge" in f["title"]


def test_unknown_map_location_is_a_limit_for_supercharged(store, registry):
    msgs = MsgFactory("slk_r171")
    frames = [{"rpm": 4500, "throttle_pct": 95, "engine_load_pct": 90, "map_kpa_absolute": 105, "baro_kpa": 101}
              for _ in range(10)]
    a = run(store, registry, "slk_r171", feed(store, msgs, frames))
    assert not any(f["id"] == "boost_low" for f in a["findings"])
    assert any("supercharger" in lim and "unknown" in lim for lim in a["limits"])


def test_boost_low_when_sensor_post_compressor(store):
    raw = {**REGISTRY, "vehicles": {**REGISTRY["vehicles"]}}
    raw["vehicles"]["slk_r171"] = {**raw["vehicles"]["slk_r171"], "map_sensor_location": "post_compressor"}
    reg = Registry.from_dict(raw)
    msgs = MsgFactory("slk_r171")
    frames = [{"rpm": 4500, "throttle_pct": 95, "engine_load_pct": 90, "map_kpa_absolute": 105, "baro_kpa": 101}
              for _ in range(10)]
    a = run(store, reg, "slk_r171", feed(store, msgs, frames))
    f = next(x for x in a["findings"] if x["id"] == "boost_low")
    assert f["nature"] == "hypothesis"
    assert any("NON vérifiée" in e for e in f["evidence"])


def test_no_data_reports_limits_not_findings(store, registry):
    a = run(store, registry, "slk_r171", None)
    assert a["findings"] == [] or all(f["id"].startswith("dtc") for f in a["findings"])
    assert a["limits"][0].startswith("Aucune trame")
    assert a["overall_confidence"] <= 0.2


def test_report_markdown(store, registry):
    msgs = MsgFactory("golf")
    end = feed(store, msgs, [{**IDLE, "coolant_temp_c": 88} for _ in range(5)])
    store.ingest("dtc", "golf", "android_01", msgs.dtc(stored=["P0420"], permanent=None))
    a = run(store, registry, "golf", end)
    md = render_report(a, store.latest("golf"), store.latest_dtc("golf"), None)
    assert "# Rapport de diagnostic" in md and "P0420" in md and "non supporté" in md and "Limites" in md
