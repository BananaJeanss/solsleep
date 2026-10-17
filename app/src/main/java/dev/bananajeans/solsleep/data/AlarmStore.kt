// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import dev.bananajeans.solsleep.core.*
import java.time.*

/** Authoritative alarm-only device-protected store; never stores sleep/health data. */
class AlarmStore(context: Context, name: String = "alarms.db") : AlarmPersistence, AutoCloseable {
    private val helper = object : SQLiteOpenHelper(context.createDeviceProtectedStorageContext(), name, null, 1,
        android.database.DatabaseErrorHandler {
            android.util.Log.e("SolSleepAlarm","Database corruption detected; original files retained")
        }) {
        override fun onConfigure(db: SQLiteDatabase) {
            db.setForeignKeyConstraintsEnabled(true)
            db.execSQL("PRAGMA synchronous=FULL")
        }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE alarms(id TEXT PRIMARY KEY NOT NULL, payload TEXT NOT NULL)")
            db.execSQL("""CREATE TABLE pending(
                alarm_id TEXT PRIMARY KEY NOT NULL REFERENCES alarms(id) ON DELETE CASCADE,
                token TEXT UNIQUE NOT NULL, due_ms INTEGER NOT NULL, origin_date TEXT NOT NULL,
                state TEXT NOT NULL, snoozes INTEGER NOT NULL, armed INTEGER NOT NULL,
                started_ms INTEGER)""")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            throw SQLiteException("No validated alarm migration from " + oldVersion + " to " + newVersion)
        }
        override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            throw SQLiteException("Alarm database downgrade is not supported")
        }
    }.apply { setWriteAheadLoggingEnabled(true) }

    @Synchronized override fun entries(): List<AlarmEntry> {
        val result = mutableListOf<AlarmEntry>()
        helper.readableDatabase.rawQuery("""SELECT a.payload,p.token,p.due_ms,p.origin_date,p.state,
            p.snoozes,p.armed,p.started_ms FROM alarms a LEFT JOIN pending p ON a.id=p.alarm_id""", null).use { c ->
            while (c.moveToNext()) {
                val rule = decode(c.getString(0))
                val pending = if (c.isNull(1)) null else PendingAlarm(
                    c.getString(1), Instant.ofEpochMilli(c.getLong(2)), LocalDate.parse(c.getString(3)),
                    DeliveryState.valueOf(c.getString(4)), c.getInt(5), c.getInt(6) != 0,
                    if(c.isNull(7)) null else Instant.ofEpochMilli(c.getLong(7)),
                )
                result += AlarmEntry(rule, pending)
            }
        }
        return result
    }

    @Synchronized override fun put(rule: AlarmRule, pending: PendingAlarm?) = transaction { db ->
        db.insertWithOnConflict("alarms", null, ContentValues().apply {
            put("id",rule.id); put("payload",encode(rule))
        }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
        if (pending != null) writePending(db,rule.id,pending)
    }

    @Synchronized override fun replacePending(id: String, pending: PendingAlarm) = transaction { db ->
        writePending(db,id,pending)
    }

    @Synchronized override fun setArmed(token: String, armed: Boolean) {
        helper.writableDatabase.update("pending", ContentValues().apply { put("armed", if(armed) 1 else 0) },
            "token=?", arrayOf(token))
    }

    @Synchronized override fun claim(token: String, now: Instant): AlarmEntry? {
        var claimed: AlarmEntry? = null
        transaction { db ->
            val entry=entries().find { it.pending?.token==token } ?: return@transaction
            val p=entry.pending!!
            if (!entry.rule.enabled || p.at > now ||
                p.state !in setOf(DeliveryState.SCHEDULED,DeliveryState.SNOOZED)) return@transaction
            val n=db.update("pending", ContentValues().apply {
                put("state",DeliveryState.RINGING.name); put("started_ms",(p.startedAt ?: now).toEpochMilli())
            }, "token=? AND state IN (?,?)",arrayOf(token,DeliveryState.SCHEDULED.name,DeliveryState.SNOOZED.name))
            if(n==1) claimed=entry.copy(pending=p.copy(state=DeliveryState.RINGING,startedAt=p.startedAt ?: now))
        }
        return claimed
    }

    @Synchronized override fun finish(token: String, missed: Boolean): Boolean {
        var changed=false
        transaction { db ->
            val entry=entries().find { it.pending?.token==token } ?: return@transaction
            val p=entry.pending!!
            if(p.state in setOf(DeliveryState.DONE,DeliveryState.MISSED)) return@transaction
            if(!missed && p.state!=DeliveryState.RINGING) return@transaction
            db.update("pending",ContentValues().apply {
                put("state",if(missed) DeliveryState.MISSED.name else DeliveryState.DONE.name)
                put("armed",0)
            },"token=?",arrayOf(token))
            if(entry.rule.repeat is Repeat.Once) db.update("alarms",
                ContentValues().apply { put("payload",encode(entry.rule.copy(enabled=false))) },
                "id=?",arrayOf(entry.rule.id))
            changed=true
        }
        return changed
    }

    @Synchronized override fun delete(id: String) {
        helper.writableDatabase.delete("alarms","id=?",arrayOf(id))
    }

    @Synchronized override fun insertDisabled(rules: List<AlarmRule>) = transaction {db ->
        require(rules.all {!it.enabled})
        rules.forEach {r ->
            db.insertOrThrow("alarms",null,ContentValues().apply {put("id",r.id);put("payload",encode(r))})
        }
    }

    private fun writePending(db: SQLiteDatabase,id: String,p: PendingAlarm) {
        db.insertWithOnConflict("pending",null,ContentValues().apply {
            put("alarm_id",id);put("token",p.token);put("due_ms",p.at.toEpochMilli())
            put("origin_date",p.date.toString());put("state",p.state.name)
            put("snoozes",p.snoozes);put("armed",if(p.armed) 1 else 0)
            p.startedAt?.let {put("started_ms",it.toEpochMilli())} ?: putNull("started_ms")
        },SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
    }

    private fun transaction(block: (SQLiteDatabase)->Unit) {
        val db=helper.writableDatabase
        db.beginTransaction()
        try { block(db);db.setTransactionSuccessful() } finally { db.endTransaction() }
    }

    @Synchronized override fun close() = helper.close()

    private fun encode(r: AlarmRule)=AlarmRuleCodec.encode(r)
    private fun decode(payload: String)=AlarmRuleCodec.decode(payload)
}
