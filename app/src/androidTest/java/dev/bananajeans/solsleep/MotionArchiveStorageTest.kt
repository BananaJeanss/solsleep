// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep

import android.database.sqlite.*
import android.net.Uri
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
class MotionArchiveStorageTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val name="motion-archive-${UUID.randomUUID()}.db"
    private val directory=context.noBackupFilesDir.resolve("archive-test-${UUID.randomUUID()}")
    private val id=UUID.randomUUID();private val boot=UUID.randomUUID();private val stream=UUID.randomUUID()
    private val wall=Instant.parse("2026-10-17T23:00:00Z")
    private val session=CaptureSession(id,UUID.randomUUID(),UUID.randomUUID(),CaptureState.COMPLETED,wall,wall.plusSeconds(2),7)
    private val source=CaptureStream(stream,id,boot,wall,100,CaptureSource("加速度 🌙","Native test",1,0.01f,40f,false),1,2,3)
    private val recording=CaptureRecording(session,listOf(source),listOf(
        CaptureEvent(77,id,CaptureReason.START,wall,100,boot,null),CaptureEvent(999,id,CaptureReason.STOP,wall.plusSeconds(2),102,boot,0)))
    private val chunks=(0L..1L).map {MotionChunk(id,stream,boot,it,wall,100,listOf(MotionSample(101+it,-0.0f,1f,9.81f)))}
    private val uris=mutableListOf<Uri>()
    private fun archive()=ByteArrayOutputStream().also {out->MotionArchive.write(recording,out) {write->chunks.forEach {write(MotionChunkCodec.encode(it))}}}.toByteArray()
    private fun documents(checkpoint: ()->Unit={})=MotionDocuments(context,name,directory,checkpoint=checkpoint)
    private fun uri(mode: String)=Uri.parse("content://dev.bananajeans.solsleep.debug.test.journal/$mode/${UUID.randomUUID()}").also {uris+=it}
    @After fun cleanup() {
        MotionFiles.forSession(context,id).eraseOwned();context.deleteDatabase(name);directory.deleteRecursively()
        uris.forEach {context.contentResolver.delete(it,null,null)}
    }
    @Test fun realProviderRestorePreservesCompleteMetadataAndExactChunksAcrossReopen() {
        val uri=uri("normal");context.contentResolver.openOutputStream(uri,"wt")!!.use {it.write(archive())}
        val pending=documents().stage(uri);assertEquals(recording,pending.preview.recording);assertFalse(pending.conflict)
        assertTrue(documents().restore(pending.preview.fingerprint));assertFalse(documents().hasPending())
        CaptureStore(context,name).use {store->
            assertEquals(recording,store.recording(id));assertNull(store.active())
            assertTrue(MotionFiles.inspectSession(context,store,id).issues.isEmpty())
        }
        val files=mutableListOf<ByteArray>();MotionFiles.forSession(context,id).exportBytes {files+=it}
        chunks.forEachIndexed {i,c->assertArrayEquals(MotionChunkCodec.encode(c),files[i])}
    }
    @Test fun interruptedPublicationRetainsStagingAndReopenRetryIsIdempotent() {
        val p=documents().stage {ByteArrayInputStream(archive())};var calls=0
        try {documents {if(++calls==1) throw IOException("Injected process loss after synced chunk")}.restore(p.preview.fingerprint);fail("Fault")}
        catch(_: IOException) { }
        CaptureStore(context,name).use {assertNull(it.get(id));assertNull(it.active())}
        assertEquals(1L,MotionFiles.forSession(context,id).summary().chunks)
        assertEquals(p,documents().inspectPending());assertTrue(documents().restore(p.preview.fingerprint))
        assertEquals(2L,MotionFiles.forSession(context,id).summary().chunks)
        CaptureStore(context,name).use {assertEquals(recording,it.recording(id))}
    }
    @Test fun sqlFailureAfterRawPublicationRetainsAllBytesAndCanRetryWithoutDuplicates() {
        val p=documents().stage {ByteArrayInputStream(archive())}
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("CREATE TRIGGER fail_restore BEFORE INSERT ON events BEGIN SELECT RAISE(ABORT,'injected'); END")
        }
        try {documents().restore(p.preview.fingerprint);fail("SQL fault")} catch(_: SQLiteException) { }
        CaptureStore(context,name).use {assertNull(it.get(id));assertTrue(it.streams(id).isEmpty());assertTrue(it.events(id).isEmpty())}
        assertEquals(2L,MotionFiles.forSession(context,id).summary().chunks);assertTrue(documents().hasPending())
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READWRITE).use {it.execSQL("DROP TRIGGER fail_restore")}
        assertTrue(documents().restore(p.preview.fingerprint));CaptureStore(context,name).use {assertEquals(recording,it.recording(id))}
    }
    @Test fun existingAndDeletedIdentitiesWinAndNeverPublishFiles() {
        CaptureStore(context,name).use {store->
            assertTrue(store.restore(recording) {})
            var writes=0;assertFalse(store.restore(recording) {writes++});assertEquals(0,writes)
            val deleting=store.transition(id,7,CaptureState.DELETING,CaptureReason.DELETE,session.ownerId,wall,104,boot)
            assertEquals(8L,deleting.revision);store.finishDelete(id)
        }
        val p=documents().stage {ByteArrayInputStream(archive())};assertTrue(p.conflict)
        assertFalse(documents().restore(p.preview.fingerprint))
        assertEquals(0L,MotionFiles.forSession(context,id).summary().chunks)
        CaptureStore(context,name).use {assertNull(it.get(id));assertTrue(it.restoreConflict(recording))}
    }
    @Test fun requestAndStreamIdentityConflictsCannotReplaceLocalRecords() {
        CaptureStore(context,name).use {store->
            val local=recording.copy(session=session.copy(id=UUID.randomUUID()),streams=emptyList(),
                events=recording.events.map {it.copy(sessionId=UUID.randomUUID())})
            val fixed=local.copy(events=local.events.map {it.copy(sessionId=local.session.id)})
            assertTrue(store.restore(fixed) {});assertFalse(store.restore(recording) {fail("Request collision")})
            assertEquals(fixed,store.recording(fixed.session.id))
        }
    }
    @Test fun unrelatedOrConflictingRawFilesAreRetainedAndNeverMergedIntoRestoredRecording() {
        val original=chunks.first().copy(samples=listOf(MotionSample(101,5f,6f,7f)))
        MotionFiles.forSession(context,id).publish(original)
        val pending=documents().stage {ByteArrayInputStream(archive())}
        try {documents().restore(pending.preview.fingerprint);fail("Conflicting local bytes")} catch(_: IllegalStateException) { }
        assertEquals(listOf(original),MotionFiles.forSession(context,id).recover().chunks)
        CaptureStore(context,name).use {assertNull(it.get(id))};assertTrue(documents().hasPending())
    }
    @Test fun streamCollisionLeavesAllOriginalMetadataAndNeverInvokesRawPublication() {
        val other=UUID.randomUUID()
        val local=recording.copy(session=session.copy(id=other,requestId=UUID.randomUUID()),
            streams=recording.streams.map {it.copy(sessionId=other)},events=recording.events.map {it.copy(sessionId=other)})
        CaptureStore(context,name).use {store->
            assertTrue(store.restore(local) {});assertFalse(store.restore(recording) {fail("Stream collision")})
            assertEquals(local,store.recording(other));assertNull(store.get(id))
        }
    }
    @Test fun liveRecordingBlocksRestoreAndLeavesOriginalPendingCopy() {
        val p=documents().stage {ByteArrayInputStream(archive())}
        CaptureStore(context,name).use {it.start(UUID.randomUUID(),UUID.randomUUID(),wall,100,boot)}
        try {documents().restore(p.preview.fingerprint);fail("Live capture")} catch(_: IllegalStateException) { }
        assertEquals(p,documents().inspectPending());assertEquals(0L,MotionFiles.forSession(context,id).summary().chunks)
    }
    @Test fun corruptAndTruncatedArchivesNeverEnterLiveStorageAndCanBeDiscarded() {
        val bytes=archive();bytes[bytes.size-1]=(bytes.last().toInt() xor 1).toByte()
        try {documents().stage {ByteArrayInputStream(bytes)};fail("Checksum")} catch(_: IllegalArgumentException) { }
        assertTrue(documents().hasPending());CaptureStore(context,name).use {assertNull(it.get(id))}
        documents().discard();assertFalse(documents().hasPending())
        try {documents().stage {ByteArrayInputStream(archive().copyOf(20))};fail("Truncated")} catch(_: EOFException) { }
        documents().discard();assertFalse(documents().hasPending())
    }
    @Test fun lowSpaceFailsBeforeReadingProviderOrOpeningDestinationAndRetainsRecoveryCopy() {
        val low=MotionDocuments(context,name,directory,availableBytes={0})
        var opened=false
        try {low.stage {opened=true;ByteArrayInputStream(archive())};fail("Low space")}
        catch(_: MotionSpaceException) { }
        assertFalse(opened);assertFalse(documents().hasPending())
        val pending=documents().stage {ByteArrayInputStream(archive())}
        try {low.restore(pending.preview.fingerprint);fail("Restore needs space")}
        catch(_: MotionSpaceException) { }
        assertEquals(pending,documents().inspectPending());assertEquals(0L,MotionFiles.forSession(context,id).summary().chunks)
        assertTrue(documents().restore(pending.preview.fingerprint))
        try {low.export(id,{opened=true;ByteArrayOutputStream()},{throw AssertionError()});fail("Export needs staging space")}
        catch(_: MotionSpaceException) { }
        assertFalse(opened);CaptureStore(context,name).use {assertEquals(recording,it.recording(id))}
    }
    @Test fun interruptedCompleteStagingIsValidatedAndRecoveredWithoutProviderAccess() {
        assertFalse(documents().hasPending());directory.resolve("preparing.solmotion").writeBytes(archive())
        val pending=documents().inspectPending()!!;assertEquals(recording,pending.preview.recording)
        assertTrue(directory.resolve("pending.solmotion").exists());assertFalse(directory.resolve("preparing.solmotion").exists())
        assertTrue(documents().restore(pending.preview.fingerprint))
    }
    @Test fun exportRealProviderReadbackDenialTruncationAndReplacementRetainSource() {
        CaptureStore(context,name).use {assertTrue(it.restore(recording) {chunks.forEach(MotionFiles.forSession(context,id)::publish)})}
        val normal=uri("normal");assertEquals(recording,documents().export(id,normal).recording)
        listOf("deny_read","truncated","replaced").forEach {scenario->
            try {documents().export(id,uri(scenario));fail("Unverified $scenario")} catch(_: MotionVerificationException) { }
            CaptureStore(context,name).use {assertEquals(recording,it.recording(id))}
        }
        try {documents().export(id,uri("deny_write"));fail("Write denial")} catch(e: IOException) {assertFalse(e is MotionVerificationException)}
        assertEquals(chunks,MotionFiles.forSession(context,id).recover().chunks)
    }
    @Test fun v1MigrationRetainsRowsSourceFieldsAndEventOrderAndV2FixtureRestores() {
        context.deleteDatabase(name)
        val path=context.getDatabasePath(name);path.parentFile!!.mkdirs()
        fun fixture(version: Int) {
            SQLiteDatabase.openOrCreateDatabase(path,null).use {db->
                val sql=InstrumentationRegistry.getInstrumentation().context.assets.open("capture/$version.sql").bufferedReader().use {it.readText()}
                sql.lines().filterNot {it.trimStart().startsWith("--")}.joinToString("\n").split(';').map {it.trim()}.filter {it.isNotEmpty()}.forEach {db.execSQL(it)}
                if(version==1) {
                    db.execSQL("INSERT INTO sessions VALUES(?,?,?,?,?,?,?)",arrayOf<Any>(id.toString(),session.requestId.toString(),session.ownerId.toString(),"COMPLETED",wall.toEpochMilli(),wall.plusSeconds(2).toEpochMilli(),7))
                    db.execSQL("INSERT INTO events VALUES(77,?,'STOP',?,102,?,NULL)",arrayOf<Any>(id.toString(),wall.toEpochMilli(),boot.toString()))
                    db.execSQL("INSERT INTO streams VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",arrayOf<Any>(stream.toString(),id.toString(),boot.toString(),wall.toEpochMilli(),100,"Migrated","Vendor",1,0.01f,40f,0,1,2,3))
                }
            }
        }
        fixture(1)
        CaptureStore(context,name).use {store->
            assertEquals(session,store.get(id));assertEquals(77L,store.events(id).single().sequence)
            assertEquals(1L,store.streams(id).single().committedSamples);assertEquals("Migrated",store.streams(id).single().source.name)
            assertFalse(store.restore(recording) {fail("Must retain migrated identity")})
        }
        SQLiteDatabase.openDatabase(path.path,null,SQLiteDatabase.OPEN_READONLY).use {assertEquals(2,it.version)}
        context.deleteDatabase(name);fixture(2)
        CaptureStore(context,name).use {assertTrue(it.restore(recording) {});assertEquals(recording,it.recording(id))}
    }
}
