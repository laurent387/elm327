# Overmind OBD — côté serveur

Reçoit la télémétrie des passerelles Android **Overmind OBD Gateway** (ELM327 → MQTT), la valide,
la stocke, la diffuse en temps réel et l'expose aux agents Overmind via une API et un serveur MCP
**en lecture seule**. Aucun composant ne peut envoyer de commande au véhicule.

```text
passerelle Android ──MQTT QoS1 (Tailscale ou TLS 8883)──▶ Mosquitto ──▶ ingest ──▶ SQLite (WAL)
                                                                         │            │
                                                                     bus interne   queries ──▶ API :8095 (+ /obd/ live, WebSocket)
                                                                         │                  └─▶ MCP :8796 (outils obd_*)
                                                                    WebSocket / agents
```

Contrat de messages : [`../schemas/PROTOCOL.md`](../schemas/PROTOCOL.md) (+ `overmind_obd.schema.json`,
`pid_catalog.json`).

## Modules (`overmind_obd/`)

| Module | Rôle |
|---|---|
| `registry.py` | Registre YAML passerelles ↔ véhicules + profil moteur (essence/diesel, atmo/turbo/compresseur, emplacement du capteur MAP, fabricant/motorisation) |
| `validation.py` | Schéma JSON + règles : `vehicle_id` = topic, passerelle autorisée, LWT `health` sans `message_id` |
| `store.py` | SQLite WAL : messages bruts (`message_id` UNIQUE → déduplication), mesures, dernières valeurs, DTC, statuts, santé, sessions, passerelles |
| `ingest.py` | Abonné MQTT paho v2 (session persistante, QoS 1, reconnexion), normalisation, bus d'événements, détection de perte (`lost` après N s) |
| `api.py` | FastAPI + WebSocket `/ws/obd/live` + page `/obd/` (visualisation temps réel) |
| `analysis.py`, `knowledge.py`, `knowledge/` | Analyse à règles (hypothèses vs confirmés, confiance, limites) + base DTC FR (74 codes) + fiches moteur |
| `report.py` | Rapport de diagnostic Markdown en français |
| `mcp_server.py` | Serveur MCP lecture seule (9 outils `obd_*`) |
| `tokens.py` | Jetons API/MCP (empreintes SHA-256 seulement, même convention que Robot Face) |

## Installation

```bash
cd ~/elm327/overmind
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
mkdir -p ~/.config/overmind
cp config/gateways.example.yaml ~/.config/overmind/obd_gateways.yaml   # puis adapter véhicule/passerelle
.venv/bin/python -m overmind_obd.tokens create dashboard --scopes api        # jeton affiché une seule fois
.venv/bin/python -m overmind_obd.tokens create orchestrator --scopes api,mcp
```

Broker : Mosquitto (paquet système, non installé par ce dépôt). Configuration fournie dans
[`../deploy/mosquitto/`](../deploy/mosquitto/) : listeners local + Tailscale + TLS 8883, `allow_anonymous false`,
un compte par passerelle (écriture seule sur ses topics, aucune lecture), compte `overmind-ingest` en lecture seule :

```bash
sudo deploy/mosquitto/add_gateway.sh --ingest
sudo deploy/mosquitto/add_gateway.sh android_01 slk_r171
```

## Lancement

```bash
# API + ingestion MQTT embarquée (un seul processus, nécessaire pour le WebSocket temps réel)
OVERMIND_OBD_MQTT_USER=overmind-ingest OVERMIND_OBD_MQTT_PASSWORD=… .venv/bin/python -m overmind_obd.api
# → http://127.0.0.1:8095/obd/  (saisir le jeton « dashboard »)

# Serveur MCP (processus séparé, lit la même base)
.venv/bin/python -m overmind_obd.mcp_server             # http://127.0.0.1:8796/mcp
```

Services systemd **utilisateur** (comme `hovermind-dashboard.service`) : copier
`../deploy/systemd/overmind-obd-{api,mcp}.service` dans `~/.config/systemd/user/`, créer
`~/.config/overmind/obd.env` depuis `obd.env.example`, puis `systemctl --user enable --now overmind-obd-api overmind-obd-mcp`.
`overmind-obd-ingest` est l'alternative « ingestion seule » (sans API ni WebSocket ; en conflit avec `-api`).

### Variables d'environnement

| Variable | Défaut |
|---|---|
| `OVERMIND_OBD_DB` | `~/.local/share/overmind/obd.sqlite3` |
| `OVERMIND_OBD_REGISTRY` | `~/.config/overmind/obd_gateways.yaml` (sinon l'exemple) |
| `OVERMIND_OBD_MQTT_HOST` / `_PORT` / `_USER` / `_PASSWORD` / `_TLS` / `_CA` | `127.0.0.1` / `1883` / — / — / `0` / — |
| `OVERMIND_OBD_LOST_AFTER_S` | `30` (passerelle muette → `lost`) |
| `OVERMIND_OBD_API_HOST` / `_PORT` | `127.0.0.1` / `8095` |
| `OVERMIND_OBD_EMBED_INGEST` | `1` |
| `OVERMIND_OBD_TOKENS` | jetons en clair séparés par des virgules (tous scopes, jamais écrits) |
| `OVERMIND_OBD_TOKENS_FILE` | `~/.config/overmind/obd_tokens.json` |
| `OVERMIND_OBD_MCP_HOST` / `_PORT` / `_TRANSPORT` | `127.0.0.1` / `8796` / `streamable-http` (ou `stdio`) |
| `OVERMIND_OBD_MCP_ALLOWED_HOSTS` | liste `hôte:port` (protection DNS-rebinding) si exposé hors localhost |

Le MCP refuse de démarrer sur une adresse non locale sans jeton `mcp` configuré.

## API (Bearer requis sauf `/health`)

```text
GET /api/obd/v1/health
GET /api/obd/v1/vehicles
GET /api/obd/v1/vehicles/{id}/status          passerelle online/offline/lost, ELM327, santé téléphone
GET /api/obd/v1/vehicles/{id}/latest          dernières valeurs (valeur, unité, horodatage, âge, stale, source)
GET /api/obd/v1/vehicles/{id}/supported-pids
GET /api/obd/v1/vehicles/{id}/timeseries?key=rpm&last_minutes=15&limit=500
GET /api/obd/v1/vehicles/{id}/dtc             avec description FR et causes possibles
GET /api/obd/v1/vehicles/{id}/sessions
GET /api/obd/v1/sessions/{session_id}
GET /api/obd/v1/vehicles/{id}/analysis?window_minutes=15
GET /api/obd/v1/vehicles/{id}/report          Markdown
WS  /ws/obd/live    1er message {"token": "...", "vehicle_id": "slk_r171"} puis flux d'événements
```

Événements du bus / WebSocket : `telemetry`, `dtc`, `status`, `health` (`payload` = message validé) et
`gateway_state` (`online` / `offline` (LWT) / `lost`). Un agent Python dans le même processus peut
s'abonner avec `app.state.bus.subscribe(callback)`.

## Outils MCP (lecture seule, `readOnlyHint=true`)

`obd_list_vehicles`, `obd_get_connection_status`, `obd_get_supported_pids`, `obd_get_live_data`,
`obd_get_dtc`, `obd_get_history`, `obd_get_session`, `obd_analyze_engine`, `obd_generate_report`.

**ChatGPT** n'a pas d'accès automatique à ce serveur. Pour l'utiliser comme connecteur MCP distant, il
faut l'exposer en HTTPS public (ex. reverse proxy TLS ou Tailscale Funnel sur `/mcp` uniquement),
garder l'authentification par jeton, renseigner `OVERMIND_OBD_MCP_ALLOWED_HOSTS`, puis déclarer l'URL
dans les connecteurs ChatGPT (fonction réservée à certains abonnements/workspaces ; vérifier la prise en
charge de l'authentification par en-tête Bearer, sinon prévoir OAuth). Ne jamais exposer l'API, le
broker en clair ou la base.

## Intégration HoverMind (`~/hovermind`) — documentation, rien n'est modifié

**API dans le serveur :8000** (option, au lieu du processus :8095) — dans `server/app/main.py` :

```python
from overmind_obd.api import build_router            # paquet installé : pip install -e ~/elm327/overmind
from overmind_obd.queries import ObdQueries
# objets créés au démarrage : Store(), Registry.load(), TokenStore(), EventBus(), Ingestor + MqttIngestService
app.include_router(build_router(queries, tokens, bus, ingestor))
```

**MCP dans l'orchestrateur**, sur le modèle de `orchestrator/picar_backend.py` (`streamablehttp_client`) :
un `ObdBackend(url="http://127.0.0.1:8796/mcp", token=…)` qui appelle `obd_get_live_data`,
`obd_analyze_engine`… en passant `headers={"Authorization": f"Bearer {token}"}` ; déclarer ces outils
dans `policies/permissions.yaml` **non gatés** (lecture seule), accordés à l'agent `obd_mechanic`
(fiche dans `../agents/`). Aucun outil OBD n'est une actuation.

## Simulateur et passerelle factice

```bash
python3 ../tools/elm327_sim/elm327_sim.py --port 35000 --dtc P0171 --pending P0128 --multi-ecu
python3 ../tools/fake_gateway.py --elm 127.0.0.1:35000 --mqtt 127.0.0.1:1883 --duration 120 \
        --user android_01 --password …
```

Le simulateur (TCP comme un ELM327 Wi-Fi, ou `--serial-pty`) répond aux commandes AT usuelles, aux modes
01 (bitmaps de support cohérents), 03/07/0A, 09 02 (VIN multi-trame), en CAN 11 bits avec ou sans
en-têtes, et **refuse le mode 04** (effacement). Ses valeurs sont simulées. Le téléphone Android peut s'y
connecter en mode « ELM327 Wi-Fi/TCP ».

## Tests

```bash
.venv/bin/python -m pytest tests -q
```

Couvre validation/rejets, déduplication, rejeu hors ordre, séries temporelles, sessions qui survivent aux
reconnexions BT, analyse (mélange pauvre ralenti vs charge, MAP inconnu → limite, suralimentation),
outils MCP, API + WebSocket, dialogue ELM brut, et le **bout-en-bout** simulateur → fake_gateway → broker
MQTT (`amqtt` embarqué) → ingestion → API.
