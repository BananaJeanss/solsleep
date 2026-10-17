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
import java.time.Instant
import java.util.UUID
import java.util.concurrent.*

@RunWith(AndroidJUnit4::class)
class CaptureStoreTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val name="capture-${UUID.randomUUID()}.db"
    private var store=CaptureStore(context,name)
    private val owner=UUID.randomUUID()
    private val boot=UUID.randomUUID()
    private val wall=Instant.parse("2026-10-17T23:00:00Z")
    private fun start(request: UUID=UUID.randomUUID())=store.start(request,owner,wall,1,boot)
    private fun move(s: CaptureSession,to: CaptureState,reason: CaptureReason)=store.transition(s.id,s.revision,to,reason,owner,wall,2,boot)
    private fun stream(s: CaptureSession)=CaptureStream(UUID.randomUUID(),s.id,boot,wall,1,
        CaptureSource("加速度 🌙","Vendor",2,0.01f,39.24f,false),0,0,0)
    @After fun cleanup() {runCatching {store.close()};context.deleteDatabase(name)}
    @Test fun completeLifecycleSourceMetadataCountersAndEventsSurviveReopen() {
        val first=start();val source=stream(first);store.openStream(source,owner)
        store.checkpoint(source.id,81,2,1)
        val recording=move(first,CaptureState.RECORDING,CaptureReason.START)
        val paused=move(recording,CaptureState.PAUSED,CaptureReason.PAUSE)
        val resumed=move(paused,CaptureState.PREPARING,CaptureReason.RESUME)
        val next=stream(resumed);store.openStream(next,owner);store.checkpoint(next.id,41,0,0)
        val final=move(move(resumed,CaptureState.RECORDING,CaptureReason.START),CaptureState.COMPLETED,CaptureReason.STOP)
        store.close();store=CaptureStore(context,name)
        assertEquals(final,store.get(final.id));assertNull(store.active())
        assertEquals(listOf(source.copy(committedSamples=81,rejectedSamples=2,gaps=1),next.copy(committedSamples=41)),store.streams(final.id))
        assertEquals(listOf(CaptureReason.START,CaptureReason.START,CaptureReason.PAUSE,CaptureReason.RESUME,CaptureReason.START,CaptureReason.STOP),store.events(final.id).map {it.reason})
        assertFalse(context.createDeviceProtectedStorageContext().getDatabasePath(name).exists())
    }
    @Test fun startIdempotencyAndTerminalRequestsCannotCreateAnotherNight() {
        val request=UUID.randomUUID();val first=start(request)
        assertEquals(first,start(request));assertEquals(1,store.events(first.id).size)
        val done=move(first,CaptureState.COMPLETED,CaptureReason.STOP)
        assertEquals(done,start(request));assertEquals(1,store.sessions().size)
        assertNotEquals(done.id,start().id)
    }
    @Test fun coldConcurrentStartsHaveOnlyOneDurableOwnerAndNoPartialLosingEvents() {
        val other=CaptureStore(context,name);val pool=Executors.newFixedThreadPool(2);val gate=CountDownLatch(1)
        try {
            val futures=listOf(store,other).map {s->pool.submit<Boolean> {
                gate.await();try {s.start(UUID.randomUUID(),owner,wall,1,boot);true} catch(_: SQLiteConstraintException) {false}
            }}
            gate.countDown();assertEquals(1,futures.count {it.get(10,TimeUnit.SECONDS)})
            assertEquals(1,store.sessions().size);assertEquals(1,store.events(store.active()!!.id).size)
        } finally {pool.shutdownNow();other.close()}
    }
    @Test fun staleActionsAndWrongOwnersCannotOverwriteResumedCapture() {
        val first=start();val recording=move(first,CaptureState.RECORDING,CaptureReason.START)
        try {move(first,CaptureState.COMPLETED,CaptureReason.STOP);fail("Stale action")} catch(_: IllegalStateException) { }
        try {store.transition(recording.id,recording.revision,CaptureState.PAUSED,CaptureReason.PAUSE,UUID.randomUUID(),wall,3,boot);fail("Wrong owner")} catch(_: IllegalStateException) { }
        assertEquals(recording,store.get(first.id));assertEquals(2,store.events(first.id).size)
    }
    @Test fun processLossCreatesOneInterruptionAndNeverFabricatesSamplesOrAutomaticResume() {
        val first=start();val source=stream(first);store.openStream(source,owner);store.checkpoint(source.id,41,0,0)
        move(first,CaptureState.RECORDING,CaptureReason.START)
        val newOwner=UUID.randomUUID()
        store.recoverOrphans(newOwner,wall.minusSeconds(500),1,UUID.randomUUID())
        store.recoverOrphans(newOwner,wall,2,boot)
        val actual=store.get(first.id)!!;assertEquals(CaptureState.INTERRUPTED,actual.state)
        assertEquals(newOwner,actual.ownerId);assertEquals(wall.minusSeconds(500),actual.updatedAt)
        assertEquals(41L,store.streams(first.id).single().committedSamples)
        assertEquals(1,store.events(first.id).count {it.reason==CaptureReason.PROCESS_LOSS})
    }
    @Test fun eventPublicationFailureRollsBackStateAndRevision() {
        val first=start()
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READWRITE).use {db->
            db.execSQL("CREATE TRIGGER reject_pause BEFORE INSERT ON events WHEN NEW.reason='PAUSE' BEGIN SELECT RAISE(ABORT,'injected'); END")
        }
        val recording=move(first,CaptureState.RECORDING,CaptureReason.START)
        try {move(recording,CaptureState.PAUSED,CaptureReason.PAUSE);fail("Must roll back")} catch(_: SQLiteException) { }
        store.close();store=CaptureStore(context,name)
        assertEquals(recording,store.get(first.id));assertEquals(2,store.events(first.id).size)
    }
    @Test fun progressCannotRegressAndDeletingIntentSurvivesUntilFilesAreRemoved() {
        val first=start();val source=stream(first);store.openStream(source,owner);store.checkpoint(source.id,41,2,1)
        try {store.checkpoint(source.id,40,2,1);fail("Cannot regress")} catch(_: IllegalStateException) { }
        try {store.finishDelete(first.id);fail("Cannot erase an active session")} catch(_: IllegalStateException) { }
        val done=move(first,CaptureState.COMPLETED,CaptureReason.STOP)
        val deleting=move(done,CaptureState.DELETING,CaptureReason.DELETE)
        store.close();store=CaptureStore(context,name);assertEquals(deleting,store.get(first.id))
        store.finishDelete(first.id);assertNull(store.get(first.id));assertTrue(store.streams(first.id).isEmpty());assertTrue(store.events(first.id).isEmpty())
    }
    @Test fun independentSchemaFixtureOpensAndFutureSchemaRetainsAllRows() {
        store.close();val path=context.getDatabasePath(name);path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path,null).use {db->
            val schema=InstrumentationRegistry.getInstrumentation().context.assets.open("capture/1.sql").bufferedReader().use {it.readText()}
            schema.lines().filterNot {it.trimStart().startsWith("--")}.joinToString("\n").split(';').map {it.trim()}.filter {it.isNotEmpty()}.forEach {db.execSQL(it)}
        }
        store=CaptureStore(context,name);val first=start();store.close()
        SQLiteDatabase.openDatabase(path.path,null,SQLiteDatabase.OPEN_READWRITE).use {it.version=99}
        store=CaptureStore(context,name)
        try {store.sessions();fail("Future schema")} catch(_: SQLiteException) { }
        SQLiteDatabase.openDatabase(path.path,null,SQLiteDatabase.OPEN_READONLY).use {db->
            assertEquals(99,db.version);db.rawQuery("SELECT id FROM sessions",null).use {c->assertTrue(c.moveToFirst());assertEquals(first.id.toString(),c.getString(0))}
        }
    }
    @Test fun corruptedCaptureDatabaseIsRetainedByteForByte() {
        store.close();val path=context.getDatabasePath(name);path.parentFile!!.mkdirs();val bytes=ByteArray(1024) {(it*17).toByte()};path.writeBytes(bytes)
        store=CaptureStore(context,name)
        try {store.sessions();fail("Corruption")} catch(_: SQLiteException) { }
        assertArrayEquals(bytes,path.readBytes())
    }
    @Test fun paginationIsStableAcrossIdenticalOrBackwardWallClockTimes() {
        val ids=(0 until 25).map {val s=start();move(s,CaptureState.COMPLETED,CaptureReason.STOP);s.id}
        assertEquals(ids.reversed().take(20),store.sessions(20).map {it.id})
        assertEquals(ids.reversed().drop(20),store.sessions(20,20).map {it.id})
    }
    @Test fun publishedFileMayLeadIndexButMissingAcknowledgedSamplesAreReported() {
        val first=start();val source=stream(first);store.openStream(source,owner)
        val files=MotionFiles.forSession(context,first.id)
        try {
            files.publish(MotionChunk(first.id,source.id,boot,0,wall,1,listOf(MotionSample(2,0f,0f,9.81f))))
            assertEquals(1L,MotionFiles.inspectSession(context,store,first.id).samples)
            assertTrue(MotionFiles.inspectSession(context,store,first.id).issues.isEmpty())
            store.checkpoint(source.id,1,0,0);files.eraseOwned()
            assertEquals(0L,MotionFiles.inspectSession(context,store,first.id).samples)
            assertTrue(MotionFiles.inspectSession(context,store,first.id).issues.any {it.reason.contains("missing")})
            assertEquals(1L,store.streams(first.id).single().committedSamples)
        } finally {files.eraseOwned()}
    }
}
