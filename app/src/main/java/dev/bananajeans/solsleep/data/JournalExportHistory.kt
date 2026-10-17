// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.data

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.Cursor
import android.database.sqlite.*
import android.os.UserManager
import dev.bananajeans.solsleep.core.*
import java.time.Instant
import java.util.UUID

/** Local export metadata only. Failure cannot block journal recovery or alarm initialization. */
class JournalExportHistory(context: Context,name: String="journal-exports.db"): JournalExportAudit,AutoCloseable {
    companion object {private val openLocks=Array(32) {Any()}}
    private val credential=context.applicationContext.also {check(!it.isDeviceProtectedStorage)}
    private val openLock=openLocks[(credential.getDatabasePath(name).absolutePath.hashCode() and Int.MAX_VALUE)%openLocks.size]
    private val helper=object: SQLiteOpenHelper(credential,name,null,1,DatabaseErrorHandler {
        android.util.Log.e("SolSleepExportHistory","Export history corruption detected; original files retained")
    }) {
        override fun onConfigure(db: SQLiteDatabase) {
            check(credential.getSystemService(UserManager::class.java).isUserUnlocked)
            db.execSQL("PRAGMA synchronous=FULL")
        }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE attempts(sequence INTEGER PRIMARY KEY AUTOINCREMENT,
                id TEXT NOT NULL UNIQUE,started_ms INTEGER NOT NULL,updated_ms INTEGER NOT NULL CHECK(updated_ms>=started_ms),
                display_name TEXT,stage TEXT NOT NULL CHECK(stage IN ('STARTED','WRITTEN','VERIFIED','FAILED_WRITE','UNVERIFIED')),
                fingerprint TEXT,CHECK((stage='VERIFIED' AND fingerprint IS NOT NULL AND length(fingerprint)=64
                AND fingerprint NOT GLOB '*[^0-9a-f]*') OR (stage!='VERIFIED' AND fingerprint IS NULL)))""")
            db.execSQL("CREATE INDEX verified_exports ON attempts(stage,sequence)")
        }
        override fun onUpgrade(db: SQLiteDatabase,oldVersion: Int,newVersion: Int) {throw SQLiteException("No validated export history migration")}
        override fun onDowngrade(db: SQLiteDatabase,oldVersion: Int,newVersion: Int) {throw SQLiteException("Export history downgrade not supported")}
    }.apply {setWriteAheadLoggingEnabled(true)}
    private fun database(): SQLiteDatabase=synchronized(openLock) {helper.writableDatabase}
    @Synchronized override fun start(displayName: String?,at: Instant): String=transaction {db->
        val id=UUID.randomUUID().toString()
        JournalExportAttempt(id,1,at,at,displayName,JournalExportStage.STARTED,null)
        db.insertOrThrow("attempts",null,ContentValues().apply {
            put("id",id);put("started_ms",at.toEpochMilli());put("updated_ms",at.toEpochMilli())
            put("display_name",displayName);put("stage",JournalExportStage.STARTED.name)
        });id
    }
    @Synchronized override fun written(id: String,at: Instant)=transition(id,JournalExportStage.STARTED,JournalExportStage.WRITTEN,null,at)
    @Synchronized override fun verified(id: String,fingerprint: String,at: Instant)=
        transition(id,JournalExportStage.WRITTEN,JournalExportStage.VERIFIED,fingerprint,at)
    @Synchronized override fun failed(id: String,at: Instant)=transaction {db->
        val old=read(db,id)
        val next=when(old.stage) {
            JournalExportStage.STARTED->JournalExportStage.FAILED_WRITE
            JournalExportStage.WRITTEN->JournalExportStage.UNVERIFIED
            else->error("Export attempt is already terminal")
        }
        update(db,old,next,null,at)
    }
    @Synchronized fun summary(): JournalExportSummary=transaction {db->
        fun latest(selection: String?)=db.query("attempts",null,selection,null,null,null,"sequence DESC","1").use {c->
            if(c.moveToFirst()) decode(c) else null
        }
        JournalExportSummary(latest(null),latest("stage='VERIFIED'"))
    }
    private fun transition(id: String,from: JournalExportStage,to: JournalExportStage,fingerprint: String?,at: Instant)=transaction {db->
        val old=read(db,id);check(old.stage==from) {"Export attempt changed"};update(db,old,to,fingerprint,at)
    }
    private fun update(db: SQLiteDatabase,old: JournalExportAttempt,to: JournalExportStage,fingerprint: String?,at: Instant) {
        val updated=old.copy(stage=to,fingerprint=fingerprint,updatedAt=maxOf(old.updatedAt,at))
        check(db.update("attempts",ContentValues().apply {
            put("stage",updated.stage.name);put("fingerprint",fingerprint);put("updated_ms",updated.updatedAt.toEpochMilli())
        },"id=? AND stage=?",arrayOf(old.id,old.stage.name))==1) {"Export attempt changed"}
    }
    private fun read(db: SQLiteDatabase,id: String)=db.query("attempts",null,"id=?",arrayOf(id),null,null,null).use {c->
        check(c.moveToFirst()) {"Missing export attempt"};decode(c)
    }
    private fun decode(c: Cursor)=JournalExportAttempt(c.getString(c.getColumnIndexOrThrow("id")),c.getLong(c.getColumnIndexOrThrow("sequence")),
        Instant.ofEpochMilli(c.getLong(c.getColumnIndexOrThrow("started_ms"))),Instant.ofEpochMilli(c.getLong(c.getColumnIndexOrThrow("updated_ms"))),
        c.getColumnIndexOrThrow("display_name").let {if(c.isNull(it)) null else c.getString(it)},
        JournalExportStage.valueOf(c.getString(c.getColumnIndexOrThrow("stage"))),
        c.getColumnIndexOrThrow("fingerprint").let {if(c.isNull(it)) null else c.getString(it)})
    private fun <T> transaction(block: (SQLiteDatabase)->T): T {
        val db=database();db.beginTransactionNonExclusive()
        try {val result=block(db);db.setTransactionSuccessful();return result} finally {db.endTransaction()}
    }
    @Synchronized override fun close()=helper.close()
}
