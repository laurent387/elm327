import socket
import threading
import time

import pytest

import elm327_sim
from fake_gateway import Elm, decode, load_catalog


@pytest.fixture(scope="module")
def sim_port():
    args = elm327_sim.parse_args(["--host", "127.0.0.1", "--port", "0", "--latency-ms", "1", "--dtc", "P0171",
                                  "--dtc", "P0300", "--dtc", "P0420", "--pending", "P0128", "--no-permanent",
                                  "--multi-ecu"])
    srv = socket.socket()
    srv.bind(("127.0.0.1", 0))
    port = srv.getsockname()[1]
    srv.close()
    args.port = port
    threading.Thread(target=elm327_sim.serve_tcp, args=(args,), daemon=True).start()
    for _ in range(50):
        try:
            socket.create_connection(("127.0.0.1", port), timeout=0.2).close()
            break
        except OSError:
            time.sleep(0.05)
    return port


def raw(sock, cmd):
    sock.sendall((cmd + "\r").encode())
    buf = b""
    while not buf.endswith(b">"):
        buf += sock.recv(4096)
    return buf.decode()


def test_raw_elm_dialog(sim_port):
    s = socket.create_connection(("127.0.0.1", sim_port))
    assert "ELM327 v1.5" in raw(s, "ATZ")
    assert raw(s, "ATE0").startswith("ATE0\rOK")       # écho encore actif pour cette commande
    assert raw(s, "ATSP0") == "OK\r\r>"
    first = raw(s, "0100")
    assert first.startswith("SEARCHING...\r41 00 ")
    assert raw(s, "ATDPN").startswith("A6")
    assert raw(s, "0199") == "NO DATA\r\r>"
    assert raw(s, "04") == "7F 04 22\r\r>"           # effacement refusé : moteur tournant
    assert raw(s, "0A") == "NO DATA\r\r>"
    assert raw(s, "ATS0").startswith("OK")
    mf = raw(s, "03")                                   # 3 codes -> multi-trame ISO-TP, ATH0
    assert mf.startswith("008\r0:43030171")


def test_client_parses_everything(sim_port):
    e = Elm("127.0.0.1", sim_port)
    info = e.init()
    assert info["protocol"] == "6" and info["version"] == "ELM327 v1.5" and 11 < info["voltage_v"] < 15
    sup = e.supported_pids()
    assert {"010C", "010B", "0104", "0133", "0101"} <= set(sup) and "0120" not in sup
    assert {d["code"] for d in e.dtcs("03")} == {"P0171", "P0300", "P0420"}
    assert [d["code"] for d in e.dtcs("07")] == ["P0128"]
    assert e.dtcs("0A") is None
    assert e.vin() == "SIMULATEDVIN00001"
    resp = e.request("0100")
    assert set(resp) == {"7E8", "7E9"}                  # deux calculateurs (ATH1)
    rpm = e.request("010C")["7E8"]
    assert 0 <= decode(load_catalog()["010C"]["formula"], rpm[2:]) < 8000
    with pytest.raises(ValueError):
        e.cmd("04")                                     # le client refuse aussi le mode 04


def test_clear_dtc_only_engine_off():
    """Mode 04 : comme un calculateur, accepté seulement contact mis moteur coupé, puis codes vidés."""
    args = elm327_sim.parse_args(["--dtc", "P0171", "--pending", "P0300", "--engine-off", "--no-permanent"])
    state = elm327_sim.ElmState(args)
    assert state.engine.rpm == 0
    assert any("44" in line for line in state.obd("04"))
    assert args.dtc == [] and args.pending == []
    assert state.obd("03") == ["43 00"]
