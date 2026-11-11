#!/usr/bin/env bash
# Bout-en-bout local, sans téléphone : code Kotlin de l'appli (JVM) → simulateur ELM327 TCP
# → broker MQTT de dev → ingestion Overmind → API. Vérifie que les mesures arrivent.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PY="$ROOT/overmind/.venv/bin/python"
WORK="$(mktemp -d)"
ELM_PORT=${ELM_PORT:-35001}; MQTT_PORT=${MQTT_PORT:-1884}; API_PORT=${API_PORT:-8097}
pids=()
cleanup() { for p in "${pids[@]}"; do kill "$p" 2>/dev/null || true; done; rm -rf "$WORK"; }
trap cleanup EXIT

"$PY" "$ROOT/tools/elm327_sim/elm327_sim.py" --host 127.0.0.1 --port "$ELM_PORT" --dtc P0171 >"$WORK/sim.log" 2>&1 & pids+=($!)
"$PY" "$ROOT/tools/dev_broker.py" --port "$MQTT_PORT" >"$WORK/broker.log" 2>&1 & pids+=($!)
sleep 2
( cd "$ROOT/overmind" && OVERMIND_OBD_DB="$WORK/obd.sqlite3" OVERMIND_OBD_REGISTRY="$ROOT/overmind/config/gateways.example.yaml" \
  OVERMIND_OBD_TOKENS_FILE="$WORK/tokens.json" OVERMIND_OBD_ALLOW_NO_AUTH=1 OVERMIND_OBD_API_PORT="$API_PORT" \
  OVERMIND_OBD_MQTT_HOST=127.0.0.1 OVERMIND_OBD_MQTT_PORT="$MQTT_PORT" "$PY" -m overmind_obd.api ) >"$WORK/api.log" 2>&1 & pids+=($!)
sleep 3

( cd "$ROOT/android" && OVERMIND_E2E_ELM="127.0.0.1:$ELM_PORT" OVERMIND_E2E_MQTT="127.0.0.1:$MQTT_PORT" \
  ./gradlew -q testPhoneDebugUnitTest --tests '*JvmEndToEndTest*' --rerun-tasks -i ) | grep -E "E2E|MQTT|FAILED|PASSED" || true

sleep 1
echo "--- API Overmind : dernières valeurs"
curl -fsS "http://127.0.0.1:$API_PORT/api/obd/v1/vehicles/slk_r171/latest" | "$PY" -m json.tool | head -40
echo "--- API Overmind : défauts"
curl -fsS "http://127.0.0.1:$API_PORT/api/obd/v1/vehicles/slk_r171/dtc" | "$PY" -m json.tool | head -20
curl -fsS "http://127.0.0.1:$API_PORT/api/obd/v1/vehicles/slk_r171/latest" | "$PY" -c '
import json,sys; d=json.load(sys.stdin); s=json.dumps(d)
assert "rpm" in s and "map_kpa_absolute" in s and "engine_load_pct" in s, "MVP absent"
print("OK : RPM, MAP et charge reçus par Overmind depuis le code Android")'
