package com.overmind.obdgateway.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.overmind.obdgateway.gateway.LiveValue
import com.overmind.obdgateway.ui.theme.OvermindColors
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun Section(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = OvermindColors.Surface),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title.uppercase(), color = OvermindColors.Muted, fontSize = 12.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.size(8.dp))
            content()
        }
    }
}

@Composable
fun StatusDot(ok: Boolean?, label: String, detail: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
        Box(
            Modifier.size(12.dp).background(
                when (ok) { true -> OvermindColors.Ok; false -> OvermindColors.Bad; null -> OvermindColors.Amber }, CircleShape,
            ),
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(label, fontSize = 16.sp, color = OvermindColors.Text)
            if (detail != null) Text(detail, fontSize = 12.sp, color = OvermindColors.Muted)
        }
    }
}

fun formatValue(v: Double?, unit: String): String {
    if (v == null) return "—"
    val decimals = when {
        unit == "rpm" || unit == "km/h" || unit == "kPa" || unit == "°C" -> 0
        unit == "λ" -> 3
        unit == "V" -> 2
        else -> 1
    }
    return "%.${decimals}f".format(Locale.FRANCE, v)
}

/** Grande valeur lisible d'un coup d'œil (usage automobile). */
@Composable
fun BigValue(label: String, value: LiveValue?, unit: String, modifier: Modifier = Modifier, accent: Color = OvermindColors.Accent) {
    val status = value?.status
    val color = when (status) { "ok" -> accent; "stale" -> OvermindColors.Muted; else -> OvermindColors.Muted }
    Card(
        modifier = modifier.padding(4.dp),
        colors = CardDefaults.cardColors(containerColor = OvermindColors.SurfaceHi),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(12.dp).fillMaxWidth(), horizontalAlignment = Alignment.Start) {
            Text(label, color = OvermindColors.Muted, fontSize = 13.sp)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    if (status == "unsupported") "n/d" else formatValue(value?.value, unit),
                    color = color, fontSize = 40.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.width(6.dp))
                Text(unit, color = OvermindColors.Muted, fontSize = 15.sp, modifier = Modifier.padding(bottom = 7.dp))
            }
            val hint = when (status) {
                "unsupported" -> "non exposé par le véhicule"
                "stale" -> "dernière valeur (lecture en échec)"
                "no_data", "error" -> "pas de réponse"
                "pending", null -> "en attente"
                else -> null
            }
            if (hint != null) Text(hint, color = OvermindColors.Muted, fontSize = 11.sp)
        }
    }
}

@Composable
fun KeyValue(k: String, v: String?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(k, color = OvermindColors.Muted, fontSize = 14.sp)
        Text(v ?: "—", color = OvermindColors.Text, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
    }
}

fun fmtTime(ms: Long?): String = ms?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM, Locale.FRANCE).format(Date(it)) } ?: "—"
