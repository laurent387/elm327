package com.overmind.obdgateway.net

import com.overmind.obdgateway.buffer.InMemoryOutbox
import com.overmind.obdgateway.telemetry.OutgoingMessage
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Test

/** Broker simulé : coupures, PUBACK partiels, rejeu. */
class FakeLink(var failConnects: Int = 0, var dropAfter: Int = Int.MAX_VALUE) : MqttLink {
    val delivered = ArrayList<OutgoingMessage>()
    private var up = false
    var publishedCount = 0
    override val isConnected get() = up
    override suspend fun connect() {
        if (failConnects > 0) { failConnects--; throw java.io.IOException("réseau indisponible") }
        up = true
    }
    override suspend fun publish(batch: List<OutgoingMessage>): Set<String> {
        val acked = HashSet<String>()
        for (m in batch) {
            if (publishedCount >= dropAfter) { up = false; dropAfter = Int.MAX_VALUE; break } // coupure en plein lot
            publishedCount++
            delivered += m; acked += m.messageId
        }
        return acked
    }
    override fun disconnect() { up = false }
}

class OutboxDrainerTest {
    private fun msg(i: Long) = OutgoingMessage("id-$i", "telemetry", "t", "{}", 1, false, "s", i, i)

    @Test fun `hors ligne puis reprise sans perte ni doublon, dans l'ordre`() = runBlocking {
        val outbox = InMemoryOutbox()
        val link = FakeLink(failConnects = 2, dropAfter = 45)
        val d = OutboxDrainer(outbox, { link }, batchSize = 32)
        (1L..100L).forEach { outbox.enqueue(msg(it)) }
        val job = launch { d.run() }
        withTimeout(20_000) { while (outbox.count() > 0) { d.wake(); delay(20) } }
        job.cancel()
        assertEquals(100, link.delivered.size)
        assertEquals((1L..100L).toList(), link.delivered.map { it.sequence })
        assertEquals(100, link.delivered.map { it.messageId }.toSet().size)
    }

    @Test fun `outbox bornee - la telemetrie la plus ancienne est sacrifiee, pas les DTC`() {
        val o = InMemoryOutbox()
        o.enqueue(OutgoingMessage("dtc", "dtc", "t", "{}", 1, false, "s", 0, 0))
        (1L..10L).forEach { o.enqueue(msg(it)) }
        assertEquals(3, o.trim(8))
        assertEquals(listOf("dtc", "id-4"), o.peek(2).map { it.messageId })
    }

    @Test fun `enqueue idempotent sur message_id`() {
        val o = InMemoryOutbox()
        o.enqueue(msg(1)); o.enqueue(msg(1))
        assertEquals(1, o.count())
    }
}
