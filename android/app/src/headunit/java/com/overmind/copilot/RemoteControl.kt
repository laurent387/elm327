package com.overmind.copilot

import android.app.Activity
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.view.PixelCopy
import android.widget.Toast
import androidx.core.content.FileProvider
import com.overmind.copilot.core.ClientAction
import com.overmind.copilot.core.MediaCommand
import com.overmind.copilot.core.RemoteCommand
import com.overmind.copilot.core.ServerMessage
import com.overmind.copilot.core.Protocol
import com.overmind.copilot.core.UpdateNonce
import com.overmind.obdgateway.BuildConfig
import com.overmind.obdgateway.ui.DisplayProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.ref.WeakReference
import java.security.MessageDigest
import kotlin.coroutines.resume

/**
 * Télécommande depuis Overmind (MCP local du GE75 → WebSocket sortante déjà ouverte).
 * Liste FERMÉE ([RemoteCommand]) ; rien ne touche le véhicule ni n'exécute de commande arbitraire.
 * Les actions sont signalées à l'écran ; une mise à jour est toujours confirmée par l'installeur Android.
 */
class RemoteControl(private val ctx: Context, private val rt: CopilotRuntime) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var updateState = "aucune"

    /** Renvoie (données, erreur) ; erreur ≠ null = échec. */
    suspend fun handle(m: ServerMessage.Remote): Pair<JsonObject?, String?> {
        val cmd = m.command ?: return null to "commande refusée par l'autoradio : ${m.raw}"
        if (!rt.settings.config.value.remoteEnabled) return null to "télécommande désactivée dans les réglages Kitt"
        Log.i(TAG, "commande ${cmd.wire} ${m.args}")
        if (!cmd.readOnly) notice("Overmind : ${cmd.wire}")
        return try {
            when (cmd) {
                RemoteCommand.STATUS -> status() to null
                RemoteCommand.DISPLAY -> display() to null
                RemoteCommand.LOGS -> logs(m.args.int("lines") ?: 200, m.args.str("grep").orEmpty()) to null
                RemoteCommand.SCREENSHOT -> screenshot()
                RemoteCommand.LIST_APPS -> listApps() to null
                RemoteCommand.MEDIA -> media(m.args.str("command"))
                RemoteCommand.LAUNCH_APP -> launch(m.args.str("package").orEmpty())
                RemoteCommand.RESTART -> restart()
                RemoteCommand.UPDATE -> update(m.args)
            }
        } catch (e: Exception) {
            Log.w(TAG, "échec ${cmd.wire}", e)
            null to (e.message ?: e.javaClass.simpleName)
        }
    }

    // --- Lecture --------------------------------------------------------------------------------
    private fun status(): JsonObject {
        val ui = rt.ui.value
        val g = rt.gateway.value
        val cfg = rt.settings.config.value
        val am = ctx.getSystemService(AudioManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        return buildJsonObject {
            putJsonObject("app") {
                put("package", ctx.packageName); put("version", BuildConfig.VERSION_NAME)
                put("version_code", BuildConfig.VERSION_CODE)
                put("uptime_s", (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()) / 1000)
                put("foreground", foreground?.get() != null)
            }
            putJsonObject("device") {
                put("manufacturer", Build.MANUFACTURER); put("model", Build.MODEL); put("android", Build.VERSION.RELEASE)
                put("sdk", Build.VERSION.SDK_INT); put("firmware", Build.DISPLAY)
                put("abis", Build.SUPPORTED_ABIS.joinToString(","))
            }
            putJsonObject("kitt") {
                put("state", ui.state.name); put("assistant", ui.assistant)
                put("last_transcript", ui.transcript); put("last_reply", ui.reply)
                ui.lastLatencyMs?.let { put("last_latency_ms", it) }
                ui.error?.let { put("error", it) }
                put("wake_enabled", cfg.wakeEnabled); put("wake_word", cfg.wakeWord)
                put("link", rt.client.state.value.name)
                rt.client.lastError.value?.let { put("link_error", it) }
            }
            putJsonObject("gateway") {
                put("obd_state", g.obdState.name); put("obd_label", g.obdState.label)
                g.sessionId?.let { put("session_id", it) }
                g.adapter?.let { put("adapter", it) }
                g.elm?.let { e ->
                    e.version?.let { put("elm_version", it) }
                    e.voltage?.let { put("elm_voltage_v", it) }
                    e.protocol?.let { put("protocol", it.toString()) }
                }
                g.vin?.let { put("vin", it) }
                put("polled_pids", g.polledPids.size); put("live_values", g.live.size)
                g.cycleMs?.let { put("cycle_ms", it) }
                g.lastError?.let { put("error", it) }
                g.lastDtc?.let { d -> put("dtc_stored", d.stored.size); put("dtc_pending", d.pending.size) }
                put("vehicle_moving", g.vehicleMoving(System.currentTimeMillis()))
            }
            putJsonObject("network") { network(this) }
            putJsonObject("audio") {
                put("music_active", am.isMusicActive)
                put("music_volume", am.getStreamVolume(AudioManager.STREAM_MUSIC))
                put("music_volume_max", am.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
                put("mode", when (am.mode) {
                    AudioManager.MODE_IN_CALL -> "call"; AudioManager.MODE_IN_COMMUNICATION -> "communication"
                    AudioManager.MODE_RINGTONE -> "ringtone"; else -> "normal"
                })
            }
            putJsonObject("memory") {
                put("available_mb", mem.availMem / 1_048_576); put("total_mb", mem.totalMem / 1_048_576)
                put("low", mem.lowMemory)
                put("app_heap_mb", (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1_048_576)
            }
            put("update", updateState)
        }
    }

    private fun network(b: kotlinx.serialization.json.JsonObjectBuilder) {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        b.put("transport", when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        })
        if (caps != null) b.put("internet_validated", caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
    }

    private fun display(): JsonObject {
        val p = DisplayProfile.from(ctx, ctx.resources.configuration)
        return buildJsonObject {
            put("width_px", p.widthPx); put("height_px", p.heightPx); put("density_dpi", p.densityDpi)
            put("density", p.densityLabel); put("width_dp", p.widthDp); put("height_dp", p.heightDp)
            put("font_scale", p.fontScale); put("landscape", p.landscape); put("summary", p.summary)
        }
    }

    /** Journal de CE processus uniquement (aucune permission READ_LOGS nécessaire ni demandée). */
    private fun logs(lines: Int, grep: String): JsonObject {
        val n = lines.coerceIn(10, 2000)
        val proc = ProcessBuilder("logcat", "-d", "-v", "time", "-t", n.toString(), "--pid", Process.myPid().toString())
            .redirectErrorStream(true).start()
        val all = proc.inputStream.bufferedReader().readLines()
        proc.waitFor()
        val kept = if (grep.isBlank()) all else all.filter { it.contains(grep, ignoreCase = true) }
        return buildJsonObject {
            put("pid", Process.myPid()); put("count", kept.size)
            putJsonArray("lines") { kept.takeLast(n).forEach { add(kotlinx.serialization.json.JsonPrimitive(it.take(500))) } }
        }
    }

    /** Capture de la fenêtre Kitt (PixelCopy) : sans root, Android ne laisse pas capturer les autres applis. */
    private suspend fun screenshot(): Pair<JsonObject?, String?> {
        val act = foreground?.get() ?: return null to "Kitt n'est pas au premier plan : capture impossible sans root"
        val bmp = withContext(Dispatchers.Main) {
            val view = act.window.decorView
            val b = Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            withTimeout(5_000) {
                suspendCancellableCoroutine<Bitmap?> { cont ->
                    PixelCopy.request(act.window, b, { r -> cont.resume(if (r == PixelCopy.SUCCESS) b else null) }, main)
                }
            }
        } ?: return null to "capture refusée par Android"
        val scaled = if (bmp.width > 1280) Bitmap.createScaledBitmap(bmp, 1280, bmp.height * 1280 / bmp.width, true) else bmp
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 75, out)
        return buildJsonObject {
            put("width", scaled.width); put("height", scaled.height)
            put("jpeg_base64", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
        } to null
    }

    private fun listApps(): JsonObject {
        val pm = ctx.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .distinctBy { it.second }.sortedBy { it.first.lowercase() }
        return buildJsonObject {
            putJsonArray("apps") {
                apps.forEach { (label, pkg) -> add(buildJsonObject { put("label", label); put("package", pkg) }) }
            }
        }
    }

    // --- Actions --------------------------------------------------------------------------------
    private suspend fun media(command: String?): Pair<JsonObject?, String?> {
        val c = MediaCommand.from(command) ?: return null to "commande musique inconnue : $command"
        val (ok, detail) = withContext(Dispatchers.Main) { rt.actions.run(ClientAction.Media(c)) }
        return if (ok) buildJsonObject { put("done", c.wire) } to null else null to detail
    }

    private suspend fun launch(pkg: String): Pair<JsonObject?, String?> {
        if (!Regex("^[A-Za-z0-9_.]{1,200}$").matches(pkg)) return null to "package invalide"
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return null to "appli introuvable : $pkg"
        withContext(Dispatchers.Main) { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        return buildJsonObject { put("launched", pkg) } to null
    }

    /** Relance complète du processus (le service redémarre l'écoute et la liaison). */
    private fun restart(): Pair<JsonObject?, String?> {
        val pi = PendingIntent.getActivity(ctx, 7, Intent(ctx, CopilotActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT)
        ctx.getSystemService(AlarmManager::class.java)
            .set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 1_500, pi)
        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        GlobalScope.launch { delay(600); Log.i(TAG, "redémarrage demandé par Overmind"); Process.killProcess(Process.myPid()) }
        return buildJsonObject { put("restarting", true); put("back_in_s", 3) } to null
    }

    /**
     * Mise à jour : l'autoradio tire l'APK par morceaux dans la WebSocket déjà ouverte (TLS, CA
     * épinglée), vérifie SHA-256 et nom de package, puis l'installeur Android demande confirmation
     * sur l'écran. Répond tout de suite ; l'avancement se lit dans `status.update` et les journaux.
     */
    private fun update(args: JsonObject): Pair<JsonObject?, String?> {
        val nonce = args.str("nonce")?.takeIf { UpdateNonce.valid(it) } ?: return null to "identifiant de mise à jour invalide"
        val sha = args.str("sha256")?.lowercase()?.takeIf { Regex("^[0-9a-f]{64}$").matches(it) } ?: return null to "empreinte absente"
        val size = args.long("size")?.takeIf { it in 1..MAX_APK } ?: return null to "taille absente ou excessive"
        if (updateState.startsWith("téléchargement")) return null to "mise à jour déjà en cours"
        updateState = "téléchargement…"
        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        GlobalScope.launch(Dispatchers.IO) {
            updateState = try { downloadAndInstall(nonce, sha, size) } catch (e: Exception) { "échec : ${e.message}" }
            Log.i(TAG, "mise à jour : $updateState")
        }
        return buildJsonObject { put("accepted", true); put("state", updateState) } to null
    }

    private val chunks = Channel<Pair<ServerMessage.UpdateData, ByteArray?>>(Channel.UNLIMITED)

    /** Morceau reçu (ou erreur, [data] = null) pour la mise à jour en cours. */
    fun onUpdateChunk(meta: ServerMessage.UpdateData, data: ByteArray?) { chunks.trySend(meta to data) }

    private suspend fun downloadAndInstall(nonce: String, sha: String, size: Long): String {
        while (chunks.tryReceive().isSuccess) Unit   // restes d'un transfert précédent
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val apk = File(dir, "kitt-update.apk")
        val md = MessageDigest.getInstance("SHA-256")
        var offset = 0L
        apk.outputStream().use { out ->
            while (true) {
                if (!rt.client.sendText(Protocol.updateGet(nonce, offset))) return "échec : liaison Overmind coupée"
                val (meta, data) = withTimeoutOrNull(30_000) { chunks.receive() } ?: return "échec : délai dépassé à $offset octets"
                if (meta.id != nonce) continue
                if (meta.error != null || data == null) return "échec : ${meta.error ?: "morceau vide"}"
                if (meta.offset != offset || data.size != meta.bytes) return "échec : morceau incohérent à $offset"
                offset += data.size
                if (offset > size) return "échec : taille inattendue"
                md.update(data); out.write(data)
                updateState = "téléchargement… ${offset * 100 / size} %"
                if (meta.eof) break
            }
        }
        if (offset != size) return "échec : taille $offset ≠ $size"
        val got = md.digest().joinToString("") { "%02x".format(it) }
        if (got != sha) { apk.delete(); return "échec : empreinte SHA-256 différente" }
        val info = ctx.packageManager.getPackageArchiveInfo(apk.path, 0)
        if (info?.packageName != ctx.packageName) { apk.delete(); return "échec : APK d'un autre package (${info?.packageName})" }
        withContext(Dispatchers.Main) {
            if (!ctx.packageManager.canRequestPackageInstalls()) {
                notice("Autorise l'installation pour Overmind Kitt, puis relance la mise à jour")
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } else {
                val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.updates", apk)
                @Suppress("DEPRECATION")
                ctx.startActivity(Intent(Intent.ACTION_INSTALL_PACKAGE).setData(uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        return if (ctx.packageManager.canRequestPackageInstalls()) "prête : confirmation demandée à l'écran (version ${info.versionName})"
        else "en attente : autoriser l'installation d'applis pour Overmind Kitt"
    }

    private fun notice(text: String) = main.post { Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show() }

    private fun JsonObject.str(k: String) = runCatching { this[k]?.jsonPrimitive?.contentOrNull }.getOrNull()
    private fun JsonObject.int(k: String) = runCatching { this[k]?.jsonPrimitive?.intOrNull }.getOrNull()
    private fun JsonObject.long(k: String) = runCatching { this[k]?.jsonPrimitive?.longOrNull }.getOrNull()

    companion object {
        private const val TAG = "OvermindRemote"
        private const val MAX_APK = 200L * 1024 * 1024
        /** Activité Kitt au premier plan (pour la capture d'écran), posée par CopilotActivity. */
        @Volatile var foreground: WeakReference<Activity>? = null
    }
}
