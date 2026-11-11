package com.overmind.copilot

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.util.Locale

/**
 * Sortie vocale : WAV synthétisé par Overmind (Piper sur le GE75) ; voix Android locale
 * seulement en secours (Overmind injoignable). La musique est baissée pendant que Kitt parle.
 */
class Speaker(private val ctx: Context, private val onSpeaking: (Boolean) -> Unit) {
    private val am = ctx.getSystemService(AudioManager::class.java)
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attrs).build()
    private var player: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 60) }.getOrNull()

    init {
        tts = TextToSpeech(ctx) { status ->
            ttsReady = status == TextToSpeech.SUCCESS && tts?.setLanguage(Locale.FRANCE)?.let { it >= 0 } == true
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = release()
            @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) = release()
        })
    }

    /** Petit bip « je t'écoute » après le mot de réveil. */
    fun earcon() { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 90) }

    fun playWav(wav: ByteArray) {
        stop()
        val f = File(ctx.cacheDir, "kitt_reply.wav").apply { writeBytes(wav) }
        am.requestAudioFocus(focus)
        onSpeaking(true)
        player = MediaPlayer().apply {
            setAudioAttributes(attrs)
            setDataSource(f.absolutePath)
            setOnCompletionListener { release() }
            setOnErrorListener { _, _, _ -> release(); true }
            prepare()
            start()
        }
    }

    /** Secours hors ligne (voix Android de l'autoradio). */
    fun sayLocal(text: String) {
        if (!ttsReady) return
        stop()
        am.requestAudioFocus(focus)
        onSpeaking(true)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "kitt-local")
    }

    fun stop() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        tts?.stop()
    }

    private fun release() {
        player?.release(); player = null
        am.abandonAudioFocusRequest(focus)
        onSpeaking(false)
    }

    fun shutdown() { stop(); tts?.shutdown(); tone?.release() }
}
