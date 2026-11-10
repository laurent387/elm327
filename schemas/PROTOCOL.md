# Protocole de télémétrie Overmind OBD — v1.0

Contrat entre la passerelle Android (**Overmind OBD Gateway**) et le service
d'ingestion Overmind. Les schémas JSON formels sont dans ce dossier
(`overmind_obd.schema.json`, `pid_catalog.json`).

## Transport

- MQTT 3.1.1, **QoS 1**, `retain=false` (sauf `status` et `health`, publiés `retain=true`).
- Connexion **sortante** du téléphone vers le broker (aucune connexion entrante
  vers le téléphone).
- `client_id = gateway_id` = nom d'utilisateur MQTT, `cleanSession=true` : la passerelle ne
  s'abonne à rien ; la fiabilité vient de l'outbox SQLite locale (suppression après PUBACK)
  et de la déduplication par `message_id` côté serveur.
- Dernières volontés (LWT, retain) sur `.../gateway/health` : `{"state":"offline"}`.

## Topics

```text
overmind/vehicles/{vehicle_id}/obd/telemetry      mesures temps réel
overmind/vehicles/{vehicle_id}/obd/dtc            codes défauts
overmind/vehicles/{vehicle_id}/obd/status         état ELM327 / véhicule / PID supportés (retain)
overmind/vehicles/{vehicle_id}/gateway/health     santé de la passerelle (+ LWT)
```

`vehicle_id` et `gateway_id` : `^[a-z0-9_\-]{1,64}$`.

**Il n'existe aucun topic descendant (serveur → téléphone).** La passerelle ne
s'abonne à rien : aucune commande réseau ne peut atteindre le bus véhicule.

## Enveloppe commune

| Champ | Type | Rôle |
|---|---|---|
| `schema_version` | `"1.0"` | version du contrat |
| `message_id` | UUID v4 | **clé de déduplication** côté serveur |
| `vehicle_id` | str | doit être égal au segment du topic |
| `gateway_id` | str | doit être autorisé pour ce véhicule (registre serveur) |
| `session_id` | UUID | une session = une période de diagnostic démarrée par l'utilisateur (survit aux reconnexions Bluetooth) |
| `sequence` | int ≥ 0 | compteur monotone par passerelle, persistant |
| `timestamp` | ISO-8601 UTC | heure de création du message (téléphone) |
| `engine_type` | `petrol` \| `diesel` \| `unknown` | |
| `kind` | `telemetry` \| `dtc` \| `status` \| `health` | redondant avec le topic |

Un message bufferisé hors-ligne garde son `message_id`, sa `sequence` et son
`timestamp` d'origine : le rejeu après coupure ne crée pas de doublon.

## Télémétrie (`kind = telemetry`)

```json
{
  "schema_version": "1.0",
  "kind": "telemetry",
  "message_id": "8f0e…",
  "vehicle_id": "slk_r171",
  "gateway_id": "android_01",
  "session_id": "1c2d…",
  "timestamp": "2026-11-10T16:45:00.412Z",
  "sequence": 12345,
  "engine_type": "petrol",
  "data": { "rpm": 3500, "map_kpa_absolute": 98, "engine_load_pct": 78.0 },
  "measurements": [
    {"key": "rpm", "pid": "010C", "value": 3500, "unit": "rpm",
     "ts": "2026-11-10T16:45:00.301Z", "source": "obd2:7E8", "status": "ok"},
    {"key": "maf_g_s", "pid": "0110", "value": null, "unit": "g/s",
     "ts": "2026-11-10T16:45:00.390Z", "source": "obd2", "status": "no_data"}
  ],
  "context": {"aspiration": "supercharger", "map_sensor_location": "unknown"}
}
```

- `data` : vue compacte, **uniquement les mesures `status = ok`** (compatible
  avec l'exemple du cahier des charges).
- `measurements` : vue complète. `status` ∈ `ok`, `no_data`, `error`,
  `unsupported`, `stale`. `value` est `null` dès que `status ≠ ok` :
  **aucune valeur n'est jamais inventée**.
- `source` : `obd2:<en-tête ECU>` (ex. `obd2:7E8`) quand les en-têtes sont
  connus, `obd2` sinon, `derived:<pid>+<pid>` pour une grandeur calculée.
- Grandeur dérivée `boost_kpa_relative` = `map_kpa_absolute − baro_kpa`,
  **seulement si MAP est lu dans cette trame et BARO a été lu il y a moins de
  60 s** (BARO varie lentement, il est interrogé moins souvent).
  Elle est publiée avec `context.map_sensor_location` (`post_compressor`,
  `pre_throttle`, `intake_manifold`, `unknown`) : un PID MAP générique ne
  garantit pas une mesure après le compresseur.

Clés connues : voir `pid_catalog.json` (clé, PID, unité, formule).

## Codes défauts (`kind = dtc`)

```json
"dtc": {
  "mil_on": true,
  "stored":    [{"code": "P0101", "ecu": "7E8"}],
  "pending":   [],
  "permanent": null,
  "read_at": "2026-11-10T16:45:02Z"
}
```

`permanent = null` signifie « mode 0A non supporté par le calculateur » ;
`[]` signifie « supporté, aucun code ».

## État (`kind = status`, retain)

```json
"status": {
  "state": "connected",
  "elm": {"version": "ELM327 v1.5", "protocol": "6", "protocol_name": "ISO 15765-4 CAN (11 bit ID, 500 kbaud)", "voltage_v": 12.6},
  "vin": "WDB1714…",
  "supported_pids": ["0104", "0105", "010B", "010C"],
  "polled_pids": ["010C", "010B", "0104"],
  "poll_interval_ms": 500
}
```

`state` ∈ `connecting`, `connected`, `disconnected`, `no_vehicle`, `error`.

## Santé passerelle (`kind = health`)

```json
"health": {
  "state": "online",
  "app_version": "0.1.0",
  "bt_connected": true,
  "network": "cellular",
  "outbox_pending": 0,
  "battery_pct": 81,
  "uptime_s": 3600
}
```

LWT : `{"schema_version":"1.0","kind":"health","gateway_id":"…","vehicle_id":"…","health":{"state":"offline"}}`
(sans `message_id` : le serveur l'accepte comme cas particulier).

## Règles côté serveur

1. Rejeter tout message dont `vehicle_id` ≠ segment du topic.
2. Rejeter tout `gateway_id` non enregistré pour ce `vehicle_id`.
3. Ignorer silencieusement un `message_id` déjà vu (QoS 1 = au moins une fois).
4. Ne jamais republier vers le téléphone ni relayer de commande OBD.
