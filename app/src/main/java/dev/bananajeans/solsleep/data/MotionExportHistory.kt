// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.sqlite.*
import android.os.UserManager
import dev.bananajeans.solsleep.core.*
import java.time.Instant
import java.util.UUID

/** Separate credential-protected receipts. Not a source of recordings or current cloud availability. */
class MotionExportHistory(context: Context,name: String="motion-exports.db"): MotionExportAudit,AutoCloseable {
    companion object {private val locks=Array(32) {Any()}}
    private val credential=context.applicationContext.also {check(!it.isDeviceProtectedStorage)}
    private val openLock=locks[(credential.getDatabasePath(name).absolutePath.hashCode() and Int.MAX_VALUE)%locks.size]
    private val helper=object: SQLiteOpenHelper(credential,name,null,1,DatabaseErrorHandler {
        android.util.Log.e("SolSleepMotionExport","Receipt corruption detected; original files retained")
    }) {
        override fun onConfigure(db: SQLiteDatabase) {
            check(credential.getSystemService(UserManager::class.java).isUserUnlocked)
            db.execSQL("PRAGMA synchronous=FULL")
        }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE attempts(sequence INTEGER PRIMARY KEY AUTOINCREMENT,
                id TEXT NOT NULL UNIQUE,session_id TEXT NOT NULL,started_ms INTEGER NOT NULL,
                updated_ms INTEGER NOT NULL CHECK(updated_ms>=started_ms),
                destination_hash TEXT,authority TEXT,display_name TEXT,
                stage TEXT NOT NULL CHECK(stage IN ('STARTED','WRITTEN','VERIFIED','FAILED_WRITE','UNVERIFIED')),
                fingerprint TEXT,
                CHECK(destination_hash IS NOT NULL OR (authority IS NULL AND display_name IS NULL)),
                CHECK(destination_hash IS NULL OR (length(destination_hash)=64 AND destination_hash NOT GLOB '*[^0-9a-f]*')),
                CHECK(authority IS NULL OR length(authority) BETWEEN 1 AND 255),
                CHECK(display_name IS NULL OR length(display_name) BETWEEN 1 AND 256),
                CHECK((stage='VERIFIED' AND fingerprint IS NOT NULL AND length(fingerprint)=64
                AND fingerprint NOT GLOB '*[^0-9a-f]*') OR (stage!='VERIFIED' AND fingerprint IS NULL)))""")
            db.execSQL("CREATE INDEX session_exports ON attempts(session_id,sequence)")
            db.execSQL("CREATE INDEX verified_exports ON attempts(session_id,stage,sequence)")
        }
        override fun onUpgrade(db: SQLiteDatabase,oldVersion: Int,newVersion: Int) {throw SQLiteException("No validated receipt migration")}
        override fun onDowngrade(db: SQLiteDatabase,oldVersion: Int,newVersion: Int) {throw SQLiteException("Receipt downgrade refused")}
    }.apply {setWriteAheadLoggingEnabled(true)}
    private fun database(): SQLiteDatabase=synchronized(openLock) {helper.writableDatabase}
    @Synchronized override fun start(sessionId: UUID,destination: MotionExportDestination?,at: Instant): UUID=transaction {db->
        val id=UUID.randomUUID()
        MotionExportAttempt(id,sessionId,1,at,at,destination,MotionExportStage.STARTED,null)
        db.insertOrThrow("attempts",null,ContentValues().apply {
            put("id",id.toString());put("session_id",sessionId.toString());put("started_ms",at.toEpochMilli());put("updated_ms",at.toEpochMilli())
            put("destination_hash",destination?.uriSha256);put("authority",destination?.authority);put("display_name",destination?.displayName)
            put("stage",MotionExportStage.STARTED.name)
        });id
    }
    @Synchronized override fun written(id: UUID,at: Instant)=transition(id,MotionExportStage.STARTED,MotionExportStage.WRITTEN,null,at)
    @Synchronized override fun verified(id: UUID,fingerprint: String,at: Instant)=
        transition(id,MotionExportStage.WRITTEN,MotionExportStage.VERIFIED,fingerprint,at)
    @Synchronized override fun failed(id: UUID,at: Instant)=transaction {db->
        val old=read(db,id)
        val stage=when(old.stage) {
            MotionExportStage.STARTED->MotionExportStage.FAILED_WRITE
            MotionExportStage.WRITTEN->MotionExportStage.UNVERIFIED
            else->error("Receipt is already terminal")
        }
        update(db,old,stage,null,at)
    }
    @Synchronized fun summary(sessionId: UUID): MotionExportSummary=transaction {db->
        fun latest(verified: Boolean)=db.query("attempts",null,"session_id=?"+(if(verified) " AND stage='VERIFIED'" else ""),
            arrayOf(sessionId.toString()),null,null,"sequence DESC","1").use {c->if(c.moveToFirst()) decode(c) else null}
        MotionExportSummary(sessionId,latest(false),latest(true))
    }
    @Synchronized fun deleteForSession(sessionId: UUID)=transaction {db->db.delete("attempts","session_id=?",arrayOf(sessionId.toString()));Unit}
    private fun transition(id: UUID,from: MotionExportStage,to: MotionExportStage,fingerprint: String?,at: Instant)=transaction {db->
        val old=read(db,id);check(old.stage==from) {"Receipt changed"};update(db,old,to,fingerprint,at)
    }
    private fun update(db: SQLiteDatabase,old: MotionExportAttempt,stage: MotionExportStage,fingerprint: String?,at: Instant) {
        val value=old.copy(stage=stage,fingerprint=fingerprint,updatedAt=maxOf(old.updatedAt,at))
        check(db.update("attempts",ContentValues().apply {
            put("stage",value.stage.name);put("fingerprint",value.fingerprint);put("updated_ms",value.updatedAt.toEpochMilli())
        },"id=? AND stage=?",arrayOf(old.id.toString(),old.stage.name))==1) {"Receipt changed"}
    }
    private fun uuid(value: String)=UUID.fromString(value).also {require(it.toString()==value)}
    private fun read(db: SQLiteDatabase,id: UUID)=db.query("attempts",null,"id=?",arrayOf(id.toString()),null,null,null).use {c->
        check(c.moveToFirst()) {"Receipt missing"};decode(c)
    }
    private fun decode(c: Cursor): MotionExportAttempt {
        fun text(key: String)=c.getString(c.getColumnIndexOrThrow(key))
        fun nullable(key: String)=c.getColumnIndexOrThrow(key).let {if(c.isNull(it)) null else c.getString(it)}
        val hash=nullable("destination_hash");val authority=nullable("authority");val name=nullable("display_name")
        require(hash!=null || (authority==null && name==null))
        return MotionExportAttempt(uuid(text("id")),uuid(text("session_id")),c.getLong(c.getColumnIndexOrThrow("sequence")),
            Instant.ofEpochMilli(c.getLong(c.getColumnIndexOrThrow("started_ms"))),Instant.ofEpochMilli(c.getLong(c.getColumnIndexOrThrow("updated_ms"))),
            hash?.let {MotionExportDestination(it,authority,name)},MotionExportStage.valueOf(text("stage")),nullable("fingerprint"))
    }
    private fun <T> transaction(block: (SQLiteDatabase)->T): T {
        val db=database();db.beginTransactionNonExclusive()
        try {val value=block(db);db.setTransactionSuccessful();return value} finally {db.endTransaction()}
    }
    @Synchronized override fun close()=helper.close()
}
