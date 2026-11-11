# Protocole Kitt (copilote vocal) — v1

Contrat entre l'appli autoradio **Overmind Kitt** et le service `overmind_copilot`
sur le serveur Overmind (GE75).

## Transport

- WebSocket **TLS** `wss://<IP publique>:38097/copilot` (redirection Freebox → GE75:8097,
  certificat de la CA privée Overmind, comme MQTT) ; `ws://127.0.0.1:8097` en clair
  uniquement en local (tests, `adb reverse`).
- Trames **texte** = JSON UTF-8 ; trames **binaires** = audio.
- Audio montant : PCM 16 bits signé little-endian, mono, 16 000 Hz.
- Audio descendant : un fichier WAV complet (mono, 16 bits, fréquence dans l'en-tête).
- L'autoradio n'envoie le micro **qu'après** le mot de réveil, détecté hors ligne.

## Messages autoradio → Overmind

| `type` | Champs | Rôle |
|---|---|---|
| `hello` | `token`, `device_id`, `vehicle_id`, `app_version`, `remote` | Premier message, obligatoire. Jeton portée `copilot`. `remote` : télécommande autorisée dans les réglages Kitt. |
| `utterance_start` | `id`, `sample_rate` (16000), `wake`, `context` | Début d'un énoncé ; suivi de trames binaires PCM. `wake: true` si déclenché par le mot de réveil (l'audio inclut alors ~1 s avant la détection). |
| `utterance_end` | `id` | Fin de l'énoncé : transcription puis réponse. |
| `text` | `id`, `text`, `context` | Énoncé déjà écrit (tests, saisie clavier). |
| `action_result` | `id`, `ok`, `detail` | Résultat d'une action exécutée par l'autoradio (journal). |
| `remote_result` | `id`, `ok`, `data` ou `error` | Réponse à une commande `remote` (voir Télécommande). |
| `update_get` | `id` (nonce), `offset` | Demande le morceau suivant d'une mise à jour préparée. |
| `ping` | — | Maintien de connexion → `pong`. |

`context` (tout est facultatif, rien n'est inventé côté serveur si absent) :

```json
{
  "time": "2026-11-10T23:40:00+01:00",
  "location": {"lat": 48.84, "lon": 2.37, "speed_kmh": 42, "heading_deg": 180, "accuracy_m": 8},
  "media": {"playing": true, "title": "…", "artist": "…"},
  "moving": true
}
```

## Mot de réveil en deux étapes

1. **Autoradio, hors ligne** : Vosk petit modèle FR avec une grammaire réduite
   `["kit", "[unk]"]`, sur les résultats finaux uniquement (après un silence). Rapide,
   mais confond « Kitt » avec « quitte » ou « qui t'a ».
2. **Serveur** : la transcription complète (vocabulaire entier) doit commencer par le
   mot de réveil (`kit`, ou `hé`/`et`/`ok`/`dis` + `kit`/`quitte`). Sinon → `ignored`.

## Messages Overmind → autoradio

| `type` | Champs | Rôle |
|---|---|---|
| `welcome` | `assistant`, `sample_rate`, `server_version` | Réponse à `hello`. |
| `transcript` | `id`, `text` | Ce que le serveur a compris. Vide = rien compris. |
| `ignored` | `id`, `reason` | Faux réveil : la transcription complète ne commence pas par le mot de réveil (« quitte l'autoroute »). Pas de réponse ni d'audio ; l'autoradio se tait. |
| `reply` | `id`, `text`, `intent`, `action` | Réponse à dire et action éventuelle pour l'autoradio. |
| `audio` | `id`, `format` (`wav`), `bytes` | Annonce la trame binaire WAV qui suit immédiatement. |
| `done` | `id`, `timings` (`stt_ms`, `llm_ms`, `tts_ms`, `total_ms`) | Fin du traitement de l'énoncé. |
| `error` | `id`, `code`, `message` | Erreur ; l'autoradio peut répondre localement. |
| `pong` | — | |
| `remote` | `id`, `cmd`, `args` | Commande de télécommande (voir ci-dessous). |
| `update_data` | `id`, `offset`, `bytes`, `eof` ou `error` | Annonce la trame binaire du morceau d'APK demandé (aucune trame si `error`). |

Une `reply` avec `intent: "remote_say"` (sans `done`) est une phrase envoyée par la télécommande.

### Actions (`reply.action`)

L'autoradio n'exécute que ces types ; tout autre type est ignoré.

| `type` | Champs | Exécution autoradio |
|---|---|---|
| `media` | `command` ∈ `play`, `pause`, `toggle`, `next`, `previous`, `volume_up`, `volume_down` | Touche média Android |
| `navigate` | `destination` | Appli GPS (`google.navigation:` / `geo:`) |
| `call` | `contact` | Appel (`ACTION_CALL`) — n'est émis **qu'après** confirmation vocale (« oui ») |

**Aucune action ne concerne le véhicule** : il n'existe aucun chemin du serveur vers le
bus OBD. Les demandes d'effacement de défauts, d'actionneurs ou de codage sont refusées
par le serveur avant même d'interroger le modèle.

## Télécommande

Le serveur MCP `overmind_copilot.remote_mcp` (GE75, **127.0.0.1:8789 uniquement**, jeton portée
`remote`) envoie des commandes à l'autoradio dans cette même WebSocket, ouverte par
l'autoradio : aucun port n'est ouvert sur l'autoradio, rien n'est redirigé vers le MCP.

`remote.cmd` — liste FERMÉE, vérifiée par le serveur et par l'autoradio (toute autre valeur est
refusée) ; aucune ne touche le véhicule ni n'exécute de commande arbitraire :

| `cmd` | `args` | Effet sur l'autoradio |
|---|---|---|
| `status` | — | appli, Kitt, passerelle OBD, réseau, audio, mémoire, mise à jour |
| `display` | — | écran détecté (px, dpi, dp, police) |
| `logs` | `lines`, `grep` | logcat du processus Kitt uniquement |
| `screenshot` | — | JPEG de la fenêtre Kitt (seulement au premier plan) |
| `list_apps` | — | applis lançables |
| `media` | `command` | touche média / volume |
| `launch_app` | `package` | ouvre l'appli |
| `restart` | — | relance du processus Kitt |
| `update` | `nonce`, `sha256`, `size` | téléchargement par `update_get` / `update_data` (morceaux de 512 Kio), vérification SHA-256 et package, puis installeur Android : **confirmation à l'écran** |

Les actions (non-lectures) sont signalées par un message à l'écran. Le conducteur peut couper la
télécommande dans les réglages Kitt (`hello.remote = false` → toute commande est refusée).
