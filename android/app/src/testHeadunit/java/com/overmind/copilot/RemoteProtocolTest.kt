package com.overmind.copilot

import com.overmind.copilot.core.Protocol
import com.overmind.copilot.core.RemoteCommand
import com.overmind.copilot.core.ServerMessage
import com.overmind.copilot.core.UpdateNonce
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Télécommande : liste fermée, réponses, URL de mise à jour (même hôte que la WebSocket). */
class RemoteProtocolTest {

    @Test fun closedCommandListWithoutVehicleAccess() {
        assertEquals(setOf("status", "display", "logs", "screenshot", "list_apps", "media", "launch_app", "restart", "update"),
            RemoteCommand.entries.map { it.wire }.toSet())
        for (w in listOf("obd", "dtc", "clear", "shell", "exec", "write", "can")) {
            assertTrue(RemoteCommand.entries.none { it.wire.contains(w) })
        }
        assertTrue(RemoteCommand.STATUS.readOnly && RemoteCommand.SCREENSHOT.readOnly)
        assertFalse(RemoteCommand.UPDATE.readOnly || RemoteCommand.MEDIA.readOnly)
    }

    @Test fun parseRemoteAndRejectUnknown() {
        val m = Protocol.parse("""{"type":"remote","id":"r1","cmd":"logs","args":{"lines":50}}""") as ServerMessage.Remote
        assertEquals(RemoteCommand.LOGS, m.command)
        assertEquals("50", m.args["lines"]!!.jsonPrimitive.content)
        val bad = Protocol.parse("""{"type":"remote","id":"r2","cmd":"shell","args":{"cmd":"reboot"}}""") as ServerMessage.Remote
        assertNull(bad.command)
        assertEquals("shell", bad.raw)
    }

    @Test fun helloAnnouncesRemoteAndResults() {
        val h = Json.parseToJsonElement(Protocol.hello("t", "hu", "v", "0.1", remote = true)).jsonObject
        assertEquals("true", h["remote"]!!.jsonPrimitive.content)
        val ok = Json.parseToJsonElement(Protocol.remoteResult("r1", buildJsonObject { put("x", 1) })).jsonObject
        assertEquals("true", ok["ok"]!!.jsonPrimitive.content)
        assertEquals("1", ok["data"]!!.jsonObject["x"]!!.jsonPrimitive.content)
        val err = Json.parseToJsonElement(Protocol.remoteResult("r1", null, "non")).jsonObject
        assertEquals("false", err["ok"]!!.jsonPrimitive.content)
        assertEquals("non", err["error"]!!.jsonPrimitive.content)
    }

    @Test fun updateIsPulledInsideTheWebSocket() {
        val g = Json.parseToJsonElement(Protocol.updateGet("AbCdEfGhIjKlMnOpQrStUvWx", 524288)).jsonObject
        assertEquals("update_get", g["type"]!!.jsonPrimitive.content)
        assertEquals("524288", g["offset"]!!.jsonPrimitive.content)
        assertEquals(ServerMessage.UpdateData("n", 0, 1000, true, null),
            Protocol.parse("""{"type":"update_data","id":"n","offset":0,"bytes":1000,"eof":true}"""))
        val e = Protocol.parse("""{"type":"update_data","id":"n","error":"expirée"}""") as ServerMessage.UpdateData
        assertEquals("expirée", e.error)
        assertTrue(UpdateNonce.valid("AbCdEfGhIjKlMnOpQrStUvWx"))
        for (bad in listOf(null, "", "court", "https://evil.example/x.apk", "../../etc/passwd/aaaaaaaaaaaa")) {
            assertFalse(UpdateNonce.valid(bad))
        }
    }
}
