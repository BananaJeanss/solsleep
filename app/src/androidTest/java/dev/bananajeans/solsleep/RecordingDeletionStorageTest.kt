// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.bananajeans.solsleep.core.*
import dev.bananajeans.solsleep.data.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.IOException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class RecordingDeletionStorageTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val name="deletions-test-${UUID.randomUUID()}.db"
    private val wall=Instant.parse("2026-10-20T00:00:00Z")
    private val uris=mutableListOf<Uri>()
    private fun marker()=RecordingDeletion(UUID.randomUUID(),UUID.randomUUID())
    private fun snapshot(vararg markers: RecordingDeletion)=RecordingDeletionSnapshot(markers.toList())
    private fun recording(marker: RecordingDeletion): CaptureRecording {
        val owner=UUID.randomUUID();val boot=UUID.randomUUID()
        return CaptureRecording(CaptureSession(marker.sessionId,marker.requestId,owner,CaptureState.COMPLETED,wall,wall,1),
            emptyList(),listOf(CaptureEvent(1,marker.sessionId,CaptureReason.STOP,wall,1,boot,null)))
    }
    private fun uri(mode: String)=Uri.parse("content://dev.bananajeans.solsleep.debug.test.journal/$mode/${UUID.randomUUID()}").also {uris+=it}
    @After fun cleanup() {context.deleteDatabase(name);uris.forEach {context.contentResolver.delete(it,null,null)}}

    @Test fun previewDoesNotWriteAndCleanInstallImportBlocksBothOldIdentitiesAcrossReopen() {
        val marker=marker();val original=recording(marker)
        CaptureStore(context,name).use {
            assertEquals(RecordingDeletionMerge(1,0,0,0),it.mergeDeletionHistory(snapshot(marker),previewOnly=true))
            assertTrue(it.deletionSnapshot().markers.isEmpty());assertFalse(it.restoreConflict(original))
            assertEquals(RecordingDeletionMerge(1,0,0,0),it.mergeDeletionHistory(snapshot(marker)))
        }
        CaptureStore(context,name).use {
            assertFalse(it.restore(original) {fail("Deleted recording published")})
            assertFalse(it.restore(recording(marker.copy(sessionId=UUID.randomUUID()))) {fail("Deleted request reused")})
            assertFalse(it.restore(recording(marker.copy(requestId=UUID.randomUUID()))) {fail("Deleted session reused")})
            assertEquals(listOf(marker),it.deletionSnapshot().markers)
            assertNull(it.active());assertTrue(it.sessions().isEmpty())
        }
    }
    @Test fun commitRechecksAPreviewAfterAnotherHelperRestoresAMatchingRecording() {
        val marker=marker();val value=recording(marker)
        CaptureStore(context,name).use {
            assertEquals(RecordingDeletionMerge(1,0,0,0),it.mergeDeletionHistory(snapshot(marker),previewOnly=true))
        }
        CaptureStore(context,name).use {assertTrue(it.restore(value) {})}
        CaptureStore(context,name).use {
            assertEquals(RecordingDeletionMerge(0,0,1,0),it.mergeDeletionHistory(snapshot(marker)))
            assertEquals(value,it.recording(marker.sessionId));assertTrue(it.deletionSnapshot().markers.isEmpty())
        }
    }
    @Test fun markersNeverDeletePresentRecordingsAndBothLiveIdentitiesWin() {
        val marker=marker();val value=recording(marker)
        CaptureStore(context,name).use {
            assertTrue(it.restore(value) {})
            assertEquals(RecordingDeletionMerge(0,0,1,0),it.mergeDeletionHistory(snapshot(marker)))
            assertEquals(RecordingDeletionMerge(0,0,1,0),it.mergeDeletionHistory(snapshot(marker.copy(sessionId=UUID.randomUUID()))))
            assertEquals(RecordingDeletionMerge(0,0,1,0),it.mergeDeletionHistory(snapshot(marker.copy(requestId=UUID.randomUUID()))))
            assertEquals(value,it.recording(marker.sessionId));assertTrue(it.deletionSnapshot().markers.isEmpty())
        }
    }
    @Test fun exactRetryIsIdempotentAndCrossedLocalIdentityPairsAreSkipped() {
        val a=marker();val b=marker()
        CaptureStore(context,name).use {
            assertEquals(RecordingDeletionMerge(2,0,0,0),it.mergeDeletionHistory(snapshot(a,b)))
            assertEquals(RecordingDeletionMerge(0,2,0,0),it.mergeDeletionHistory(snapshot(a,b)))
            assertEquals(RecordingDeletionMerge(0,0,0,1),it.mergeDeletionHistory(snapshot(a.copy(requestId=b.requestId))))
            assertEquals(RecordingDeletionMerge(0,0,0,1),it.mergeDeletionHistory(snapshot(a.copy(requestId=UUID.randomUUID()))))
            assertEquals(RecordingDeletionMerge(0,0,0,1),it.mergeDeletionHistory(snapshot(a.copy(sessionId=UUID.randomUUID()))))
            assertEquals(setOf(a,b),it.deletionSnapshot().markers.toSet())
        }
    }
    @Test fun transactionFailureRollsBackEarlierMarkersAndRetryAfterReopenAddsExactlyOnce() {
        val a=RecordingDeletion(UUID(0,1),UUID(1,1));val b=RecordingDeletion(UUID(0,2),UUID(1,2))
        CaptureStore(context,name).use {assertTrue(it.deletionSnapshot().markers.isEmpty())}
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("CREATE TRIGGER fail_marker BEFORE INSERT ON deleted_sessions WHEN NEW.id='${b.sessionId}' BEGIN SELECT RAISE(ABORT,'injected'); END")
        }
        try {CaptureStore(context,name).use {it.mergeDeletionHistory(snapshot(a,b))};fail("SQL fault")} catch(_: SQLiteException) {}
        CaptureStore(context,name).use {assertTrue(it.deletionSnapshot().markers.isEmpty());assertTrue(it.sessions().isEmpty())}
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READWRITE).use {it.execSQL("DROP TRIGGER fail_marker")}
        CaptureStore(context,name).use {assertEquals(RecordingDeletionMerge(2,0,0,0),it.mergeDeletionHistory(snapshot(a,b)))}
        CaptureStore(context,name).use {assertEquals(RecordingDeletionMerge(0,2,0,0),it.mergeDeletionHistory(snapshot(a,b)))}
    }
    @Test fun concurrentColdHelpersSerializeWithoutLostOrDuplicateIdentities() {
        val marker=marker();val start=CountDownLatch(1);val pool=Executors.newFixedThreadPool(2)
        try {
            val tasks=List(2) {pool.submit(Callable {
                check(start.await(10,TimeUnit.SECONDS))
                CaptureStore(context,name).use {it.mergeDeletionHistory(snapshot(marker))}
            })}
            start.countDown();val results=tasks.map {it.get(20,TimeUnit.SECONDS)}
            assertEquals(1,results.sumOf {it.added});assertEquals(1,results.sumOf {it.alreadyKnown})
            CaptureStore(context,name).use {assertEquals(listOf(marker),it.deletionSnapshot().markers)}
        } finally {pool.shutdownNow()}
    }
    @Test fun exportIncludesConfirmedPendingDeletionWithoutErasingRecordOrInventingTime() {
        val marker=marker();val value=recording(marker)
        CaptureStore(context,name).use {
            assertTrue(it.restore(value) {})
            it.transition(marker.sessionId,1,CaptureState.DELETING,CaptureReason.DELETE,value.session.ownerId,wall,2,UUID.randomUUID())
            assertEquals(listOf(marker),it.deletionSnapshot().markers)
            assertEquals(CaptureState.DELETING,it.get(marker.sessionId)!!.state)
            assertEquals(RecordingDeletionMerge(0,0,1,0),it.mergeDeletionHistory(snapshot(marker)))
            it.finishDelete(marker.sessionId)
            assertEquals(listOf(marker),it.deletionSnapshot().markers);assertNull(it.get(marker.sessionId))
        }
    }
    @Test fun activeRecordingBlocksPreviewAndCommitButNeverBlocksHistoryExport() {
        val marker=marker()
        CaptureStore(context,name).use {
            val active=it.start(UUID.randomUUID(),UUID.randomUUID(),wall,0,UUID.randomUUID())
            for(preview in listOf(true,false)) {
                try {it.mergeDeletionHistory(snapshot(marker),preview);fail("Active import")} catch(_: IllegalStateException) {}
            }
            assertEquals(active,it.get(active.id));assertTrue(it.deletionSnapshot().markers.isEmpty())
        }
        val output=uri("normal")
        assertTrue(RecordingDeletionDocuments(context,name).export(output).snapshot.markers.isEmpty())
    }
    @Test fun actualProviderReadbackFailuresRetainLocalHistoryAndSuccessfulCopyImportsOnAnotherStore() {
        val marker=marker();CaptureStore(context,name).use {it.mergeDeletionHistory(snapshot(marker))}
        val docs=RecordingDeletionDocuments(context,name);val normal=uri("normal")
        assertEquals(listOf(marker),docs.export(normal).snapshot.markers)
        assertEquals(listOf(marker),docs.inspect(normal).snapshot.markers)
        listOf("deny_read","truncated","replaced").forEach {mode->
            try {docs.export(uri(mode));fail("Readback $mode")} catch(_: RecordingDeletionVerificationException) {}
            CaptureStore(context,name).use {assertEquals(listOf(marker),it.deletionSnapshot().markers)}
        }
        try {docs.export(uri("deny_write"));fail("Write denial")} catch(e: IOException) {assertFalse(e is RecordingDeletionVerificationException)}
        val other="deletions-import-${UUID.randomUUID()}.db"
        try {
            val preview=docs.inspect(normal)
            CaptureStore(context,other).use {assertEquals(RecordingDeletionMerge(1,0,0,0),it.mergeDeletionHistory(preview.snapshot))}
            CaptureStore(context,other).use {assertTrue(it.restoreConflict(recording(marker)))}
        } finally {context.deleteDatabase(other)}
    }
    @Test fun futureSchemaAndCorruptHistoryAreRetainedAndNeverResetForExportOrImport() {
        CaptureStore(context,name).use {it.deletionSnapshot()}
        val path=context.getDatabasePath(name)
        SQLiteDatabase.openDatabase(path.path,null,SQLiteDatabase.OPEN_READWRITE).use {it.version=99}
        val future=path.readBytes()
        try {CaptureStore(context,name).use {it.mergeDeletionHistory(snapshot(marker()))};fail("Future schema")} catch(_: SQLiteException) {}
        assertArrayEquals(future,path.readBytes())
        context.deleteDatabase(name);path.parentFile!!.mkdirs();path.writeBytes(ByteArray(4096) {0x5a})
        val corrupt=path.readBytes()
        try {CaptureStore(context,name).use {it.deletionSnapshot()};fail("Corrupt schema")} catch(_: SQLiteException) {}
        assertArrayEquals(corrupt,path.readBytes())
    }
}
