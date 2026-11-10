#!/usr/bin/env bash
# Crée/renouvelle le compte MQTT d'une passerelle et ses droits d'écriture.
#   sudo deploy/mosquitto/add_gateway.sh android_01 slk_r171 [autre_vehicule...]
#   sudo deploy/mosquitto/add_gateway.sh --ingest           # compte du service d'ingestion
# Le mot de passe est affiché UNE fois (à saisir dans l'application) ; Mosquitto n'en garde que le hash.
set -euo pipefail
# Emplacements lisibles par le profil AppArmor de Mosquitto (Ubuntu) : /etc/mosquitto/* uniquement.
DIR=${OVERMIND_MQTT_DIR:-/etc/mosquitto}
PASSWD="$DIR/overmind_passwd"; ACL="$DIR/overmind_acl"
command -v mosquitto_passwd >/dev/null || { echo "mosquitto_passwd introuvable (apt install mosquitto)"; exit 1; }
mkdir -p "$DIR"; touch "$PASSWD" "$ACL"; chmod 640 "$PASSWD" "$ACL"
getent group mosquitto >/dev/null && chgrp mosquitto "$PASSWD" "$ACL"
re='^[a-z0-9_-]{1,64}$'
if [[ "${1:-}" == "--ingest" ]]; then
  user=overmind-ingest
  grep -q "^user $user$" "$ACL" || printf '\nuser %s\ntopic read overmind/vehicles/+/obd/#\ntopic read overmind/vehicles/+/gateway/health\n' "$user" >> "$ACL"
else
  [[ $# -ge 2 ]] || { echo "usage: $0 <gateway_id> <vehicle_id>..."; exit 2; }
  user=$1; shift
  [[ $user =~ $re ]] || { echo "gateway_id invalide"; exit 2; }
  # retire l'ancien bloc de cette passerelle puis le réécrit
  awk -v u="$user" 'BEGIN{skip=0} /^user /{skip=($2==u)} !skip' "$ACL" > "$ACL.tmp" && mv "$ACL.tmp" "$ACL"
  { printf '\n# --- passerelle %s ---\nuser %s\n' "$user" "$user"
    for v in "$@"; do
      [[ $v =~ $re ]] || { echo "vehicle_id invalide: $v" >&2; exit 2; }
      for t in obd/telemetry obd/dtc obd/status gateway/health; do printf 'topic write overmind/vehicles/%s/%s\n' "$v" "$t"; done
    done; } >> "$ACL"
fi
pw=$(openssl rand -base64 24 | tr -d '/+=' | cut -c1-28)
mosquitto_passwd -b "$PASSWD" "$user" "$pw"
echo "utilisateur MQTT : $user"
echo "mot de passe     : $pw"
echo "# Pensez à : systemctl reload mosquitto (ou kill -HUP) et à déclarer la passerelle dans obd_gateways.yaml"
