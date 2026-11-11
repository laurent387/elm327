package com.overmind.copilot

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import android.provider.ContactsContract
import android.view.KeyEvent
import com.overmind.copilot.core.ClientAction
import com.overmind.copilot.core.MediaCommand
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Exécute les actions autoradio autorisées. Aucune n'agit sur le véhicule. */
class ClientActions(private val ctx: Context) {
    private val am = ctx.getSystemService(AudioManager::class.java)

    /** Renvoie (succès, détail) pour le journal Overmind. */
    fun run(a: ClientAction): Pair<Boolean, String> = runCatching {
        when (a) {
            is ClientAction.Media -> media(a.command)
            is ClientAction.Navigate -> navigate(a.destination)
            is ClientAction.Call -> call(a.contact)
        }
    }.getOrElse { false to (it.message ?: it.javaClass.simpleName) }

    private fun media(c: MediaCommand): Pair<Boolean, String> {
        when (c) {
            MediaCommand.VOLUME_UP -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
            MediaCommand.VOLUME_DOWN -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
            else -> {
                val code = when (c) {
                    MediaCommand.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
                    MediaCommand.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
                    MediaCommand.TOGGLE -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                    MediaCommand.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
                    else -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                }
                val t = SystemClock.uptimeMillis()
                am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
                am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
            }
        }
        return true to "media ${c.wire}"
    }

    private fun navigate(dest: String): Pair<Boolean, String> {
        val q = Uri.encode(dest)
        val google = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$q")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val generic = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$q")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val intent = if (google.resolveActivity(ctx.packageManager) != null) google else generic
        if (intent.resolveActivity(ctx.packageManager) == null) return false to "aucune appli de navigation"
        ctx.startActivity(intent)
        return true to "navigation vers $dest"
    }

    @SuppressLint("MissingPermission")
    private fun call(contact: String): Pair<Boolean, String> {
        val number = findNumber(contact) ?: return false to "contact introuvable : $contact"
        val canCall = ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val intent = Intent(if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:$number"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        return true to "appel $contact"
    }

    private fun findNumber(name: String): String? {
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val proj = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)
        val sel = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        ctx.contentResolver.query(uri, proj, sel, arrayOf("%$name%"), null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return null
    }
}

/** Contexte envoyé avec chaque énoncé : heure, position GPS, musique. Rien d'inventé : absent = omis. */
class ContextProvider(private val ctx: Context) : LocationListener {
    private val lm = ctx.getSystemService(LocationManager::class.java)
    private val am = ctx.getSystemService(AudioManager::class.java)
    @Volatile private var last: Location? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        runCatching {
            last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5_000L, 20f, this, Looper.getMainLooper())
        }
    }

    fun stop() { runCatching { lm.removeUpdates(this) } }

    override fun onLocationChanged(location: Location) { last = location }

    fun gpsSpeedKmh(): Double? = last?.takeIf { it.hasSpeed() && System.currentTimeMillis() - it.time < 15_000 }?.speed?.times(3.6)

    fun build(moving: Boolean?): JsonObject = buildJsonObject {
        put("time", ZonedDateTime.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm", Locale.FRANCE)))
        last?.takeIf { System.currentTimeMillis() - it.time < 120_000 }?.let { l ->
            putJsonObject("location") {
                put("lat", l.latitude); put("lon", l.longitude)
                if (l.hasSpeed()) put("speed_kmh", l.speed * 3.6)
                if (l.hasBearing()) put("heading_deg", l.bearing)
                if (l.hasAccuracy()) put("accuracy_m", l.accuracy)
            }
        }
        putJsonObject("media") { put("playing", am.isMusicActive) }
        if (moving != null) put("moving", moving)
    }
}
