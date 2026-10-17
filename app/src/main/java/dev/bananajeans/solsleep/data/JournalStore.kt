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
    companion object {
        // SQLiteOpenHelper's instance lock does not coordinate separate helpers creating one DB.
        private val openLocks=Array(32) {Any()}
    }
    private val credential=context.applicationContext.also {check(!it.isDeviceProtectedStorage) {"Credential storage required"}}
    private val openLock=openLocks[(credential.getDatabasePath(name).absolutePath.hashCode() and Int.MAX_VALUE)%openLocks.size]
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

    private fun database(): SQLiteDatabase = synchronized(openLock) {helper.writableDatabase}

    @Synchronized fun current(): List<SleepRecord> = database().rawQuery("""SELECT s.id,s.current_revision,r.payload FROM sessions s
        LEFT JOIN revisions r ON r.session_id=s.id AND r.revision=s.current_revision""",null).use {c->
        buildList {while(c.moveToNext()) {
            check(!c.isNull(2)) {"Missing current revision; original data retained"}
            val record=SleepRecordCodec.decode(c.getString(2))
            check(record.id==c.getString(0) && record.revision==c.getLong(1)) {"Revision identity mismatch; original data retained"}
            add(record)
        }}.sortedByDescending {it.start}
    }
    @Synchronized fun knownIds(): Set<String> = database().rawQuery(
        "SELECT id FROM sessions UNION SELECT id FROM tombstones",null).use {c->
        buildSet {while(c.moveToNext()) add(c.getString(0))}
    }
    @Synchronized fun history(id: String): List<SleepRecord> = readHistory(database(),id)
    @Synchronized fun snapshot(): List<SleepRecord> = transaction {db->
        readRecords(db)
    }
    /** Read histories and deletion markers in the same WAL transaction, never separate snapshots. */
    @Synchronized fun archiveSnapshot(): JournalSnapshot = transaction {db->
        val records=readRecords(db)
        check(records.groupBy {it.id}.values.map {it.last()}.toSet()==current().toSet()) {
            "Inconsistent journal; original data retained"
        }
        val deleted=db.rawQuery("SELECT id FROM tombstones ORDER BY id",null).use {c->
            buildSet {while(c.moveToNext()) add(c.getString(0))}
        }
        JournalSnapshot(records,deleted)
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
    @Synchronized fun restore(preview: JournalPreview): JournalRestoreResult = transaction {db->
        // Revalidate even previews constructed by a caller rather than the archive reader.
        JournalPreview(preview.createdAt,preview.records,preview.fingerprint,preview.deletedIds)
        var count=0;var skipped=0;var markers=0;var skippedMarkers=0;var kept=0
        preview.deletedIds.sorted().forEach {id->
            val live=db.rawQuery("SELECT 1 FROM sessions WHERE id=?",arrayOf(id)).use {it.moveToFirst()}
            val deleted=db.rawQuery("SELECT 1 FROM tombstones WHERE id=?",arrayOf(id)).use {it.moveToFirst()}
            if(live || deleted) {skippedMarkers++;if(live) kept++}
            else {db.insertOrThrow("tombstones",null,ContentValues().apply {put("id",id)});markers++}
        }
        preview.records.groupBy {it.id}.forEach {(id,history)->
            val exists=db.rawQuery("SELECT 1 FROM sessions WHERE id=? UNION SELECT 1 FROM tombstones WHERE id=?",arrayOf(id,id)).use {it.moveToFirst()}
            if(!exists) {
                validateHistory(history)
                db.insertOrThrow("sessions",null,ContentValues().apply {put("id",id);put("current_revision",history.last().revision)})
                history.forEach {insertRevision(db,it)};count++
            } else skipped++
        };JournalRestoreResult(count,skipped,markers,skippedMarkers,kept)
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
    private fun readRecords(db: SQLiteDatabase): List<SleepRecord> =
        db.rawQuery("SELECT payload FROM revisions ORDER BY session_id,revision",null).use {c->
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
        val db=database();db.beginTransactionNonExclusive()
        try {val result=block(db);db.setTransactionSuccessful();return result} finally {db.endTransaction()}
    }
    @Synchronized override fun close()=helper.close()
}
