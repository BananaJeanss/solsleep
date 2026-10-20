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

/** Session ownership and a rebuildable progress index. Raw checksummed files remain authoritative.
 * Credential protected, independent of alarm and manual journal startup. No destructive recovery.
 */
class CaptureStore(context: Context,name: String="motion-capture.db"): AutoCloseable {
    companion object {private val locks=Array(32) {Any()}}
    private val credential=context.applicationContext.also {check(!it.isDeviceProtectedStorage)}
    private val openLock=locks[(credential.getDatabasePath(name).absolutePath.hashCode() and Int.MAX_VALUE)%locks.size]
    private val helper=object: SQLiteOpenHelper(credential,name,null,3,DatabaseErrorHandler {
        android.util.Log.e("SolSleepCapture","Capture database damaged; original files retained")
    }) {
        override fun onConfigure(db: SQLiteDatabase) {
            check(credential.getSystemService(UserManager::class.java).isUserUnlocked)
            db.setForeignKeyConstraintsEnabled(true);db.execSQL("PRAGMA synchronous=FULL")
        }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE sessions(id TEXT PRIMARY KEY NOT NULL,request_id TEXT NOT NULL UNIQUE,
                owner_id TEXT NOT NULL,state TEXT NOT NULL CHECK(state IN ('PREPARING','RECORDING','PAUSED','INTERRUPTED','COMPLETED','FAILED','DELETING')),
                started_ms INTEGER NOT NULL,updated_ms INTEGER NOT NULL,revision INTEGER NOT NULL CHECK(revision>=1))""")
            db.execSQL("CREATE UNIQUE INDEX single_open_session ON sessions((1)) WHERE state NOT IN ('COMPLETED','FAILED','DELETING')")
            db.execSQL("""CREATE TABLE events(sequence INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,reason TEXT NOT NULL,
                wall_ms INTEGER NOT NULL,elapsed_ns INTEGER NOT NULL CHECK(elapsed_ns>=0),boot_id TEXT NOT NULL,detail INTEGER)""")
            createArchiveSchema(db)
            db.execSQL("""CREATE TABLE streams(id TEXT PRIMARY KEY NOT NULL,
                session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,boot_id TEXT NOT NULL,
                anchor_ms INTEGER NOT NULL,anchor_ns INTEGER NOT NULL CHECK(anchor_ns>=0),
                sensor_name TEXT NOT NULL,sensor_vendor TEXT NOT NULL,sensor_version INTEGER NOT NULL,
                resolution REAL NOT NULL,maximum_range REAL NOT NULL,wake_up INTEGER NOT NULL CHECK(wake_up IN (0,1)),
                committed_samples INTEGER NOT NULL DEFAULT 0 CHECK(committed_samples>=0),
                rejected_samples INTEGER NOT NULL DEFAULT 0 CHECK(rejected_samples>=0),gaps INTEGER NOT NULL DEFAULT 0 CHECK(gaps>=0),
                acquisition_payload TEXT CHECK(acquisition_payload IS NULL OR length(CAST(acquisition_payload AS BLOB))<=1024))""")
        }
        override fun onUpgrade(db: SQLiteDatabase,oldVersion: Int,newVersion: Int) {
            check(oldVersion in 1..2 && newVersion==3) {"No validated capture migration"}
            if(oldVersion<2) createArchiveSchema(db)
            db.execSQL("ALTER TABLE streams ADD COLUMN acquisition_payload TEXT CHECK(acquisition_payload IS NULL OR length(CAST(acquisition_payload AS BLOB))<=1024)")
        }
        override fun onDowngrade(db: SQLiteDatabase,oldVersion: Int,newVersion: Int) {throw SQLiteException("Capture downgrade not supported")}
    }.apply {setWriteAheadLoggingEnabled(true)}
    private fun createArchiveSchema(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE events ADD COLUMN original_sequence INTEGER CHECK(original_sequence>0)")
        db.execSQL("CREATE UNIQUE INDEX imported_event_order ON events(session_id,original_sequence) WHERE original_sequence IS NOT NULL")
        db.execSQL("CREATE TABLE deleted_sessions(id TEXT PRIMARY KEY NOT NULL,request_id TEXT NOT NULL UNIQUE)")
    }
    private fun database()=synchronized(openLock) {helper.writableDatabase}
    private fun <T> transaction(block: (SQLiteDatabase)->T): T {
        val db=database();db.beginTransactionNonExclusive()
        try {val value=block(db);db.setTransactionSuccessful();return value} finally {db.endTransaction()}
    }
    @Synchronized fun sessions(limit: Int=20,offset: Int=0): List<CaptureSession> {
        require(limit in 1..100 && offset>=0)
        return database().query("sessions",null,null,null,null,null,"rowid DESC","$offset,$limit").use {c->buildList {while(c.moveToNext()) add(session(c))}}
    }
    @Synchronized fun active(): CaptureSession?=database().query("sessions",null,"state NOT IN ('COMPLETED','FAILED','DELETING')",null,null,null,null).use {c->if(c.moveToFirst()) session(c) else null}
    @Synchronized fun get(id: UUID): CaptureSession?=read(database(),id)
    @Synchronized fun start(requestId: UUID,ownerId: UUID,wall: Instant,elapsed: Long,boot: UUID): CaptureSession=transaction {db->
        db.query("sessions",null,"request_id=?",arrayOf(requestId.toString()),null,null,null).use {c->
            if(c.moveToFirst()) return@transaction session(c)
        }
        check(!db.query("deleted_sessions",arrayOf("id"),"request_id=?",arrayOf(requestId.toString()),null,null,null).use {it.moveToFirst()}) {"Request was deleted"}
        // The unique partial index also enforces this across helpers, not just this object.
        val value=CaptureSession(UUID.randomUUID(),requestId,ownerId,CaptureState.PREPARING,wall,wall,1)
        db.insertOrThrow("sessions",null,values(value));event(db,value.id,CaptureReason.START,wall,elapsed,boot,null);value
    }
    @Synchronized fun transition(id: UUID,expectedRevision: Long,to: CaptureState,reason: CaptureReason,
        owner: UUID,wall: Instant,elapsed: Long,boot: UUID): CaptureSession=transaction {db->
        val old=requireNotNull(read(db,id));check(old.revision==expectedRevision) {"Capture changed"}
        check(CaptureTransitions.permits(old.state,to)) {"Invalid capture transition"}
        // Only a new explicit acquisition may change process ownership.
        check(old.ownerId==owner || to in setOf(CaptureState.PREPARING,CaptureState.INTERRUPTED,CaptureState.COMPLETED,CaptureState.DELETING))
        val next=old.copy(state=to,ownerId=owner,updatedAt=wall,revision=Math.addExact(old.revision,1))
        check(db.update("sessions",values(next),"id=? AND revision=?",arrayOf(id.toString(),expectedRevision.toString()))==1)
        event(db,id,reason,wall,elapsed,boot,null);next
    }
    /** On a new process, never resume acquisition automatically or label missing time as captured. */
    @Synchronized fun recoverOrphans(owner: UUID,wall: Instant,elapsed: Long,boot: UUID)=transaction {db->
        db.query("sessions",null,"owner_id!=? AND state IN ('PREPARING','RECORDING')",arrayOf(owner.toString()),null,null,null).use {c->
            while(c.moveToNext()) {
                val old=session(c);val next=old.copy(state=CaptureState.INTERRUPTED,ownerId=owner,updatedAt=wall,revision=Math.addExact(old.revision,1))
                check(db.update("sessions",values(next),"id=? AND revision=?",arrayOf(old.id.toString(),old.revision.toString()))==1)
                event(db,old.id,CaptureReason.PROCESS_LOSS,wall,elapsed,boot,null)
            }
        }
    }
    @Synchronized fun openStream(value: CaptureStream,owner: UUID)=transaction {db->
        require(value.committedSamples==0L && value.rejectedSamples==0L && value.gaps==0L && value.elapsedNanos>=0)
        val session=requireNotNull(read(db,value.sessionId));check(session.ownerId==owner && session.state==CaptureState.PREPARING)
        db.insertOrThrow("streams",null,ContentValues().apply {
            put("id",value.id.toString());put("session_id",value.sessionId.toString());put("boot_id",value.bootId.toString())
            put("anchor_ms",value.wall.toEpochMilli());put("anchor_ns",value.elapsedNanos)
            put("sensor_name",value.source.name);put("sensor_vendor",value.source.vendor);put("sensor_version",value.source.version)
            put("resolution",value.source.resolution);put("maximum_range",value.source.maximumRange);put("wake_up",if(value.source.wakeUp) 1 else 0)
            put("acquisition_payload",value.provenance?.let(CaptureProvenanceCodec::encode))
        });Unit
    }
    @Synchronized fun checkpoint(stream: UUID,samples: Long,rejected: Long,gaps: Long)=transaction {db->
        require(samples>=0 && rejected>=0 && gaps>=0)
        check(db.update("streams",ContentValues().apply {put("committed_samples",samples);put("rejected_samples",rejected);put("gaps",gaps)},
            "id=? AND committed_samples<=? AND rejected_samples<=? AND gaps<=?",arrayOf(stream.toString(),samples.toString(),rejected.toString(),gaps.toString()))==1)
    }
    @Synchronized fun note(id: UUID,reason: CaptureReason,wall: Instant,elapsed: Long,boot: UUID,detail: Long?=null)=transaction {db->
        check(read(db,id)?.terminal==false);event(db,id,reason,wall,elapsed,boot,detail)
    }
    @Synchronized fun events(id: UUID): List<CaptureEvent> =database().query("events",null,"session_id=?",arrayOf(id.toString()),null,null,"COALESCE(original_sequence,sequence)").use {c->
        buildList {while(c.moveToNext()) add(CaptureEvent(c.getColumnIndexOrThrow("original_sequence").let {if(c.isNull(it)) c.long("sequence") else c.getLong(it)},id,CaptureReason.valueOf(c.string("reason")),
            Instant.ofEpochMilli(c.long("wall_ms")),c.long("elapsed_ns"),UUID.fromString(c.string("boot_id")),c.getColumnIndexOrThrow("detail").let {if(c.isNull(it)) null else c.getLong(it)}))}
    }
    @Synchronized fun streams(id: UUID): List<CaptureStream> =database().query("streams",null,"session_id=?",arrayOf(id.toString()),null,null,"rowid").use {c->
        buildList {while(c.moveToNext()) add(CaptureStream(UUID.fromString(c.string("id")),id,UUID.fromString(c.string("boot_id")),
            Instant.ofEpochMilli(c.long("anchor_ms")),c.long("anchor_ns"),CaptureSource(c.string("sensor_name"),c.string("sensor_vendor"),c.int("sensor_version"),
                c.getFloat(c.getColumnIndexOrThrow("resolution")),c.getFloat(c.getColumnIndexOrThrow("maximum_range")),c.int("wake_up")==1),
            c.long("committed_samples"),c.long("rejected_samples"),c.long("gaps"),
            c.getColumnIndexOrThrow("acquisition_payload").let {if(c.isNull(it)) null else CaptureProvenanceCodec.decode(c.getString(it))}))}
    }
    /** Call only AFTER deleting owned raw files. A crash leaves the DELETING intent retryable. */
    @Synchronized fun finishDelete(id: UUID)=transaction {db->
        val old=requireNotNull(read(db,id));check(old.state==CaptureState.DELETING)
        db.insertOrThrow("deleted_sessions",null,ContentValues().apply {put("id",old.id.toString());put("request_id",old.requestId.toString())})
        check(db.delete("sessions","id=? AND state='DELETING'",arrayOf(id.toString()))==1)
    }
    /** Includes durably confirmed DELETING intents even if raw erasure has not finished.
     * No historical timestamps are fabricated. Existing v2/v3 schema stays unchanged.
     */
    @Synchronized fun deletionSnapshot(): RecordingDeletionSnapshot=transaction {db->
        db.rawQuery("SELECT id,request_id FROM deleted_sessions UNION ALL SELECT id,request_id FROM sessions WHERE state='DELETING' ORDER BY id LIMIT ?",
            arrayOf((RecordingDeletionArchive.MAX_MARKERS+1).toString())).use {cursor->
            val markers=buildList {while(cursor.moveToNext()) add(RecordingDeletion(
                UUID.fromString(cursor.getString(0)),UUID.fromString(cursor.getString(1))))}
            RecordingDeletionSnapshot(markers)
        }
    }
    /** Additive identity import. A marker NEVER deletes or changes a present recording.
     * Preview and commit share classification; commit rechecks under the write transaction.
     */
    @Synchronized fun mergeDeletionHistory(snapshot: RecordingDeletionSnapshot,previewOnly: Boolean=false): RecordingDeletionMerge=transaction {db->
        check(db.rawQuery("SELECT 1 FROM sessions WHERE state NOT IN ('COMPLETED','FAILED','DELETING') LIMIT 1",null).use {!it.moveToFirst()}) {
            "Finish active recording before importing deletion history"
        }
        var added=0;var known=0;var live=0;var collisions=0
        snapshot.markers.forEach {marker->
            val args=arrayOf(marker.sessionId.toString(),marker.requestId.toString())
            if(db.rawQuery("SELECT 1 FROM sessions WHERE id=? OR request_id=? LIMIT 1",args).use {it.moveToFirst()}) live++
            else {
                val existing=db.rawQuery("SELECT id,request_id FROM deleted_sessions WHERE id=? OR request_id=?",args).use {cursor->
                    buildList {while(cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1))}
                }
                when {
                    existing.isEmpty()->{
                        if(!previewOnly) db.insertOrThrow("deleted_sessions",null,ContentValues().apply {
                            put("id",args[0]);put("request_id",args[1])
                        })
                        added++
                    }
                    existing.size==1 && existing.single()==(args[0] to args[1])->known++
                    else->collisions++
                }
            }
        }
        RecordingDeletionMerge(added,known,live,collisions)
    }
    /** Metadata snapshot is transactional; only terminal records can be portable. */
    @Synchronized fun recording(id: UUID): CaptureRecording=transaction {db->
        listOf("streams" to MotionArchive.MAX_STREAMS,"events" to MotionArchive.MAX_EVENTS).forEach {(table,limit)->
            db.rawQuery("SELECT COUNT(*) FROM $table WHERE session_id=?",arrayOf(id.toString())).use {c->
                check(c.moveToFirst() && c.getLong(0)<=limit) {"Recording metadata exceeds archive limit; retained"}
            }
        }
        val value=CaptureRecording(requireNotNull(read(db,id)),streams(id),events(id))
        value.validate();value
    }
    @Synchronized fun restoreConflict(value: CaptureRecording): Boolean=conflicts(database(),value)
    private fun conflicts(db: SQLiteDatabase,value: CaptureRecording): Boolean {
        val s=value.session
        fun exists(table: String,where: String,args: Array<String>)=db.query(table,arrayOf("id"),where,args,null,null,null,"1").use {it.moveToFirst()}
        if(exists("sessions","id=? OR request_id=?",arrayOf(s.id.toString(),s.requestId.toString())) ||
            exists("deleted_sessions","id=? OR request_id=?",arrayOf(s.id.toString(),s.requestId.toString()))) return true
        return value.streams.any {exists("streams","id=?",arrayOf(it.id.toString()))}
    }
    /** Files are idempotently published before visible metadata. Caller retains a durable recovery
     * archive until this transaction commits. Never invoke with live capture running.
     */
    @Synchronized fun restore(value: CaptureRecording,publish: ()->Unit): Boolean=transaction {db->
        value.validate()
        if(conflicts(db,value)) return@transaction false
        check(db.query("sessions",arrayOf("id"),"state NOT IN ('COMPLETED','FAILED','DELETING')",null,null,null,null,"1").use {!it.moveToFirst()}) {"Finish active recording before restore"}
        publish()
        db.insertOrThrow("sessions",null,values(value.session))
        value.streams.forEach {v->
            db.insertOrThrow("streams",null,ContentValues().apply {
                put("id",v.id.toString());put("session_id",v.sessionId.toString());put("boot_id",v.bootId.toString())
                put("anchor_ms",v.wall.toEpochMilli());put("anchor_ns",v.elapsedNanos)
                put("sensor_name",v.source.name);put("sensor_vendor",v.source.vendor);put("sensor_version",v.source.version)
                put("resolution",v.source.resolution);put("maximum_range",v.source.maximumRange);put("wake_up",if(v.source.wakeUp) 1 else 0)
                put("committed_samples",v.committedSamples);put("rejected_samples",v.rejectedSamples);put("gaps",v.gaps)
                put("acquisition_payload",v.provenance?.let(CaptureProvenanceCodec::encode))
            })
        }
        value.events.forEach {v->
            db.insertOrThrow("events",null,ContentValues().apply {
                put("session_id",v.sessionId.toString());put("original_sequence",v.sequence);put("reason",v.reason.name)
                put("wall_ms",v.wall.toEpochMilli());put("elapsed_ns",v.elapsedNanos);put("boot_id",v.bootId.toString());put("detail",v.detail)
            })
        }
        true
    }
    private fun read(db: SQLiteDatabase,id: UUID)=db.query("sessions",null,"id=?",arrayOf(id.toString()),null,null,null).use {c->if(c.moveToFirst()) session(c) else null}
    private fun session(c: Cursor)=CaptureSession(UUID.fromString(c.string("id")),UUID.fromString(c.string("request_id")),UUID.fromString(c.string("owner_id")),
        CaptureState.valueOf(c.string("state")),Instant.ofEpochMilli(c.long("started_ms")),Instant.ofEpochMilli(c.long("updated_ms")),c.long("revision"))
    private fun values(s: CaptureSession)=ContentValues().apply {
        put("id",s.id.toString());put("request_id",s.requestId.toString());put("owner_id",s.ownerId.toString());put("state",s.state.name)
        put("started_ms",s.startedAt.toEpochMilli());put("updated_ms",s.updatedAt.toEpochMilli());put("revision",s.revision)
    }
    private fun event(db: SQLiteDatabase,id: UUID,reason: CaptureReason,wall: Instant,elapsed: Long,boot: UUID,detail: Long?) {
        require(elapsed>=0 && wall.nano%1_000_000==0)
        db.insertOrThrow("events",null,ContentValues().apply {
            put("session_id",id.toString());put("reason",reason.name);put("wall_ms",wall.toEpochMilli())
            put("elapsed_ns",elapsed);put("boot_id",boot.toString());put("detail",detail)
        })
    }
    private fun Cursor.string(name: String)=getString(getColumnIndexOrThrow(name))
    private fun Cursor.long(name: String)=getLong(getColumnIndexOrThrow(name))
    private fun Cursor.int(name: String)=getInt(getColumnIndexOrThrow(name))
    @Synchronized override fun close()=helper.close()
}
