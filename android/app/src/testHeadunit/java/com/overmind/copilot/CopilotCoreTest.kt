package com.overmind.copilot

import com.overmind.copilot.core.ClientAction
import com.overmind.copilot.core.EndOfSpeech
import com.overmind.copilot.core.MediaCommand
import com.overmind.copilot.core.PreRoll
import com.overmind.copilot.core.Protocol
import com.overmind.copilot.core.ServerMessage
import com.overmind.copilot.core.WakeMatcher
import com.overmind.copilot.core.rms
import com.overmind.copilot.core.toLittleEndianBytes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CopilotCoreTest {

    @Test fun helloAndUtteranceMessages() {
        val h = Json.parseToJsonElement(Protocol.hello("tok", "hu", "slk_r171", "0.1.0")).jsonObject
        assertEquals("hello", h["type"]!!.jsonPrimitive.content)
        assertEquals("slk_r171", h["vehicle_id"]!!.jsonPrimitive.content)
        val u = Json.parseToJsonElement(Protocol.utteranceStart("u1", true, buildJsonObject { put("moving", true) })).jsonObject
        assertEquals("16000", u["sample_rate"]!!.jsonPrimitive.content)
        assertEquals("true", u["wake"]!!.jsonPrimitive.content)
    }

    @Test fun parseServerMessages() {
        assertEquals(ServerMessage.Welcome("Kitt", true, true), Protocol.parse("""{"type":"welcome","assistant":"Kitt","stt":true,"tts":true}"""))
        assertEquals(ServerMessage.Ignored("u1"), Protocol.parse("""{"type":"ignored","id":"u1","reason":"wake_not_confirmed"}"""))
        val r = Protocol.parse("""{"type":"reply","id":"u1","text":"Pause.","intent":"media","action":{"type":"media","command":"pause"}}""")
        assertEquals(ServerMessage.Reply("u1", "Pause.", "media", ClientAction.Media(MediaCommand.PAUSE)), r)
        assertEquals(ServerMessage.Done("u1", 3142), Protocol.parse("""{"type":"done","id":"u1","timings":{"total_ms":3142}}"""))
        assertTrue(Protocol.parse("pas du json") is ServerMessage.Unknown)
    }

    @Test fun onlyWhitelistedActionsAreAccepted() {
        fun a(s: String) = ClientAction.from(Json.parseToJsonElement(s))
        assertEquals(ClientAction.Navigate("Gare de Lyon"), a("""{"type":"navigate","destination":" Gare de Lyon "}"""))
        assertEquals(ClientAction.Call("Maman"), a("""{"type":"call","contact":"Maman"}"""))
        assertNull(a("""{"type":"media","command":"format_disk"}"""))
        assertNull(a("""{"type":"obd","command":"04"}"""))            // jamais d'action véhicule
        assertNull(a("""{"type":"clear_dtc"}"""))
        assertNull(a("""{"type":"navigate","destination":""}"""))
        assertNull(a("""null"""))
    }

    @Test fun endOfSpeechAfterSilence() {
        val e = EndOfSpeech(frameMs = 20, silenceMs = 900)
        e.reset(initialSpeech = true)
        repeat(20) { assertEquals(EndOfSpeech.State.SPEAKING, e.feed(3000.0)) }   // 400 ms de parole
        repeat(44) { assertEquals(EndOfSpeech.State.SPEAKING, e.feed(100.0)) }     // 880 ms de silence : pas fini
        assertEquals(EndOfSpeech.State.DONE, e.feed(100.0))                          // 900 ms : fin d'énoncé
    }

    @Test fun noSpeechTimesOut() {
        val e = EndOfSpeech(frameMs = 20, noSpeechMs = 4000)
        e.reset(initialSpeech = false)
        var s = EndOfSpeech.State.WAITING
        repeat(200) { s = e.feed(80.0) }
        assertEquals(EndOfSpeech.State.NO_SPEECH, s)
        assertFalse(e.heardSpeech)
    }

    @Test fun hardStopAtMax() {
        val e = EndOfSpeech(frameMs = 20, maxMs = 8000)
        e.reset(initialSpeech = true)
        var s = EndOfSpeech.State.SPEAKING
        repeat(400) { s = e.feed(5000.0) }
        assertEquals(EndOfSpeech.State.DONE, s)
    }

    @Test fun adaptiveThresholdIgnoresSteadyRoadNoise() {
        val e = EndOfSpeech(frameMs = 20, noSpeechMs = 4000, minThreshold = 350.0)
        e.reset(initialSpeech = false)
        var s = EndOfSpeech.State.WAITING
        repeat(200) { s = e.feed(600.0) }       // bruit de roulement constant, au-dessus du seuil mini
        // Le seuil suit le bruit : après adaptation, on n'est plus « en parole » indéfiniment.
        assertTrue(s == EndOfSpeech.State.NO_SPEECH || s == EndOfSpeech.State.DONE)
    }

    @Test fun preRollKeepsLastSamplesInOrder() {
        val p = PreRoll(5)
        p.push(shortArrayOf(1, 2, 3))
        p.push(shortArrayOf(4, 5, 6, 7))
        assertArrayEquals(shortArrayOf(5, 6, 7), p.last(3))
        assertArrayEquals(shortArrayOf(3, 4, 5, 6, 7), p.last(10))
    }

    @Test fun pcmLittleEndian() {
        assertArrayEquals(byteArrayOf(0x34, 0x12, 0xFF.toByte(), 0xFF.toByte()), shortArrayOf(0x1234, -1).toLittleEndianBytes())
        assertEquals(5.0, rms(shortArrayOf(5, -5, 5, -5)), 1e-9)
        assertEquals(0.0, rms(ShortArray(0)), 0.0)
    }

    @Test fun wakeMatcherUsesFinalResultConfidence() {
        assertTrue(WakeMatcher.isWake("""{"result":[{"conf":1.0,"word":"kit"},{"conf":1.0,"word":"[unk]"}],"text":"kit [unk]"}"""))
        assertFalse(WakeMatcher.isWake("""{"result":[{"conf":0.6,"word":"kit"}],"text":"kit"}"""))
        assertFalse(WakeMatcher.isWake("""{"result":[{"conf":1.0,"word":"[unk]"}],"text":"[unk]"}"""))
        assertTrue(WakeMatcher.isWake("""{"text":"kit"}"""))
        assertFalse(WakeMatcher.isWake("""{"text":""}"""))
    }
}
