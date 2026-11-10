# Guide réseau : Freebox, Tailscale et Internet direct

Objectif : le téléphone (Wi-Fi, 4G ou 5G, n'importe où) publie en MQTT vers le broker
du serveur Overmind à la maison. **Le téléphone initie toujours la connexion** ; le
serveur n'ouvre jamais de connexion vers le téléphone, et aucun topic ne descend vers lui.

```text
Téléphone (app Overmind OBD) ──MQTT──▶ Mosquitto (serveur Overmind) ──▶ overmind_obd (ingestion, API :8095, MCP :8796)
                                         ▲ 1883 sur IP Tailscale (mode A)
                                         ▲ 8883 TLS via la Freebox (mode B)
```

Ne jamais exposer sur Internet : l'API `:8095`, le serveur MCP `:8796`, le port 1883
en clair, ni aucun service OBD brut.

---

## Mode A — Tailscale (recommandé)

Aucun port ouvert sur la Freebox. Tailscale crée un réseau privé chiffré (WireGuard)
entre le téléphone et le serveur, quelle que soit la connexion du téléphone.

### 1. Serveur Overmind (GE75)

```bash
curl -fsSL https://tailscale.com/install.sh | sh
sudo tailscale up
tailscale ip -4            # ex. 100.101.102.103 → à reporter dans mosquitto.conf
tailscale status           # nom MagicDNS, ex. ge75.tail1234.ts.net
```

### 2. Broker Mosquitto

```bash
sudo apt install mosquitto mosquitto-clients
sudo mkdir -p /etc/mosquitto/overmind
sudo cp deploy/mosquitto/mosquitto.conf /etc/mosquitto/conf.d/overmind-obd.conf
sudo cp deploy/mosquitto/acl.example /etc/mosquitto/overmind/acl
# Remplacer 100.64.0.1 (EXEMPLE) par l'IP de `tailscale ip -4` ; commenter le listener 8883
# si le mode B n'est pas utilisé.
sudo deploy/mosquitto/add_gateway.sh android_01 slk_r171     # crée l'utilisateur + ACL, affiche le mot de passe une fois
sudo deploy/mosquitto/add_gateway.sh --ingest                 # compte lecture seule de l'ingestion
```

Mosquitto ne peut écouter sur l'IP Tailscale qu'une fois `tailscaled` démarré. Ajouter
un ordre de démarrage :

```bash
sudo systemctl edit mosquitto
# [Unit]
# After=tailscaled.service
# Wants=tailscaled.service
sudo systemctl restart mosquitto
```

Pare-feu (si `ufw` est actif) : n'autoriser 1883 que depuis l'interface Tailscale.

```bash
sudo ufw allow in on tailscale0 to any port 1883 proto tcp
```

### 3. Téléphone

1. Installer **Tailscale** (Play Store), se connecter au même compte (tailnet).
2. Dans Tailscale, activer « Toujours actif » (Android → VPN → Tailscale → VPN permanent)
   pour que la 4G/5G passe par le tailnet même écran éteint.
3. Dans Overmind OBD → Réglages :
   - Mode réseau : **Tailscale**
   - Adresse : nom MagicDNS (`ge75.tail1234.ts.net`) ou IP `100.x.y.z`
   - Port : `1883` ; TLS : facultatif (WireGuard chiffre déjà) — activer si vous avez
     configuré le listener 8883 avec une CA privée (coller la CA dans « Certificat CA »).
   - Identifiant / mot de passe : ceux de `add_gateway.sh` (identifiant = `gateway_id`).

Test depuis un autre appareil du tailnet :

```bash
mosquitto_sub -h 100.101.102.103 -u overmind-ingest -P '…' -t 'overmind/vehicles/+/#' -v
```

---

## Mode B — Accès Internet direct (Freebox)

À n'utiliser que si Tailscale est impossible. MQTT **TLS uniquement**, port 8883.
L'appli refuse le MQTT en clair dans ce mode.

### 1. Nom de domaine et certificat valide

Le téléphone vérifie strictement le certificat et le nom d'hôte : il faut un nom de
domaine pointant vers l'IP publique de la Freebox (IP fixe Free, ou DynDNS) et un
certificat reconnu (Let's Encrypt).

```bash
sudo apt install certbot
sudo certbot certonly --standalone -d obd.mondomaine.fr     # EXEMPLE de domaine
sudo install -d -m 750 -o mosquitto /etc/mosquitto/overmind/tls
sudo cp /etc/letsencrypt/live/obd.mondomaine.fr/{fullchain,privkey}.pem /etc/mosquitto/overmind/tls/
sudo chown mosquitto /etc/mosquitto/overmind/tls/*.pem
```

(Le défi HTTP de certbot nécessite temporairement le port 80 ; sinon utiliser un défi DNS.
Prévoir un hook de renouvellement qui recopie les fichiers et recharge Mosquitto.)

### 2. Redirection de port Freebox

1. `mafreebox.freebox.fr` → Paramètres de la Freebox → Mode avancé → **Gestion des ports**.
2. Ajouter une redirection :
   - IP de destination : IP locale du serveur Overmind (bail DHCP **fixe** conseillé :
     Paramètres → DHCP → Baux statiques).
   - Protocole : **TCP** ; port de début/fin : **8883** ; port de destination : **8883**.
   - Commentaire : « MQTT TLS Overmind OBD ».
3. **Ne rediriger que 8883.** Pas 1883, pas 8095, pas 8796.
4. Sur les Freebox récentes, l'IPv4 peut être partagée (« IP full-stack » à demander dans
   l'espace abonné Free) : sans IP full-stack, les ports disponibles sont restreints.

### 3. Téléphone

- Mode réseau : **Internet direct** ; adresse `obd.mondomaine.fr` ; port `8883`.
- TLS forcé ; laisser « Certificat CA » vide (certificat public).
- Identifiant / mot de passe propres à ce téléphone (un compte par passerelle).

### Durcissement

- Un compte MQTT par téléphone ; ACL en écriture seule sur ses propres topics
  (`deploy/mosquitto/acl.example`). Révoquer = supprimer la ligne du `passwd`.
- `allow_anonymous false`, `message_size_limit`, `max_queued_messages` (déjà dans la conf).
- Surveiller `/var/log/mosquitto/mosquitto.log` (tentatives d'authentification).
- Envisager `fail2ban` sur ce journal.

---

## Comportement hors couverture

En tunnel, parking souterrain ou zone blanche, l'appli continue d'acquérir et stocke
les messages dans son outbox SQLite (jusqu'à 200 000 messages ; au-delà la télémétrie la
plus ancienne est abandonnée, jamais les DTC/statuts). À la reconnexion, elle les renvoie
**dans l'ordre**, avec leurs `message_id`, `sequence` et horodatages d'origine ; le serveur
ignore les doublons. Le broker publie le LWT `offline` si le téléphone disparaît.

## Écran éteint

La session tourne dans un service au premier plan (notification permanente) avec un
WakeLock partiel limité à la session. Sur certains constructeurs (Samsung, Xiaomi…),
exclure l'appli de l'optimisation batterie (bouton dans Réglages) et de la mise en veille
des applis inutilisées.
