# Autoradio : copilote vocal « Kitt »

La variante **autoradio** de l'appli (`com.overmind.copilot`, « Overmind Kitt ») fait deux
choses sur un autoradio Android :

1. **Passerelle OBD** : exactement le code de l'appli téléphone (ELM327 Bluetooth → MQTT →
   Overmind), accessible par le bouton « Passerelle OBD ».
2. **Copilote vocal** : on dit « Kitt », on parle, Overmind répond à voix haute.

Le cerveau et la voix tournent **sur le serveur Overmind (GE75)**, avec ce qui existait déjà :

| Étape | Où | Composant |
|---|---|---|
| Mot de réveil « Kitt » | autoradio, hors ligne | Vosk petit modèle FR, grammaire `["kit", "[unk]"]` |
| Transcription | GE75 | Vosk FR (modèles repris de HoverBot) |
| Garde-fous et lectures directes | GE75 | `overmind_copilot.brain` : refus des écritures véhicule, commandes média, navigation, valeurs OBD lues dans la base sans modèle |
| Conversation | GE75 | agent [`agents/copilot`](../agents/copilot/) via l'orchestrateur HoverMind : Mistral (GE75), repli Qwen (HoverBot) |
| Diagnostic | GE75 | agent [`obd_mechanic`](../agents/obd_mechanic/), repli moteur de règles `overmind_obd` |
| Voix | GE75 | Piper `fr_FR-siwis-medium` (même voix que HoverBot) |

Protocole : [`schemas/COPILOT_PROTOCOL.md`](../schemas/COPILOT_PROTOCOL.md).

## Sécurité

- **Lecture seule côté véhicule** : il n'existe aucun outil ni aucune action qui écrive sur
  le bus OBD. « Efface les défauts » est refusé par le serveur sans même consulter le modèle.
- **Jamais de valeur inventée** : régime, vitesse, températures, tension, carburant et codes
  défauts sont lus directement dans la base Overmind ; si la voiture n'envoie rien, Kitt le
  dit et date la dernière valeur connue.
- **Micro** : rien ne quitte l'autoradio avant le mot de réveil ; le serveur revérifie le mot
  sur la transcription complète (« quitte l'autoroute » ne réveille pas Kitt).
- **Appel téléphonique** : seulement après un « oui » explicite.
- **Réseau** : `overmind_copilot` n'écoute en clair que sur 127.0.0.1 ; sur le réseau (cible
  de la redirection Freebox), TLS obligatoire avec la CA privée, et jeton (portée `copilot`).

## Serveur Overmind (GE75)

```bash
cd ~/elm327/overmind
.venv/bin/pip install piper-tts vosk websockets
# Modèles (repris de HoverBot) dans ~/.local/share/overmind/models/{piper,vosk}
rsync -a laurent@192.168.1.4:/opt/hoverbot/models/piper/ ~/.local/share/overmind/models/piper/
rsync -a laurent@192.168.1.4:/opt/hoverbot/models/vosk/vosk-model-small-fr-0.22 ~/.local/share/overmind/models/vosk/
# Jeton de l'autoradio (affiché une seule fois)
.venv/bin/python -m overmind_obd.tokens create autoradio --scopes copilot
# Services (ingestion OBD + copilote), variables dans ~/.config/overmind/obd.env (voir deploy/systemd/obd.env.example)
cp ../deploy/systemd/overmind-obd-api.service ../deploy/systemd/overmind-copilot.service ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now overmind-obd-api overmind-copilot
```

Test sans autoradio :

```bash
OVERMIND_COPILOT_TOKEN=… .venv/bin/python -m overmind_copilot.client --text "quel est le régime ?"
OVERMIND_COPILOT_TOKEN=… .venv/bin/python -m overmind_copilot.client --wav question.wav --out reponse.wav
```

Mémoire : petit modèle Vosk ≈ 0,4 Go, Piper ≈ 0,2 Go. Le grand modèle Vosk
(`vosk-model-fr-0.22`, ≈ 4 Go de RAM) s'active avec `OVERMIND_COPILOT_VOSK_MODEL` ; sur des
voix de synthèse il ne transcrit pas mieux que le petit.

## Accès depuis la route : Internet + Freebox (comme MQTT)

Même principe que la passerelle OBD : l'autoradio (sur le partage de connexion du
téléphone, ou n'importe quel Wi-Fi) joint l'**IP publique** de la Freebox ; la Freebox
redirige un port de la plage de l'IPv4 partagée vers le GE75 ; **TLS obligatoire** avec la CA
privée Overmind (celle de Mosquitto, déjà collée dans les réglages MQTT de la passerelle) et
jeton `copilot`.

| Service | Port externe | → GE75 |
|---|---|---|
| MQTT (passerelle OBD) | 38883 | 192.168.1.35:8883 |
| Kitt (copilote) | 38097 | 192.168.1.35:8097 |

1. `~/.config/overmind/obd.env` :
   `OVERMIND_COPILOT_HOSTS=192.168.1.35`,
   `OVERMIND_COPILOT_TLS_CERT=…/ca/server.crt`, `OVERMIND_COPILOT_TLS_KEY=…/ca/server.key`
   (le service refuse d'écouter sur le réseau sans TLS ; en clair uniquement sur 127.0.0.1).
2. Freebox OS → Gestion des ports → redirection TCP 38097 → 192.168.1.35:8097.
3. Kitt → Réglages : `wss://88.175.164.245:38097/copilot` + jeton. L'appli fait confiance à la
   CA des réglages MQTT et vérifie que le certificat porte bien cette IP.
4. Vérification depuis l'extérieur :
   `python -m overmind_copilot.client --url wss://88.175.164.245:38097/copilot --text "bonjour"`.

Un jeton invalide est refusé après 2 s (ralentit les essais). Ne jamais rediriger 8095 (API),
8799 (MCP) ni 1883.

## Télécommande (MCP)

Pour diagnostiquer ou piloter l'autoradio à distance (depuis Claude Code, un agent Overmind…),
le service copilote expose un **serveur MCP local** : `http://127.0.0.1:8789/mcp`.

```text
Client MCP (GE75) ─HTTP 127.0.0.1:8789, jeton « remote »─▶ overmind_copilot
                                                             │ même WebSocket TLS, ouverte PAR l'autoradio
                                                             ▼
                                                   Autoradio (Overmind Kitt)
```

- **Rien d'ouvert sur l'autoradio**, rien de redirigé vers le MCP : il n'écoute que sur
  127.0.0.1 et reste éteint sans jeton de portée `remote`.
- Outils : `headunit_list`, `headunit_status`, `headunit_display`, `headunit_logs`,
  `headunit_screenshot`, `headunit_list_apps` (lecture) ; `headunit_say`, `headunit_media`,
  `headunit_launch_app`, `headunit_restart_kitt`, `headunit_install_update` (actions).
- **Jamais le véhicule** : pas de commande OBD, pas d'effacement de défauts, pas de shell.
- Interrupteur **Télécommande Overmind** dans les réglages Kitt (activé par défaut) ; chaque
  action s'affiche brièvement à l'écran.
- Mise à jour : l'APK (le plus récent de `android/app/build/outputs/apk/headunit`, seul
  dossier autorisé) passe par morceaux dans la WebSocket chiffrée ; l'autoradio vérifie
  l'empreinte SHA-256 et le package, puis l'installeur Android demande **confirmation à
  l'écran**. La première fois, Android demande d'autoriser « Overmind Kitt » à installer des
  applis. Après l'installation, Kitt redémarre seul.
- Capture d'écran : uniquement la fenêtre Kitt quand elle est au premier plan (Android ne
  permet pas de capturer les autres applis sans root).

```bash
cd ~/elm327/overmind
.venv/bin/python -m overmind_obd.tokens create claude-remote --scopes remote   # affiché une fois
# Claude Code (jeton gardé dans ~/.config/overmind/kitt_remote.token, droits 600)
claude mcp add --transport http kitt-remote http://127.0.0.1:8789/mcp \
  --header "Authorization: Bearer $(cat ~/.config/overmind/kitt_remote.token)"
```

## Autoradio de Laurent : HF S8 Series H240 (Mercedes SLK R171)

| Caractéristique | Valeur | Conséquence pour l'appli |
|---|---|---|
| SoC | Rockchip RK3326 / PX30, 4× Cortex-A35 1,5 GHz | mot de réveil Vosk petit modèle uniquement ; tout le reste (STT complet, LLM, voix) sur le GE75 |
| RAM | 2 Go | modèle Vosk ~70 Mo en mémoire ; pas de grand modèle local |
| Android | 9.0 (API 28) | minSdk 26 OK ; pas de permission `BLUETOOTH_CONNECT` / `POST_NOTIFICATIONS` (demandées seulement si API ≥ 31 / 33) ; service « micro » de premier plan sans type obligatoire |
| ABI | ARM (armeabi-v7a / arm64-v8a selon le firmware) | les deux sont dans l'APK |
| Firmware | MTCE, Hal9k Mod v4.0.0, MCU MTCE_HF_V3.07_1 | voir ci-dessous (démarrage au réveil, touche volant) |
| Écran | paysage, 1024×600 probable, densité à confirmer | **aucune dimension imposée** : l'appli détecte taille et densité au démarrage (Réglages Kitt → « Écran détecté », et `adb logcat -s OvermindDisplay`) |
| Root | Magisk 20.1, non utilisé | l'appli ne demande jamais le root |

**Interface adaptative** (`ui/DisplayProfile.kt`) : taille, densité et police lues à
l'exécution ; en paysage, navigation latérale et tableau sur 3 colonnes avec le bouton de
session visible sans défiler ; anneau vocal, textes et chiffres proportionnels à la place
disponible ; boutons d'au moins 56 dp. Vérifié dans un émulateur Android 9 en 1024×600 à 160 dpi
(1024×528 dp utiles) et à 240 dpi (634×376 dp) :

```bash
# AVD « hf_s8_api28 » (x86_64, 1024×600, 160 dpi, 2 Go) ; APK avec toutes les ABI pour l'émulateur
cd android && ./gradlew assembleHeadunitDebug -PallAbis
ANDROID_AVD_HOME=~/.config/.android/avd ~/Android/Sdk/emulator/emulator -avd hf_s8_api28
```

**Spécificités MTCE / Hal9k :**
- Contact coupé = mise en veille, pas un redémarrage : ajouter **Overmind Kitt** à la liste
  des applis à relancer au réveil / à ne pas tuer dans les réglages Hal9k (au démarrage
  complet, l'appli se relance seule via `BOOT_COMPLETED`).
- Touche vocale du volant : Hal9k permet d'associer une touche à une appli ; Kitt répond aussi
  à l'action Android `VOICE_COMMAND`.
- Appel Bluetooth en cours (mode audio « appel ») : Kitt libère le micro et reprend l'écoute
  à la fin de l'appel.
- Le micro de l'autoradio peut être partagé avec le module Bluetooth : si une autre appli le
  prend, Kitt attend sans boucler.

## Installation sur l'autoradio

```bash
tools/headunit_assets.sh                      # copie le petit modèle Vosk dans les assets (non versionné)
cd android && ./gradlew assembleHeadunitDebug # → app/build/outputs/apk/headunit/debug/app-headunit-debug.apk
adb connect <ip-autoradio>:5555               # débogage ADB (USB ou Wi-Fi) à activer dans l'autoradio
adb install -r app/build/outputs/apk/headunit/debug/app-headunit-debug.apk
```

**Appairage sans taper de jeton** : sur le GE75, `cd overmind && .venv/bin/python -m overmind_copilot.pair`
affiche un code à 6 chiffres (10 min, usage unique, annulé après 5 essais faux ; aussi par l'outil
MCP `headunit_pairing_code`). Le taper dans Réglages Kitt à la place du jeton : Overmind envoie
alors à l'autoradio son propre jeton `copilot`. Le certificat public de la CA Overmind est embarqué
dans l'APK (`tools/headunit_assets.sh`), rien à coller pour `wss://`.

Au premier lancement : autoriser le micro (obligatoire), la position (contexte), les contacts
et le téléphone (appels, facultatif). Dans **Réglages Kitt** : adresse du serveur, jeton,
mot de réveil (`kit` par défaut, tel que le modèle l'écrit). Le bouton **Passerelle OBD**
ouvre l'appli passerelle (choix de l'ELM327, réglages MQTT, démarrage de session).

Test depuis un ordinateur (builds debug uniquement) :

```bash
adb shell 'am start -n com.overmind.copilot/com.overmind.copilot.CopilotActivity --es ask "quel est le régime ?"'
```

## Limites connues

- Pas encore essayé sur l'autoradio lui-même ni en roulant : validé sur un téléphone (Nothing
  Phone 3, paysage) et avec des questions synthétisées par Piper.
- Le mot de réveil n'a été testé que sur voix de synthèse ; le seuil (`WakeMatcher`, confiance
  0,85) sera peut-être à ajuster avec la vraie voix et le bruit de roulement.
- Pas de réponse vocale hors ligne au-delà d'un résumé minimal (régime, température, nombre de
  codes) prononcé par la voix Android de l'autoradio.
- Données du boîtier CANbus de l'autoradio (portes, température extérieure, commandes au
  volant) : non lues, dépendent du modèle d'autoradio.
- Pas d'accès Internet pour Kitt (météo, actualités) : Mistral et Qwen tournent en local.
- Télécommande vérifiée dans l'émulateur Android 9 (état, capture, voix, musique, applis,
  redémarrage, mise à jour de 92 Mo confirmée à l'écran puis reconnexion), pas encore sur
  l'autoradio ni à travers la Freebox.
