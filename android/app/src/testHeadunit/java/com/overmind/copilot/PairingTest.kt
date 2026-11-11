package com.overmind.copilot

import com.overmind.copilot.core.Protocol
import com.overmind.copilot.core.ServerMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Appairage : un code à 6 chiffres remplace le jeton tant qu'aucun jeton n'est enregistré. */
class PairingTest {
    @Test fun helloSendsCodeOnlyWithoutToken() {
        val h = Json.parseToJsonElement(Protocol.hello("", "hu", "v", "0.1", pairingCode = "123456")).jsonObject
        assertEquals("123456", h["pairing_code"]!!.jsonPrimitive.content)
        assertNull(h["token"])
        val t = Json.parseToJsonElement(Protocol.hello("tok", "hu", "v", "0.1", pairingCode = "123456")).jsonObject
        assertEquals("tok", t["token"]!!.jsonPrimitive.content)
        assertNull(t["pairing_code"])
    }

    @Test fun pairedMessageAndCodeFormat() {
        val tok = "x".repeat(43)
        assertEquals(ServerMessage.Paired(tok), Protocol.parse("""{"type":"paired","token":"$tok"}"""))
        assertTrue(Protocol.parse("""{"type":"paired","token":"court"}""") is ServerMessage.Unknown)
        assertTrue(PAIRING_CODE.matches("042917"))
        for (bad in listOf("12345", "1234567", "abcdef", " 123456", "12 456")) assertFalse(PAIRING_CODE.matches(bad))
    }
}
