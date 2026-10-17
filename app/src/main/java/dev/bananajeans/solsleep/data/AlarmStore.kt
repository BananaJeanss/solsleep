// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import dev.bananajeans.solsleep.core.*
import org.json.JSONObject
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

    private fun encode(r: AlarmRule): String = JSONObject().apply {
        put("version",1);put("id",r.id);put("time",r.time.toString());put("label",r.label)
        put("enabled",r.enabled);put("zone",r.zoneId);put("skip",r.skipDate?.toString())
        put("vibrate",r.vibrate);put("ramp",r.rampSeconds);put("snooze",r.snoozeMinutes)
        put("limit",r.snoozeLimit);put("revision",r.revision)
        when(val repeat=r.repeat) {
            is Repeat.Once -> {put("kind","once");put("date",repeat.date.toString())}
            is Repeat.Weekly -> {put("kind","weekly");put("days",repeat.days.sumOf { 1 shl (it.value-1) })}
            is Repeat.EveryDays -> {put("kind","interval");put("date",repeat.anchor.toString());put("interval",repeat.interval)}
            is Repeat.AlternatingWeeks -> {
                put("kind","alternating");put("days",repeat.days.sumOf { 1 shl (it.value-1) });put("odd",repeat.odd)
            }
        }
    }.toString()

    private fun decode(payload: String): AlarmRule {
        val j=JSONObject(payload)
        require(j.getInt("version")==1) { "Unsupported alarm payload version" }
        val days=DayOfWeek.entries.filter { j.optInt("days",0) and (1 shl (it.value-1)) != 0 }.toSet()
        val repeat=when(j.getString("kind")) {
            "once" -> Repeat.Once(LocalDate.parse(j.getString("date")))
            "weekly" -> Repeat.Weekly(days)
            "interval" -> Repeat.EveryDays(LocalDate.parse(j.getString("date")),j.getInt("interval"))
            "alternating" -> Repeat.AlternatingWeeks(days,j.getBoolean("odd"))
            else -> error("Unknown recurrence")
        }
        return AlarmRule(j.getString("id"),LocalTime.parse(j.getString("time")),repeat,
            j.getString("label"),j.getBoolean("enabled"),
            if(j.isNull("zone")) null else j.getString("zone"),
            if(j.isNull("skip")) null else LocalDate.parse(j.getString("skip")),
            j.getBoolean("vibrate"),j.getInt("ramp"),j.getInt("snooze"),j.getInt("limit"),j.getLong("revision"))
    }
}
