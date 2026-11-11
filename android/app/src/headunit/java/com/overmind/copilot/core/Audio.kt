package com.overmind.copilot.core

import kotlin.math.sqrt

/** RMS d'une trame PCM 16 bits. */
fun rms(frame: ShortArray, n: Int = frame.size): Double {
    if (n == 0) return 0.0
    var acc = 0.0
    for (i in 0 until n) { val v = frame[i].toDouble(); acc += v * v }
    return sqrt(acc / n)
}

/**
 * Fin d'énoncé par énergie, avec seuil adaptatif au bruit de fond (habitacle, route).
 * - fin après [silenceMs] de silence une fois la parole commencée ;
 * - abandon si aucune parole pendant [noSpeechMs] ;
 * - coupure dure à [maxMs].
 */
class EndOfSpeech(
    private val frameMs: Int = 20,
    private val silenceMs: Int = 900,
    private val noSpeechMs: Int = 4_000,
    private val maxMs: Int = 8_000,
    private val minThreshold: Double = 350.0,
    private val factor: Double = 2.5,
) {
    enum class State { WAITING, SPEAKING, DONE, NO_SPEECH }

    private var noise = minThreshold / factor
    private var elapsed = 0
    private var silent = 0
    var state = State.WAITING; private set
    var heardSpeech = false; private set

    /** [initialSpeech] : l'utilisateur parle déjà (mot de réveil juste prononcé). */
    fun reset(initialSpeech: Boolean) {
        elapsed = 0; silent = 0
        heardSpeech = initialSpeech
        state = if (initialSpeech) State.SPEAKING else State.WAITING
    }

    fun feed(level: Double): State {
        if (state == State.DONE || state == State.NO_SPEECH) return state
        elapsed += frameMs
        val threshold = maxOf(minThreshold, noise * factor)
        if (level >= threshold) {
            heardSpeech = true; silent = 0; state = State.SPEAKING
            // Monte lentement même pendant un son fort : un bruit de roulement constant finit par
            // être appris comme fond (~1 s), alors que la parole (quelques secondes) ne l'est pas.
            noise = noise * 0.995 + level * 0.005
        } else {
            noise = noise * 0.95 + level * 0.05     // suit vite le bruit de fond quand personne ne parle
            silent += frameMs
        }
        state = when {
            elapsed >= maxMs -> if (heardSpeech) State.DONE else State.NO_SPEECH
            heardSpeech && silent >= silenceMs -> State.DONE
            !heardSpeech && elapsed >= noSpeechMs -> State.NO_SPEECH
            else -> state
        }
        return state
    }
}

/** Tampon circulaire d'audio : garde les dernières secondes pour inclure « Kitt » dans l'énoncé. */
class PreRoll(capacitySamples: Int) {
    private val buf = ShortArray(capacitySamples)
    private var pos = 0
    private var filled = 0

    fun push(frame: ShortArray, n: Int = frame.size) {
        for (i in 0 until n) {
            buf[pos] = frame[i]
            pos = (pos + 1) % buf.size
        }
        filled = minOf(buf.size, filled + n)
    }

    /** Les [samples] derniers échantillons, dans l'ordre chronologique. */
    fun last(samples: Int): ShortArray {
        val n = minOf(samples, filled)
        val out = ShortArray(n)
        val start = (pos - n + buf.size) % buf.size
        for (i in 0 until n) out[i] = buf[(start + i) % buf.size]
        return out
    }

    fun clear() { pos = 0; filled = 0 }
}

fun ShortArray.toLittleEndianBytes(n: Int = size): ByteArray {
    val out = ByteArray(n * 2)
    for (i in 0 until n) {
        val v = this[i].toInt()
        out[2 * i] = (v and 0xFF).toByte()
        out[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
    }
    return out
}

/**
 * Détection du mot de réveil sur un résultat FINAL Vosk (grammaire réduite, `SetWords(true)`).
 * Le résultat partiel n'est pas utilisé : il produit trop de faux positifs.
 * Le serveur revérifie avec le vocabulaire complet (protocole, « mot de réveil en deux étapes »).
 */
object WakeMatcher {
    fun isWake(voskResultJson: String, word: String = "kit", minConf: Double = 0.85): Boolean {
        val words = Regex("\"word\"\\s*:\\s*\"([^\"]*)\"").findAll(voskResultJson).map { it.groupValues[1] }.toList()
        val confs = Regex("\"conf\"\\s*:\\s*([0-9.]+)").findAll(voskResultJson).map { it.groupValues[1].toDouble() }.toList()
        if (words.isNotEmpty() && words.size == confs.size) {
            return words.indices.any { words[it] == word && confs[it] >= minConf }
        }
        // Sans détail par mot : texte brut.
        val text = Regex("\"text\"\\s*:\\s*\"([^\"]*)\"").find(voskResultJson)?.groupValues?.get(1) ?: return false
        return text.split(" ").contains(word)
    }
}
