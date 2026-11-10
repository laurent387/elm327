package com.overmind.obdgateway.obd.dtc

/**
 * Descriptions françaises indicatives des codes génériques SAE les plus fréquents.
 * Un code ne désigne jamais à lui seul une pièce défectueuse : l'analyse est faite par
 * l'agent Overmind Mechanic côté serveur. Les codes constructeur (P1xxx, P3xxx, Bxxxx…)
 * renvoient seulement leur famille.
 */
object DtcDescriptions {

    private val known = mapOf(
        "P0010" to "Actionneur de calage d'arbre à cames admission, banc 1 — circuit",
        "P0011" to "Calage arbre à cames admission banc 1 trop avancé / performance",
        "P0016" to "Corrélation vilebrequin / arbre à cames banc 1 capteur A",
        "P0030" to "Chauffage sonde O2 B1S1 — circuit",
        "P0036" to "Chauffage sonde O2 B1S2 — circuit",
        "P0068" to "Corrélation MAP / MAF / position papillon",
        "P0087" to "Pression rampe carburant trop basse",
        "P0088" to "Pression rampe carburant trop haute",
        "P0100" to "Débitmètre d'air (MAF) — circuit",
        "P0101" to "Débitmètre d'air (MAF) — plage/performance",
        "P0102" to "Débitmètre d'air (MAF) — signal bas",
        "P0103" to "Débitmètre d'air (MAF) — signal haut",
        "P0105" to "Capteur pression absolue collecteur (MAP) — circuit",
        "P0106" to "Capteur MAP / BARO — plage/performance",
        "P0107" to "Capteur MAP — signal bas",
        "P0108" to "Capteur MAP — signal haut",
        "P0110" to "Capteur température air admission — circuit",
        "P0113" to "Capteur température air admission — signal haut",
        "P0115" to "Capteur température liquide de refroidissement — circuit",
        "P0116" to "Capteur température liquide — plage/performance",
        "P0117" to "Capteur température liquide — signal bas",
        "P0118" to "Capteur température liquide — signal haut",
        "P0120" to "Capteur position papillon A — circuit",
        "P0121" to "Capteur position papillon A — plage/performance",
        "P0128" to "Température liquide sous le seuil du thermostat",
        "P0130" to "Sonde O2 B1S1 — circuit",
        "P0133" to "Sonde O2 B1S1 — réponse lente",
        "P0135" to "Chauffage sonde O2 B1S1 — circuit",
        "P0141" to "Chauffage sonde O2 B1S2 — circuit",
        "P0171" to "Mélange trop pauvre, banc 1",
        "P0172" to "Mélange trop riche, banc 1",
        "P0174" to "Mélange trop pauvre, banc 2",
        "P0175" to "Mélange trop riche, banc 2",
        "P0190" to "Capteur pression rampe carburant — circuit",
        "P0234" to "Suralimentation excessive (overboost)",
        "P0299" to "Suralimentation insuffisante (underboost)",
        "P0300" to "Ratés d'allumage aléatoires / multiples cylindres",
        "P0301" to "Ratés d'allumage cylindre 1",
        "P0302" to "Ratés d'allumage cylindre 2",
        "P0303" to "Ratés d'allumage cylindre 3",
        "P0304" to "Ratés d'allumage cylindre 4",
        "P0305" to "Ratés d'allumage cylindre 5",
        "P0306" to "Ratés d'allumage cylindre 6",
        "P0325" to "Capteur de cliquetis 1 — circuit",
        "P0335" to "Capteur position vilebrequin — circuit",
        "P0340" to "Capteur position arbre à cames — circuit",
        "P0400" to "Recirculation des gaz (EGR) — débit",
        "P0401" to "EGR — débit insuffisant",
        "P0402" to "EGR — débit excessif",
        "P0420" to "Efficacité catalyseur sous le seuil, banc 1",
        "P0430" to "Efficacité catalyseur sous le seuil, banc 2",
        "P0440" to "Système d'évaporation (EVAP) — dysfonctionnement",
        "P0442" to "EVAP — petite fuite détectée",
        "P0455" to "EVAP — fuite importante détectée",
        "P0500" to "Capteur de vitesse véhicule",
        "P0505" to "Régulation du ralenti — dysfonctionnement",
        "P0506" to "Ralenti plus bas que prévu",
        "P0507" to "Ralenti plus haut que prévu",
        "P0562" to "Tension système basse",
        "P0563" to "Tension système haute",
        "P0600" to "Liaison série / communication calculateur",
        "P0700" to "Défaut boîte de vitesses signalé (voir TCM)",
        "P2002" to "Efficacité filtre à particules sous le seuil, banc 1",
        "P2004" to "Volets de collecteur d'admission bloqués ouverts, banc 1",
        "P2263" to "Performance système de suralimentation",
        "P242F" to "Restriction filtre à particules — accumulation de cendres",
        "P2463" to "Filtre à particules — accumulation de suies",
    )

    fun describe(code: String): String {
        known[code]?.let { return it }
        if (code.length != 5) return "Code inconnu"
        val system = when (code[0]) {
            'P' -> "Groupe motopropulseur"
            'C' -> "Châssis"
            'B' -> "Carrosserie"
            'U' -> "Réseau / communication"
            else -> "?"
        }
        val generic = code[1] == '0' || (code[0] == 'P' && code[1] == '2')
        val family = if (code[0] == 'P') when (code[2]) {
            '0', '1', '2' -> "dosage air/carburant"
            '3' -> "allumage / ratés"
            '4' -> "contrôle des émissions"
            '5' -> "vitesse / ralenti / entrées auxiliaires"
            '6' -> "calculateur / sorties"
            '7', '8', '9' -> "transmission"
            else -> "autre"
        } else ""
        return "$system — ${if (generic) "code générique" else "code constructeur"}${if (family.isNotEmpty()) ", $family" else ""}"
    }
}
