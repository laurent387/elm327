from overmind_obd.store import parse_ts
from conftest import T0


def test_dedup_and_latest(store, msgs):
    m = msgs.telemetry({"rpm": 800, "map_kpa_absolute": 35}, t=0)
    assert store.ingest("telemetry", "slk_r171", "android_01", m)
    assert not store.ingest("telemetry", "slk_r171", "android_01", m)
    assert store.message_count() == 1
    store.ingest("telemetry", "slk_r171", "android_01", msgs.telemetry({"rpm": 3000}, t=5, missing=("map_kpa_absolute",)))
    lat = store.latest("slk_r171")
    assert lat["rpm"]["value"] == 3000
    # MAP : dernière mesure en no_data, mais la dernière valeur valide est conservée, jamais inventée
    assert lat["map_kpa_absolute"]["status"] == "no_data" and lat["map_kpa_absolute"]["value"] == 35


def test_out_of_order_replay_does_not_overwrite_latest(store, msgs):
    late = msgs.telemetry({"rpm": 1000}, t=10)
    early = msgs.telemetry({"rpm": 2000}, t=1)  # message bufferisé rejoué après
    store.ingest("telemetry", "slk_r171", "android_01", late)
    store.ingest("telemetry", "slk_r171", "android_01", early)
    assert store.latest("slk_r171")["rpm"]["value"] == 1000


def test_timeseries_and_downsample(store, msgs):
    for i in range(100):
        store.ingest("telemetry", "slk_r171", "android_01", msgs.telemetry({"rpm": 1000 + i}, t=i))
    full = store.timeseries("slk_r171", "rpm")
    assert len(full) == 100 and full[0]["value"] == 1000
    ds = store.timeseries("slk_r171", "rpm", limit=10)
    assert 9 <= len(ds) <= 11 and all("min" in p and "max" in p for p in ds)
    win = store.timeseries("slk_r171", "rpm", start=parse_ts(T0.isoformat()) + 50, end=parse_ts(T0.isoformat()) + 59)
    assert [p["value"] for p in win] == list(range(1050, 1060))


def test_frames_aligned_by_message(store, msgs):
    store.ingest("telemetry", "slk_r171", "android_01", msgs.telemetry({"rpm": 800, "map_kpa_absolute": 33}, t=0))
    store.ingest("telemetry", "slk_r171", "android_01", msgs.telemetry({"rpm": 900}, t=1))
    fr = store.frames("slk_r171", ["rpm", "map_kpa_absolute"], 0)
    assert fr[0]["rpm"] == 800 and fr[0]["map_kpa_absolute"] == 33 and "map_kpa_absolute" not in fr[1]


def test_session_survives_bt_reconnect(store, msgs):
    store.ingest("status", "slk_r171", "android_01", msgs.status("connected", t=0))
    store.ingest("status", "slk_r171", "android_01", msgs.status("disconnected", t=10))
    assert store.sessions("slk_r171")[0]["ended_at"] is not None
    store.ingest("telemetry", "slk_r171", "android_01", msgs.telemetry({"rpm": 800}, t=20))
    s = store.sessions("slk_r171")[0]
    assert s["ended_at"] is None and s["n_messages"] == 3
    detail = store.session(s["session_id"])
    assert detail["stats"][0]["key"] == "rpm"


def test_dtc_status_health(store, msgs):
    store.ingest("dtc", "slk_r171", "android_01", msgs.dtc(stored=["P0171"]))
    store.ingest("status", "slk_r171", "android_01", msgs.status(supported_pids=["010C", "010B"]))
    store.ingest("health", "slk_r171", "android_01", msgs.health())
    assert store.latest_dtc("slk_r171")["stored"][0]["code"] == "P0171"
    assert store.supported_pids("slk_r171") == ["010C", "010B"]
    assert store.latest_health("slk_r171")["state"] == "online"
