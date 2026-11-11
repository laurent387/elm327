# Overmind OBD Gateway

Un smartphone Android sert de passerelle entre un adaptateur **ELM327 Bluetooth**
branché sur la prise OBD2 d'un véhicule essence ou diesel et le serveur **Overmind**.
Les agents Overmind (dont **Overmind Mechanic**) analysent les données à distance.

L'application **ne fait pas de diagnostic** : elle lit, horodate, met en tampon et
transmet. Elle est **en lecture seule côté véhicule** : aucun effacement de défaut,
aucun actionneur, aucun codage, aucune commande venue du réseau.

```text
Véhicule ─OBD2─ ELM327 ─Bluetooth SPP─ Android « Overmind OBD » ─MQTT QoS1 (Tailscale ou TLS)─▶ Mosquitto
                                         │ outbox SQLite (hors ligne)                              │
                                         └ service premier plan (écran éteint)                    ▼
                                                                         overmind_obd : ingestion → SQLite
                                                                         ├ API REST + WebSocket :8095 + page /obd/
                                                                         ├ bus interne → agents Overmind
                                                                         └ serveur MCP :8796 (lecture seule)
                                                                               └▶ Overmind Mechanic / ChatGPT (connecteur)
```

## 📥 Télécharger l'APK autoradio « Overmind Kitt »

**[⬇️ overmind-kitt-autoradio.apk](https://github.com/laurent387/elm327/releases/download/kitt-v0.1.0-test/overmind-kitt-autoradio.apk)**
(pré-version de test, Android 9+ ARM, 68 Mo — [page de la version](https://github.com/laurent387/elm327/releases/tag/kitt-v0.1.0-test))

Ouvrir ce lien dans le navigateur de l'autoradio, autoriser l'installation, puis saisir
l'adresse du serveur Overmind et le jeton dans **Réglages Kitt**. L'APK ne contient aucun
jeton ni adresse.

## Contenu du dépôt

| Dossier | Contenu |
|---|---|
| [`android/`](android/) | Projet Android Studio (Kotlin, Jetpack Compose, MVVM) |
| [`schemas/`](schemas/) | Contrat des messages : [`PROTOCOL.md`](schemas/PROTOCOL.md), schéma JSON, catalogue PID |
| [`overmind/`](overmind/) | Service Overmind `overmind_obd` : ingestion MQTT, stockage, API, analyse, MCP ([README](overmind/README.md)) |
| [`agents/obd_mechanic/`](agents/obd_mechanic/) | Skill **Overmind Mechanic** (format des agents HoverMind) |
| [`tools/`](tools/) | Simulateur ELM327, passerelle factice, broker de dev, test bout-en-bout |
| [`deploy/`](deploy/) | Mosquitto (conf, ACL, comptes), unités systemd |
| [`docs/`](docs/) | [Guide réseau Freebox / Tailscale](docs/RESEAU_FREEBOX_TAILSCALE.md) |

## Architecture de l'application Android

```text
com.overmind.obdgateway
├── obd/transport   BluetoothSppTransport (RFCOMM, 3 stratégies de connexion), TcpTransport (ELM Wi-Fi / simulateur)
├── obd/elm         ElmConnection (commande ↔ prompt '>'), ObdCommandGuard (liste blanche lecture seule),
│                   ObdResponseParser (CAN 11/29 bits, ISO-TP, J1850/ISO9141/KWP, multi-calculateurs),
│                   Elm327Session (init, protocole, PID supportés, multi-PID, DTC 03/07/0A, VIN)
├── obd/pid         PidCatalog (≈ 40 PID SAE J1979, aligné sur schemas/pid_catalog.json)
├── obd/dtc         DtcDecoder, DtcDescriptions (FR)
├── telemetry       MessageFactory (protocole v1.0, boost dérivé MAP−BARO), Topics
├── buffer          Outbox SQLite (suppression après PUBACK uniquement), historique des sessions
├── net             PahoMqttLink (TLS strict, CA privée optionnelle, LWT), OutboxDrainer (ordre, backoff, reprise)
├── gateway         GatewayController (session → acquisition → messages ; reconnexion ELM)
├── service         GatewayService (premier plan « connectedDevice », WakeLock de session, santé toutes les 30 s)
├── settings        Réglages (SharedPreferences), mot de passe chiffré par l'Android Keystore
└── ui              6 écrans Compose sombres : Tableau, ELM327, Direct, Défauts, Historique, Réglages
```

Points clés :

- **Acquisition** : PID « rapides » (régime, MAP, charge, vitesse, papillon, MAF) à chaque
  cycle (500 ms par défaut), un PID « lent » en tourniquet par cycle ; jusqu'à 6 PID par
  requête en CAN, avec repli automatique en unitaire.
- **Jamais de valeur inventée** : un PID absent ou sans réponse part avec `value: null`
  et un statut (`unsupported`, `no_data`, `error`) ; il n'apparaît pas dans `data`.
- **Suralimentation** : `map_kpa_absolute` (absolue), `baro_kpa`, et
  `boost_kpa_relative = MAP − BARO` publié avec l'emplacement déclaré du capteur MAP
  (`post_compressor` / `intake_manifold` / `unknown`) : un PID MAP générique ne prouve pas
  une mesure après le compresseur.
- **Sans doublon** : chaque message a un `message_id` (UUID) et une `sequence` monotone
  persistante ; il reste dans l'outbox jusqu'au PUBACK ; le serveur déduplique.
- **Sécurité route** : réglages verrouillés si la vitesse OBD > 0 ou pendant une session ;
  gros chiffres, pas d'interaction requise en roulant.

## Démarrage rapide

### Application Android

Prérequis : JDK 17+, Android SDK 35 (Android Studio ou `sdkmanager`).

```bash
cd android
./gradlew assembleDebug            # → app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest        # tests JVM (simulateur ELM327 Kotlin intégré)
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Sur le téléphone : appairer l'ELM327 dans les réglages Bluetooth Android (PIN `1234`
ou `0000`), ouvrir **Overmind OBD** → **ELM327** → choisir l'adaptateur → **Réglages** :
serveur, identifiants MQTT, véhicule (les valeurs par défaut `overmind.example.ts.net`,
`slk_r171`, `android_01` sont des **exemples**) → **Démarrer la session**.

Android 12+ demande l'autorisation « Appareils à proximité » (Bluetooth) et Android 13+
celle des notifications (notification de session).

### Serveur Overmind

Voir [`overmind/README.md`](overmind/README.md) et le
[guide réseau](docs/RESEAU_FREEBOX_TAILSCALE.md). En bref :

```bash
cd overmind
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
cp config/gateways.example.yaml ~/.config/overmind/obd_gateways.yaml   # puis adapter
.venv/bin/python -m overmind_obd.tokens create dashboard --scopes api  # jeton API (affiché une fois)
.venv/bin/python -m overmind_obd.api          # ingestion MQTT + API + visualisation http://127.0.0.1:8095/obd/
.venv/bin/python -m overmind_obd.mcp_server   # MCP lecture seule http://127.0.0.1:8796/mcp
```

### Tout tester sans voiture ni téléphone

```bash
tools/e2e_jvm.sh
```

Lance le simulateur ELM327 (TCP), un broker MQTT de dev et l'API Overmind, puis exécute
le **vrai code Kotlin de l'application** (transport TCP, session ELM, outbox, client Paho)
sur la JVM et vérifie via l'API que RPM, MAP et charge sont arrivés.

Dans l'application, **Réglages → Liaison ELM327 → TCP / Wi-Fi** permet aussi de pointer
le téléphone vers le simulateur (`python3 tools/elm327_sim/elm327_sim.py --host 0.0.0.0`)
ou vers un ELM327 Wi-Fi.

## Tests

| Suite | Commande | Couverture |
|---|---|---|
| Android (JVM) | `cd android && ./gradlew testDebugUnitTest` | décodage PID/DTC, parseur CAN/ISO-TP/KWP, garde-fou lecture seule, session ELM, messages, outbox/reprise, contrôleur (reconnexion, contact coupé) |
| Serveur | `cd overmind && .venv/bin/python -m pytest tests -q` | validation, dédup, stockage, analyse, MCP, simulateur, bout-en-bout Python, contrat Android |
| Bout-en-bout | `tools/e2e_jvm.sh` | code Kotlin → MQTT → ingestion → API |

## Sécurité

- **Véhicule** : `ObdCommandGuard` n'autorise que les services 01, 03, 07, 09, 0A et des
  commandes AT de configuration non persistantes ; 04 (effacement), 08 (actionneurs),
  services UDS/KWP et `ATPP` sont refusés. La passerelle ne s'abonne à aucun topic MQTT :
  il n'existe aucun chemin réseau vers le bus du véhicule.
- **Réseau** : connexion sortante uniquement ; TLS strict obligatoire en mode Internet ;
  un compte MQTT par téléphone avec ACL en écriture seule sur ses propres topics ; le
  serveur vérifie que `gateway_id` est autorisé pour `vehicle_id`.
- **Serveur** : API et MCP sur `127.0.0.1` (ou l'IP Tailscale) avec jetons Bearer stockés
  en empreinte SHA-256 ; jamais exposés sur Internet.
- **Diagnostic actif futur** (effacement, tests d'actionneurs) : à concevoir comme un
  module séparé, local au téléphone, avec confirmation physique, jamais piloté par le
  réseau de télémétrie.

## Limites connues

- Pas encore testé sur un véhicule ni un ELM327 réels : la validation repose sur le
  simulateur (Kotlin et Python) et les tests.
- Les clones ELM327 « v2.1 » bon marché ignorent parfois `ATH1` ou les requêtes multi-PID ;
  l'application s'adapte (en-têtes désactivés, repli unitaire) mais certains adaptateurs
  restent instables.
- Seuls les PID génériques SAE J1979 sont lus ; les PID constructeur (mode 22) ne le sont pas.
- Mosquitto, TLS 8883 et Tailscale sont documentés mais pas encore déployés sur le serveur.
