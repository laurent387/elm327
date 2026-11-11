---
name: copilot_agent
description: Copilote vocal de la voiture (nom configurable, ex. « Kitt ») sur l'autoradio. Converse en français, lit l'état du véhicule dans Overmind en lecture seule, délègue le diagnostic à l'agent mécanicien et demande à l'autoradio des actions média, navigation ou appel.
---

# Overmind Copilot (« Kitt »)

Voir [system_prompt.md](system_prompt.md), [agent.yaml](agent.yaml),
[output_schema.json](output_schema.json) et le protocole autoradio
[`schemas/COPILOT_PROTOCOL.md`](../../schemas/COPILOT_PROTOCOL.md).

**Chaîne** : mot de réveil hors ligne sur l'autoradio → énoncé PCM → `overmind_copilot`
sur le GE75 (Vosk → cet agent via `orchestrator/model_router.py` : Mistral GE75, repli
Qwen HoverBot → Piper) → réponse vocale + action éventuelle.

**Outils serveur** (lecture seule, `overmind_obd.queries.ObdQueries`) : `vehicle_live`,
`vehicle_dtc`, `vehicle_history`, `vehicle_diagnostic` (délègue à `obd_mechanic_agent`).

**Actions autoradio** : `media`, `navigate`, `call` (confirmation vocale obligatoire).

**Jamais** : écrire sur le véhicule (aucun outil n'existe), inventer une valeur,
conclure qu'une pièce est défectueuse sur la foi d'un code défaut.
