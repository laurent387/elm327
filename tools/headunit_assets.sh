#!/usr/bin/env bash
# Copie le petit modèle Vosk FR dans les assets de la variante autoradio (mot de réveil hors ligne).
# Le modèle (≈ 66 Mo) n'est pas versionné. Source : ~/.local/share/overmind/models (copié de HoverBot),
# sinon téléchargement depuis alphacephei.com.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
DEST="$ROOT/android/app/src/headunit/assets/model-fr"
SRC="${VOSK_SMALL_FR:-$HOME/.local/share/overmind/models/vosk/vosk-model-small-fr-0.22}"
if [ ! -d "$SRC" ]; then
  tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
  curl -fL -o "$tmp/m.zip" https://alphacephei.com/vosk/models/vosk-model-small-fr-0.22.zip
  unzip -q "$tmp/m.zip" -d "$tmp"; SRC="$tmp/vosk-model-small-fr-0.22"
fi
rm -rf "$DEST"; mkdir -p "$DEST"
cp -r "$SRC"/am "$SRC"/conf "$SRC"/graph "$SRC"/ivector "$DEST"/
# StorageService.unpack() de vosk-android exige un fichier uuid (change à chaque copie → re-dépaquetage).
cat /proc/sys/kernel/random/uuid > "$DEST/uuid"
du -sh "$DEST"
# CA PUBLIQUE Overmind (pas la clé) : sert de confiance par défaut pour wss:// si les réglages MQTT
# n'en ont pas. Non versionnée (propre à chaque installation).
CA="${OVERMIND_CA_CERT:-$HOME/.config/overmind/ca/ca.crt}"
if [ -f "$CA" ]; then
  grep -q "BEGIN CERTIFICATE" "$CA" && ! grep -q "PRIVATE KEY" "$CA"
  cp "$CA" "$ROOT/android/app/src/headunit/assets/overmind_ca.pem"; echo "CA embarquée : $CA"
fi
# ELM327 de la voiture (adresse Bluetooth, pas un secret) : l'autoradio s'y connecte seul.
MAC="${OVERMIND_OBD_MAC:-$(cat "$HOME/.config/overmind/obd_adapter_mac" 2>/dev/null || true)}"
if [[ "$MAC" =~ ^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$ ]]; then
  echo "$MAC" > "$ROOT/android/app/src/headunit/assets/obd_adapter.txt"; echo "ELM327 préconfiguré : $MAC"
fi
