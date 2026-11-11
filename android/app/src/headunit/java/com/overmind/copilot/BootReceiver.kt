package com.overmind.copilot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Démarre Kitt au démarrage de l'autoradio, et après une mise à jour de l'appli (télécommande
 * Overmind : l'installeur arrête le processus, Kitt revient seul). Sur les autoradios MTCE, la mise en veille (contact
 * coupé) n'est pas un redémarrage : le firmware Hal9k propose en plus une liste d'applis à
 * relancer au réveil (voir docs/AUTORADIO.md).
 * Android 14+ interdit un service « micro » lancé au démarrage : on ne le tente que jusqu'à 13
 * (l'autoradio HF S8 est en Android 9).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (Build.VERSION.SDK_INT >= 34) return
        if (!CopilotSettings(ctx).config.value.configured) return
        runCatching { CopilotService.start(ctx) }.onFailure { Log.w("Kitt", "démarrage automatique impossible", it) }
    }
}
