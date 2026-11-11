package com.overmind.copilot

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import com.overmind.copilot.core.EndOfSpeech
import com.overmind.copilot.core.PreRoll
import com.overmind.copilot.core.Protocol
import com.overmind.copilot.core.WakeMatcher
import com.overmind.copilot.core.rms
import com.overmind.copilot.core.toLittleEndianBytes
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Micro de l'autoradio : écoute HORS LIGNE du mot de réveil (Vosk, grammaire réduite), puis
 * capture de l'énoncé jusqu'au silence. Rien ne quitte l'appareil avant le réveil.
 */
class VoiceInput(private val ctx: Context, private val listener: Listener) {
    interface Listener {
        fun onWake()
        /** Début d'énoncé : [preRoll] contient ~1 s avant la détection (le mot de réveil). */
        fun onUtteranceStart(wake: Boolean, preRoll: ByteArray)
        fun onUtteranceAudio(pcm: ByteArray)
        fun onUtteranceEnd(heardSpeech: Boolean)
        fun onLevel(level: Double)
        fun onError(message: String)
    }

    private enum class Mode { WAKE, CAPTURE, MUTED }

    @Volatile private var mode = Mode.WAKE
    @Volatile private var wakeWord = "kit"
    @Volatile private var wakeEnabled = true
    private val running = AtomicBoolean(false)
    private val am = ctx.getSystemService(AudioManager::class.java)
    private var thread: Thread? = null
    private var model: Model? = null
    private val frame = ShortArray(Protocol.SAMPLE_RATE / 50)   // 20 ms
    private val preRoll = PreRoll(Protocol.SAMPLE_RATE * 2)
    private val eos = EndOfSpeech()
    private var captureWake = false

    fun loadModel(onReady: () -> Unit) {
        // Le modèle est dans les assets (assets/model-fr, voir tools/headunit_assets.sh).
        StorageService.unpack(ctx, "model-fr", "model",
            { m -> model = m; onReady() },
            { e -> listener.onError("modèle vocal : ${e.message}") })
    }

    fun configure(word: String, enabled: Boolean) { wakeWord = word; wakeEnabled = enabled }

    @SuppressLint("MissingPermission")
    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread({ loop() }, "kitt-mic").apply { priority = Thread.MAX_PRIORITY; start() }
    }

    fun stop() { running.set(false); thread?.join(500); thread = null }

    /** Bouton micro : capture immédiate sans mot de réveil. */
    fun pushToTalk() { if (mode != Mode.CAPTURE) beginCapture(wake = false) }

    /** Pendant que Kitt parle : on n'écoute pas (sinon il s'entend lui-même). */
    fun mute(m: Boolean) { mode = if (m) Mode.MUTED else Mode.WAKE; if (!m) preRoll.clear() }

    private fun beginCapture(wake: Boolean) {
        captureWake = wake
        eos.reset(initialSpeech = wake)
        val pre = if (wake) preRoll.last(Protocol.SAMPLE_RATE * 6 / 5) else ShortArray(0)
        listener.onUtteranceStart(wake, pre.toLittleEndianBytes())
        mode = Mode.CAPTURE
    }

    @SuppressLint("MissingPermission")
    private fun loop() {
        val minBuf = AudioRecord.getMinBufferSize(Protocol.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, Protocol.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, frame.size * 2 * 10))
        } catch (e: Exception) {
            listener.onError("micro indisponible : ${e.message}"); running.set(false); return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            listener.onError("micro indisponible (permission ?)"); running.set(false); rec.release(); return
        }
        var recognizer: Recognizer? = null
        var recognizerWord = ""
        rec.startRecording()
        try {
            while (running.get()) {
                // Appel Bluetooth en cours (module téléphone de l'autoradio) : on lâche l'écoute.
                if (am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION) {
                    if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) rec.stop()
                    if (mode == Mode.CAPTURE) { mode = Mode.WAKE; listener.onUtteranceEnd(false) }
                    preRoll.clear(); Thread.sleep(500); continue
                }
                if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) rec.startRecording()
                val n = rec.read(frame, 0, frame.size)
                if (n <= 0) { Thread.sleep(50); continue }   // micro pris par une autre appli : pas de boucle à vide
                val level = rms(frame, n)
                listener.onLevel(level)
                when (mode) {
                    Mode.MUTED -> Unit
                    Mode.WAKE -> {
                        preRoll.push(frame, n)
                        val m = model ?: continue
                        if (!wakeEnabled) continue
                        if (recognizer == null || recognizerWord != wakeWord) {
                            recognizer?.close()
                            recognizer = Recognizer(m, Protocol.SAMPLE_RATE.toFloat(), "[\"$wakeWord\", \"[unk]\"]")
                                .apply { setWords(true) }
                            recognizerWord = wakeWord
                        }
                        val r = recognizer!!
                        if (r.acceptWaveForm(frame, n) && WakeMatcher.isWake(r.result, wakeWord)) {
                            r.reset()
                            listener.onWake()
                            beginCapture(wake = true)
                        }
                    }
                    Mode.CAPTURE -> {
                        listener.onUtteranceAudio(frame.toLittleEndianBytes(n))
                        when (eos.feed(level)) {
                            EndOfSpeech.State.DONE -> { mode = Mode.WAKE; preRoll.clear(); listener.onUtteranceEnd(true) }
                            EndOfSpeech.State.NO_SPEECH -> { mode = Mode.WAKE; preRoll.clear(); listener.onUtteranceEnd(captureWake) }
                            else -> Unit
                        }
                    }
                }
            }
        } finally {
            runCatching { rec.stop() }; rec.release(); recognizer?.close()
        }
    }
}
