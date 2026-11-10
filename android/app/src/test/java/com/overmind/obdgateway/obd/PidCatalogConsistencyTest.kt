package com.overmind.obdgateway.obd

import com.overmind.obdgateway.obd.pid.PidCatalog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Le catalogue Kotlin doit rester aligné sur schemas/pid_catalog.json (contrat serveur). */
class PidCatalogConsistencyTest {
    @Test fun `catalogue aligne sur le contrat`() {
        val f = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "schemas/pid_catalog.json") }.first { it.exists() }
        val json = Json.parseToJsonElement(f.readText()).jsonObject["pids"]!!.jsonArray
        val contract = json.associate { o ->
            o.jsonObject["pid"]!!.jsonPrimitive.content to (o.jsonObject["key"]!!.jsonPrimitive.content to o.jsonObject["unit"]!!.jsonPrimitive.content)
        }
        val kotlin = PidCatalog.all.associate { it.pid to (it.key to it.unit) }
        assertEquals(contract, kotlin)
    }
}
