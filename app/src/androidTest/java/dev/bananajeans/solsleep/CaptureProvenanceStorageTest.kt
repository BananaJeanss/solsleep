// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep

import android.database.sqlite.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.bananajeans.solsleep.core.*
import dev.bananajeans.solsleep.data.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.*
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CaptureProvenanceStorageTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val name="provenance-${UUID.randomUUID()}.db"
    private val id=UUID.randomUUID();private val owner=UUID.randomUUID();private val boot=UUID.randomUUID()
    private val wall=Instant.parse("2026-10-17T23:00:00Z")
    private val provenance=CaptureProvenance("a".repeat(64),"Europe/Tallinn",100_000)
    private val session=CaptureSession(id,UUID.randomUUID(),owner,CaptureState.COMPLETED,wall,wall.minusSeconds(1),7)
    private val stream=CaptureStream(UUID.randomUUID(),id,boot,wall,0,CaptureSource("Fixture","Native",1,.01f,40f,false),4,2,1,provenance)
    private val recording=CaptureRecording(session,listOf(stream),listOf(CaptureEvent(42,id,CaptureReason.STOP,wall,2_000_000_000,boot,null)))
    private val chunk=MotionChunk(id,stream.id,boot,0,wall,0,listOf(1_000_000_000L,1_100_000_000,1_200_000_000,1_600_000_000).map {MotionSample(it,-0.0f,1f,9.81f)})
    private val directory=context.noBackupFilesDir.resolve("provenance-test-${UUID.randomUUID()}")
    private val uris=mutableListOf<android.net.Uri>()
    private fun uri()=android.net.Uri.parse("content://dev.bananajeans.solsleep.debug.test.journal/normal/${UUID.randomUUID()}").also {uris+=it}
    @After fun cleanup() {context.deleteDatabase(name);MotionFiles.forSession(context,id).eraseOwned();directory.deleteRecursively();uris.forEach {context.contentResolver.delete(it,null,null)}}
    private fun fixture(version: Int) {
        context.deleteDatabase(name)
        val path=context.getDatabasePath(name);path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path,null).use {db->
            val sql=InstrumentationRegistry.getInstrumentation().context.assets.open("capture/$version.sql").bufferedReader().use {it.readText()}
            sql.lines().filterNot {it.trimStart().startsWith("--")}.joinToString("\n").split(';').map {it.trim()}.filter {it.isNotEmpty()}.forEach {db.execSQL(it)}
        }
    }
    @Test fun bothLegacySchemaMigrationsRetainExactRowsAndLeaveAcquisitionUnknown() {
        for(version in 1..2) {
            fixture(version)
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READWRITE).use {db->
                db.execSQL("INSERT INTO sessions VALUES(?,?,?,?,?,?,?)",arrayOf<Any>(id.toString(),session.requestId.toString(),owner.toString(),"COMPLETED",wall.toEpochMilli(),wall.minusSeconds(1).toEpochMilli(),7))
                db.execSQL("INSERT INTO streams VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",arrayOf<Any>(stream.id.toString(),id.toString(),boot.toString(),wall.toEpochMilli(),0,"Fixture","Native",1,.01f,40f,0,4,2,1))
                db.execSQL("INSERT INTO events(sequence,session_id,reason,wall_ms,elapsed_ns,boot_id,detail) VALUES(42,?,'STOP',?,2000000000,?,NULL)",arrayOf<Any>(id.toString(),wall.toEpochMilli(),boot.toString()))
            }
            CaptureStore(context,name).use {store->
                assertEquals(session,store.get(id));assertEquals(stream.copy(provenance=null),store.streams(id).single())
                assertEquals(recording.events,store.events(id))
            }
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READONLY).use {db->
                assertEquals(3,db.version);db.rawQuery("SELECT acquisition_payload FROM streams",null).use {c->assertTrue(c.moveToFirst());assertTrue(c.isNull(0))}
            }
        }
    }
    @Test fun independentSchemaThreeAndReopenPreserveProvenanceDuringCounterUpdates() {
        fixture(3)
        CaptureStore(context,name).use {store->assertTrue(store.restore(recording) {});store.checkpoint(stream.id,5,3,2)}
        CaptureStore(context,name).use {store->
            assertEquals(stream.copy(committedSamples=5,rejectedSamples=3,gaps=2),store.streams(id).single())
            assertEquals(session,store.get(id))
        }
    }
    @Test fun failedMigrationRollsBackAndFuturePayloadIsRetainedWithoutDefaults() {
        fixture(2)
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READWRITE).use {db->
            db.execSQL("ALTER TABLE streams ADD COLUMN acquisition_payload TEXT")
            db.execSQL("INSERT INTO sessions VALUES(?,?,?,?,?,?,?)",arrayOf<Any>(id.toString(),session.requestId.toString(),owner.toString(),"COMPLETED",wall.toEpochMilli(),wall.toEpochMilli(),7))
        }
        try {CaptureStore(context,name).use {it.sessions()};fail("Conflicting migration must retain original data")} catch(_: SQLiteException) {}
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READONLY).use {db->
            assertEquals(2,db.version);db.rawQuery("SELECT id FROM sessions",null).use {c->assertTrue(c.moveToFirst());assertEquals(id.toString(),c.getString(0))}
        }
        fixture(3)
        CaptureStore(context,name).use {assertTrue(it.restore(recording) {})}
        val future=CaptureProvenanceCodec.encode(provenance).replace("\"version\":1","\"version\":99")
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READWRITE).use {db->db.execSQL("UPDATE streams SET acquisition_payload=?",arrayOf<Any>(future))}
        try {CaptureStore(context,name).use {it.streams(id)};fail("Unknown payload must remain unknown")} catch(_: IllegalArgumentException) {}
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READONLY).use {db->
            db.rawQuery("SELECT acquisition_payload FROM streams",null).use {c->assertTrue(c.moveToFirst());assertEquals(future,c.getString(0))}
        }
    }
    @Test fun versionTwoProviderRestoreExportAndCoverageRebuildPreserveExactInput() {
        val out=ByteArrayOutputStream();MotionArchive.write(recording,out) {it(MotionChunkCodec.encode(chunk))}
        val documents=MotionDocuments(context,name,directory)
        val input=uri();context.contentResolver.openOutputStream(input,"wt")!!.use {it.write(out.toByteArray())}
        val pending=documents.stage(input);assertEquals(recording,pending.preview.recording)
        assertTrue(documents.restore(pending.preview.fingerprint))
        val original=CaptureStore(context,name).use {store->assertEquals(recording,store.recording(id));MotionFiles.coverage(context,store,id)}
        assertEquals(200_000_000L,original.streams.single().supportedNanos)
        assertEquals(400_000_000L,original.streams.single().gapNanos)
        val output=uri();documents.export(id,output)
        assertArrayEquals(out.toByteArray(),context.contentResolver.openInputStream(output)!!.use {it.readBytes()})
        CaptureStore(context,name).use {assertEquals(original,MotionFiles.coverage(context,it,id))}
        assertEquals(listOf(chunk),MotionFiles.forSession(context,id).recover().chunks)
    }
    @Test fun corruptFilesMakeCoverageUnavailableAndMissingAcknowledgedFilesRemainFlagged() {
        CaptureStore(context,name).use {store->
            assertTrue(store.restore(recording) {MotionFiles.forSession(context,id).publish(chunk)})
            val path=context.noBackupFilesDir.resolve("motion/v1/$id/${stream.id}-0.motion")
            val damaged=path.readBytes().also {it[it.lastIndex]=(it.last().toInt() xor 1).toByte()};path.writeBytes(damaged)
            try {MotionFiles.coverage(context,store,id);fail("Corruption must not look like empty data")} catch(_: IllegalArgumentException) {}
            assertArrayEquals(damaged,path.readBytes());assertEquals(recording,store.recording(id))
            MotionFiles.forSession(context,id).eraseOwned()
            val missing=MotionFiles.coverage(context,store,id)
            assertTrue(missing.incomplete);assertEquals(4L,missing.streams.single().missingIndexedSamples)
            assertEquals(0L,missing.samples);assertEquals(recording,store.recording(id))
        }
    }
}
