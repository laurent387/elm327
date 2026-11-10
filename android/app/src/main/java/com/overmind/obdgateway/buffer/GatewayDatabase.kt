package com.overmind.obdgateway.buffer

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.overmind.obdgateway.telemetry.OutgoingMessage

/** SQLite local : outbox + historique des sessions. Volontairement sans ORM (léger). */
class GatewayDatabase(context: Context) : SQLiteOpenHelper(context, "gateway.db", null, 1) {

    override fun onConfigure(db: SQLiteDatabase) { db.enableWriteAheadLogging() }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE outbox(
            message_id TEXT PRIMARY KEY, kind TEXT NOT NULL, topic TEXT NOT NULL, payload TEXT NOT NULL,
            qos INTEGER NOT NULL, retain INTEGER NOT NULL, session_id TEXT, sequence INTEGER NOT NULL,
            created_at INTEGER NOT NULL)""")
        db.execSQL("CREATE INDEX outbox_seq ON outbox(sequence)")
        db.execSQL("""CREATE TABLE sessions(
            session_id TEXT PRIMARY KEY, vehicle_id TEXT NOT NULL, started_at INTEGER NOT NULL,
            ended_at INTEGER, adapter TEXT, protocol TEXT, vin TEXT, messages INTEGER NOT NULL DEFAULT 0,
            dtc_count INTEGER, last_error TEXT)""")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
}

class SqliteOutbox(private val db: GatewayDatabase) : Outbox {

    override fun enqueue(m: OutgoingMessage) {
        val cv = ContentValues().apply {
            put("message_id", m.messageId); put("kind", m.kind); put("topic", m.topic); put("payload", m.payload)
            put("qos", m.qos); put("retain", if (m.retain) 1 else 0); put("session_id", m.sessionId)
            put("sequence", m.sequence); put("created_at", m.createdAtMs)
        }
        db.writableDatabase.insertWithOnConflict("outbox", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
    }

    override fun peek(limit: Int): List<OutgoingMessage> {
        val out = ArrayList<OutgoingMessage>(limit)
        db.readableDatabase.rawQuery(
            "SELECT message_id,kind,topic,payload,qos,retain,session_id,sequence,created_at FROM outbox ORDER BY sequence LIMIT ?",
            arrayOf(limit.toString()),
        ).use { c ->
            while (c.moveToNext()) out += OutgoingMessage(
                c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getInt(4), c.getInt(5) == 1,
                if (c.isNull(6)) null else c.getString(6), c.getLong(7), c.getLong(8),
            )
        }
        return out
    }

    override fun remove(messageIds: Collection<String>) {
        if (messageIds.isEmpty()) return
        val w = db.writableDatabase
        w.beginTransaction()
        try {
            for (id in messageIds) w.delete("outbox", "message_id=?", arrayOf(id))
            w.setTransactionSuccessful()
        } finally { w.endTransaction() }
    }

    override fun count(): Int = db.readableDatabase.rawQuery("SELECT COUNT(*) FROM outbox", null).use { it.moveToFirst(); it.getInt(0) }

    override fun countForSession(sessionId: String): Int =
        db.readableDatabase.rawQuery("SELECT COUNT(*) FROM outbox WHERE session_id=?", arrayOf(sessionId)).use { it.moveToFirst(); it.getInt(0) }

    override fun trim(maxRows: Int): Int {
        val excess = count() - maxRows
        if (excess <= 0) return 0
        return db.writableDatabase.delete(
            "outbox",
            "message_id IN (SELECT message_id FROM outbox WHERE kind='telemetry' ORDER BY sequence LIMIT ?)",
            arrayOf(excess.toString()),
        )
    }
}

data class SessionRecord(
    val sessionId: String, val vehicleId: String, val startedAt: Long, val endedAt: Long?,
    val adapter: String?, val protocol: String?, val vin: String?, val messages: Int, val dtcCount: Int?,
    val lastError: String?, val pendingSync: Int = 0,
)

interface SessionStore {
    fun start(r: SessionRecord)
    fun update(sessionId: String, protocol: String? = null, vin: String? = null, addMessages: Int = 0, dtcCount: Int? = null, lastError: String? = null)
    fun end(sessionId: String, at: Long)
    fun recent(limit: Int): List<SessionRecord>
}

class SqliteSessionStore(private val db: GatewayDatabase) : SessionStore {
    override fun start(r: SessionRecord) {
        db.writableDatabase.insertWithOnConflict("sessions", null, ContentValues().apply {
            put("session_id", r.sessionId); put("vehicle_id", r.vehicleId); put("started_at", r.startedAt)
            put("adapter", r.adapter)
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    override fun update(sessionId: String, protocol: String?, vin: String?, addMessages: Int, dtcCount: Int?, lastError: String?) {
        val w = db.writableDatabase
        val cv = ContentValues()
        protocol?.let { cv.put("protocol", it) }
        vin?.let { cv.put("vin", it) }
        dtcCount?.let { cv.put("dtc_count", it) }
        lastError?.let { cv.put("last_error", it) }
        if (cv.size() > 0) w.update("sessions", cv, "session_id=?", arrayOf(sessionId))
        if (addMessages != 0) w.execSQL("UPDATE sessions SET messages = messages + ? WHERE session_id=?", arrayOf<Any>(addMessages, sessionId))
    }

    override fun end(sessionId: String, at: Long) {
        db.writableDatabase.update("sessions", ContentValues().apply { put("ended_at", at) }, "session_id=?", arrayOf(sessionId))
    }

    override fun recent(limit: Int): List<SessionRecord> {
        val out = ArrayList<SessionRecord>()
        db.readableDatabase.rawQuery(
            """SELECT s.session_id,s.vehicle_id,s.started_at,s.ended_at,s.adapter,s.protocol,s.vin,s.messages,s.dtc_count,s.last_error,
               (SELECT COUNT(*) FROM outbox o WHERE o.session_id = s.session_id)
               FROM sessions s ORDER BY s.started_at DESC LIMIT ?""", arrayOf(limit.toString()),
        ).use { c ->
            while (c.moveToNext()) out += SessionRecord(
                c.getString(0), c.getString(1), c.getLong(2), if (c.isNull(3)) null else c.getLong(3),
                c.getString(4), c.getString(5), c.getString(6), c.getInt(7), if (c.isNull(8)) null else c.getInt(8),
                c.getString(9), c.getInt(10),
            )
        }
        return out
    }
}
