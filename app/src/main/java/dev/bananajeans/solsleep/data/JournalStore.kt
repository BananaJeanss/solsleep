// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.data

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.*
import android.os.UserManager
import dev.bananajeans.solsleep.core.*

/** Independent credential-protected journal. Alarm initialization never opens this database. */
class JournalStore(context: Context,name: String="journal.db") : AutoCloseable {
    private val credential=context.applicationContext.also {check(!it.isDeviceProtectedStorage) {"Credential storage required"}}
    private val helper=object: SQLiteOpenHelper(credential,name,null,1,DatabaseErrorHandler {
        android.util.Log.e("SolSleepJournal","Database corruption detected; original files retained")
    }) {
        override fun onConfigure(db: SQLiteDatabase) {
            check(credential.getSystemService(UserManager::class.java).isUserUnlocked) {"Unlock required"}
            db.setForeignKeyConstraintsEnabled(true)
            db.execSQL("PRAGMA synchronous=FULL")
        }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE tombstones(id TEXT PRIMARY KEY NOT NULL)")
            db.execSQL("CREATE TABLE sessions(id TEXT PRIMARY KEY NOT NULL, current_revision INTEGER NOT NULL CHECK(current_revision>0))")
            db.execSQL("""CREATE TABLE revisions(session_id TEXT NOT NULL REFERENCES sessions(id),
                revision INTEGER NOT NULL CHECK(revision>0),payload TEXT NOT NULL,
                PRIMARY KEY(session_id,revision))""")
        }
        override fun onUpgrade(db: SQLiteDatabase,oldVersion: Int,newVersion: Int) {
            throw SQLiteException("No validated journal migration")
        }
        override fun onDowngrade(db: SQLiteDatabase,oldVersion: Int,newVersion: Int) {
            throw SQLiteException("Journal downgrade not supported")
        }
    }.apply {setWriteAheadLoggingEnabled(true)}

    @Synchronized fun current(): List<SleepRecord> = helper.readableDatabase.rawQuery("""SELECT s.id,s.current_revision,r.payload FROM sessions s
        LEFT JOIN revisions r ON r.session_id=s.id AND r.revision=s.current_revision""",null).use {c->
        buildList {while(c.moveToNext()) {
            check(!c.isNull(2)) {"Missing current revision; original data retained"}
            val record=SleepRecordCodec.decode(c.getString(2))
            check(record.id==c.getString(0) && record.revision==c.getLong(1)) {"Revision identity mismatch; original data retained"}
            add(record)
        }}.sortedByDescending {it.start}
    }
    @Synchronized fun knownIds(): Set<String> = helper.readableDatabase.rawQuery(
        "SELECT id FROM sessions UNION SELECT id FROM tombstones",null).use {c->
        buildSet {while(c.moveToNext()) add(c.getString(0))}
    }
    @Synchronized fun history(id: String): List<SleepRecord> = readHistory(helper.readableDatabase,id)
    @Synchronized fun snapshot(): List<SleepRecord> = transaction {db->
        db.rawQuery("SELECT payload FROM revisions ORDER BY session_id,revision",null).use {c->
            buildList {while(c.moveToNext()) add(SleepRecordCodec.decode(c.getString(0)))}
        }
    }
    /** A successful return means both current pointer and immutable revision were committed. */
    @Synchronized fun save(record: SleepRecord,expectedRevision: Long) = transaction {db->
        require(record.revision==expectedRevision+1 && expectedRevision>=0)
        if(db.rawQuery("SELECT 1 FROM tombstones WHERE id=?",arrayOf(record.id)).use {it.moveToFirst()}) throw RevisionConflict()
        val old=readHistory(db,record.id)
        if((old.lastOrNull()?.revision ?: 0L)!=expectedRevision) throw RevisionConflict()
        validateHistory(old+record)
        if(expectedRevision==0L) db.insertOrThrow("sessions",null,ContentValues().apply {
            put("id",record.id);put("current_revision",record.revision)
        })
        else check(db.update("sessions",ContentValues().apply {put("current_revision",record.revision)},
            "id=? AND current_revision=?",arrayOf(record.id,expectedRevision.toString()))==1)
        insertRevision(db,record)
    }
    /** Additive restore: preserve every local identity, including a locally corrected record. */
    @Synchronized fun restore(preview: JournalPreview): Int = transaction {db->
        preview.records.groupBy {it.id}.values.forEach(::validateHistory)
        var count=0
        preview.records.groupBy {it.id}.forEach {(id,history)->
            val exists=db.rawQuery("SELECT 1 FROM sessions WHERE id=? UNION SELECT 1 FROM tombstones WHERE id=?",arrayOf(id,id)).use {it.moveToFirst()}
            if(!exists) {
                validateHistory(history)
                db.insertOrThrow("sessions",null,ContentValues().apply {put("id",id);put("current_revision",history.last().revision)})
                history.forEach {insertRevision(db,it)};count++
            }
        };count
    }
    @Synchronized fun delete(id: String,expectedRevision: Long) = transaction {db->
        if(readHistory(db,id).lastOrNull()?.revision!=expectedRevision) throw RevisionConflict()
        db.delete("revisions","session_id=?",arrayOf(id))
        if(db.delete("sessions","id=? AND current_revision=?",arrayOf(id,expectedRevision.toString()))!=1) throw RevisionConflict()
        db.insertOrThrow("tombstones",null,ContentValues().apply {put("id",id)})
    }
    private fun readHistory(db: SQLiteDatabase,id: String): List<SleepRecord> =
        db.rawQuery("SELECT payload FROM revisions WHERE session_id=? ORDER BY revision",arrayOf(id)).use {c->
            buildList {while(c.moveToNext()) add(SleepRecordCodec.decode(c.getString(0)))}
        }
    private fun insertRevision(db: SQLiteDatabase,r: SleepRecord) {
        val payload=SleepRecordCodec.encode(r)
        SleepRecordCodec.decode(payload)
        db.insertOrThrow("revisions",null,ContentValues().apply {
            put("session_id",r.id);put("revision",r.revision);put("payload",payload)
        })
    }
    private fun <T> transaction(block: (SQLiteDatabase)->T): T {
        val db=helper.writableDatabase;db.beginTransactionNonExclusive()
        try {val result=block(db);db.setTransactionSuccessful();return result} finally {db.endTransaction()}
    }
    @Synchronized override fun close()=helper.close()
}
