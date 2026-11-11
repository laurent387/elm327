package com.overmind.obdgateway.ui

import android.content.Context
import android.content.res.Configuration
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Profil d'écran détecté à l'exécution : aucune dimension n'est supposée (téléphone, tablette,
 * autoradio 1024×600 à densité variable…). Les écrans en déduisent leur disposition et des
 * tailles proportionnelles à la place disponible.
 */
data class DisplayProfile(
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int,
    val widthDp: Int,
    val heightDp: Int,
    val fontScale: Float,
) {
    val landscape get() = widthDp > heightDp
    /** Hauteur utile réduite (autoradio paysage, téléphone couché) : navigation latérale, moins de marges. */
    val compactHeight get() = heightDp < 480
    /** Largeur suffisante pour plusieurs colonnes. */
    val wide get() = widthDp >= 700

    /** Facteur d'échelle rapporté à une référence 1024×600 dp (autoradio mdpi), borné. */
    val scale: Float
        get() = minOf(widthDp / 1024f, heightDp / 600f).coerceIn(0.55f, 1.4f)

    fun dp(base: Float): Dp = (base * scale).dp
    /** Taille de texte proportionnelle, sans descendre sous un plancher lisible en voiture. */
    fun sp(base: Float, min: Float = 11f): TextUnit = maxOf(base * scale, min).sp

    val densityLabel: String
        get() = when {
            densityDpi <= DisplayMetrics.DENSITY_LOW -> "ldpi"
            densityDpi <= DisplayMetrics.DENSITY_MEDIUM -> "mdpi"
            densityDpi <= DisplayMetrics.DENSITY_HIGH -> "hdpi"
            densityDpi <= DisplayMetrics.DENSITY_XHIGH -> "xhdpi"
            densityDpi <= DisplayMetrics.DENSITY_XXHIGH -> "xxhdpi"
            else -> "xxxhdpi"
        }

    /** Résumé lisible (réglages, journal) : sert à confirmer les caractéristiques réelles de l'écran. */
    val summary get() = "${widthPx}×${heightPx} px · $densityDpi dpi ($densityLabel) · ${widthDp}×${heightDp} dp" +
        (if (fontScale != 1f) " · police ×$fontScale" else "")

    companion object {
        fun from(ctx: Context, cfg: Configuration): DisplayProfile {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
            return DisplayProfile(
                widthPx = dm.widthPixels, heightPx = dm.heightPixels, densityDpi = cfg.densityDpi,
                widthDp = cfg.screenWidthDp, heightDp = cfg.screenHeightDp, fontScale = cfg.fontScale,
            )
        }
    }
}

val LocalDisplayProfile = compositionLocalOf { DisplayProfile(1080, 2340, 420, 411, 891, 1f) }

@Composable
fun rememberDisplayProfile(): DisplayProfile {
    val ctx = LocalContext.current
    val cfg = LocalConfiguration.current
    return remember(cfg.screenWidthDp, cfg.screenHeightDp, cfg.densityDpi, cfg.fontScale) {
        DisplayProfile.from(ctx, cfg).also { Log.i("OvermindDisplay", "écran : ${it.summary}") }
    }
}
