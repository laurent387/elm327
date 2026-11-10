import json

import pytest

from overmind_obd.ingest import Ingestor
from overmind_obd.validation import Rejected, validate


def test_valid_telemetry(registry, msgs):
    m = msgs.telemetry({"rpm": 800, "map_kpa_absolute": 35})
    v = validate(msgs.topic("telemetry"), json.dumps(m).encode(), registry)
    assert v.kind == "telemetry" and v.vehicle_id == "slk_r171" and not v.is_lwt


@pytest.mark.parametrize("mutate,reason", [
    (lambda m: m.update(vehicle_id="golf"), "vehicle_mismatch"),
    (lambda m: m.update(gateway_id="intruder"), "gateway_not_authorized"),
    (lambda m: m.update(gateway_id="disabled_gw"), "gateway_not_authorized"),
    (lambda m: m["measurements"][0].update(value="3500"), "schema"),
    (lambda m: m["measurements"][0].update(status="invented"), "schema"),
    (lambda m: m.update(schema_version="2.0"), "schema"),
    (lambda m: m.pop("message_id"), "schema"),
    (lambda m: m.update(kind="dtc"), "schema"),
])
def test_rejections(registry, msgs, mutate, reason):
    m = msgs.telemetry({"rpm": 800})
    mutate(m)
    with pytest.raises(Rejected) as exc:
        validate(msgs.topic("telemetry"), json.dumps(m), registry)
    assert exc.value.reason == reason


def test_bad_topic_and_json(registry, msgs):
    with pytest.raises(Rejected, match="bad_topic"):
        validate("overmind/vehicles/slk_r171/obd/command", "{}", registry)
    with pytest.raises(Rejected, match="bad_json"):
        validate(msgs.topic("telemetry"), b"{not json", registry)


def test_lwt_without_message_id_accepted_only_offline(registry, msgs):
    lwt = {"schema_version": "1.0", "kind": "health", "vehicle_id": "slk_r171", "gateway_id": "android_01",
           "health": {"state": "offline"}}
    assert validate(msgs.topic("health"), json.dumps(lwt), registry).is_lwt
    lwt["health"]["state"] = "online"
    with pytest.raises(Rejected):
        validate(msgs.topic("health"), json.dumps(lwt), registry)


def test_ingestor_counts_and_dedup(registry, store, msgs):
    ing = Ingestor(store, registry)
    events = []
    ing.bus.subscribe(events.append)
    m = msgs.telemetry({"rpm": 800})
    assert ing.handle(msgs.topic("telemetry"), json.dumps(m)) == "accepted"
    assert ing.handle(msgs.topic("telemetry"), json.dumps(m)) == "duplicate"
    assert ing.handle("overmind/vehicles/golf/obd/telemetry", json.dumps(m)) == "rejected:vehicle_mismatch"
    assert ing.stats.accepted == 1 and ing.stats.duplicates == 1 and ing.stats.rejected["vehicle_mismatch"] == 1
    assert [e["type"] for e in events] == ["telemetry", "gateway_state"]


def test_ingestor_recomputes_compact_data(registry, store, msgs):
    ing = Ingestor(store, registry)
    got = []
    ing.bus.subscribe(got.append)
    m = msgs.telemetry({"rpm": 800}, missing=("maf_g_s",))
    m["data"]["maf_g_s"] = 12.0  # valeur incohérente : non `ok` dans measurements -> retirée
    ing.handle(msgs.topic("telemetry"), json.dumps(m))
    assert got[0]["payload"]["data"] == {"rpm": 800}


def test_lost_detection(registry, store, msgs):
    ing = Ingestor(store, registry, lost_after_s=30)
    ing.handle(msgs.topic("health"), json.dumps(msgs.health()))
    assert ing.check_lost() == []
    import time
    assert ing.check_lost(time.time() + 31) == [("android_01", "slk_r171")]
    assert store.gateways("slk_r171")[0]["state"] == "lost"
