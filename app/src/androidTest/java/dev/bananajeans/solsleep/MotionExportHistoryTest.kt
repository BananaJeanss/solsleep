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
import java.time.Instant
import java.util.UUID
import java.util.concurrent.*

@RunWith(AndroidJUnit4::class)
class MotionExportHistoryTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val name="motion-receipts-${UUID.randomUUID()}.db"
    private val captureName="receipt-capture-${UUID.randomUUID()}.db"
    private val directory=context.noBackupFilesDir.resolve("receipt-import-${UUID.randomUUID()}")
    private var history=MotionExportHistory(context,name)
    private val at=Instant.parse("2026-10-18T08:00:00Z")
    private val session=UUID.randomUUID();private val hash="1".repeat(64)
    private val destination=MotionExportDestination("2".repeat(64),"test.documents","眠り 🌙.solmotion")
    private val uris=mutableListOf<Uri>()
    private fun uri(mode: String)=Uri.parse("content://dev.bananajeans.solsleep.debug.test.journal/$mode/${UUID.randomUUID()}").also {uris+=it}
    private fun reopen() {history.close();history=MotionExportHistory(context,name)}
    private fun fixture(): Pair<CaptureRecording,MotionChunk> {
        val boot=UUID.randomUUID();val stream=UUID.randomUUID()
        val recording=CaptureRecording(CaptureSession(session,UUID.randomUUID(),UUID.randomUUID(),CaptureState.COMPLETED,at,at.plusSeconds(1),2),
            listOf(CaptureStream(stream,session,boot,at,0,CaptureSource("Receipt storage fixture","Synthetic test",1,.01f,40f,false),1,0,0,
                CaptureProvenance("a".repeat(64),"Europe/Tallinn",100_000))),
            listOf(CaptureEvent(77,session,CaptureReason.STOP,at.plusSeconds(1),1_000_000_000,boot,null)))
        val chunk=MotionChunk(session,stream,boot,0,at,0,listOf(MotionSample(100_000_000,-0.0f,1f,9.81f)))
        CaptureStore(context,captureName).use {assertTrue(it.restore(recording) {MotionFiles.forSession(context,session).publish(chunk)})}
        return recording to chunk
    }
    @After fun cleanup() {
        runCatching {history.close()};context.deleteDatabase(name);context.deleteDatabase(captureName)
        MotionFiles.forSession(context,session).eraseOwned();directory.deleteRecursively()
        uris.forEach {context.contentResolver.delete(it,null,null)}
    }
    @Test fun verifiedReceiptAndLaterFailurePersistPerSessionWithOriginalDestination() {
        val first=history.start(session,destination,at);history.written(first,at.plusMillis(1));history.verified(first,hash,at.plusMillis(2))
        val later=history.start(session,null,at.plusSeconds(1));history.failed(later,at.plusSeconds(2))
        val other=UUID.randomUUID();history.start(other,null,at.plusSeconds(3));reopen()
        val value=history.summary(session)
        assertEquals(first,value.lastVerified!!.id);assertEquals(hash,value.lastVerified!!.fingerprint)
        assertEquals(destination,value.lastVerified!!.destination);assertEquals(later,value.lastAttempt!!.id)
        assertEquals(MotionExportStage.FAILED_WRITE,value.lastAttempt!!.stage)
        assertNotEquals(value.lastAttempt,history.summary(other).lastAttempt)
        assertEquals(MotionExportSummary(UUID(0,0)),history.summary(UUID(0,0)))
        assertFalse(context.createDeviceProtectedStorageContext().getDatabasePath(name).exists())
    }
    @Test fun interruptedWritesStayUnknownAndSequenceWinsOverWallClockRollback() {
        val first=history.start(session,null,at);reopen()
        assertEquals(MotionExportStage.STARTED,history.summary(session).lastAttempt!!.stage)
        history.written(first,at.minusSeconds(10));reopen()
        assertEquals(MotionExportStage.WRITTEN,history.summary(session).lastAttempt!!.stage)
        assertNull(history.summary(session).lastVerified)
        history.verified(first,hash,at.minusSeconds(5))
        val second=history.start(session,null,at.minusSeconds(100));reopen()
        assertEquals(second,history.summary(session).lastAttempt!!.id)
        assertEquals(at,history.summary(session).lastVerified!!.updatedAt)
    }
    @Test fun concurrentColdStartsAndCompetingTerminalTransitionsRetainOneWinner() {
        val other=MotionExportHistory(context,name);val executor=Executors.newFixedThreadPool(2);val start=CountDownLatch(1)
        try {
            val requests=listOf(history,other).map {h->executor.submit<UUID> {start.await();h.start(session,null,at)}}
            start.countDown();val ids=requests.map {it.get(10,TimeUnit.SECONDS)};assertEquals(2,ids.distinct().size)
            val id=history.summary(session).lastAttempt!!.id;history.written(id,at)
            val gate=CountDownLatch(1)
            val verify=executor.submit<Boolean> {gate.await();try {history.verified(id,hash,at);true} catch(_: IllegalStateException) {false}}
            val reject=executor.submit<Boolean> {gate.await();try {other.failed(id,at);true} catch(_: IllegalStateException) {false}}
            gate.countDown();assertEquals(1,listOf(verify,reject).count {it.get(10,TimeUnit.SECONDS)})
            reopen();val value=history.summary(session)
            if(value.lastAttempt!!.stage==MotionExportStage.VERIFIED) assertEquals(value.lastAttempt,value.lastVerified)
            else {assertEquals(MotionExportStage.UNVERIFIED,value.lastAttempt!!.stage);assertNull(value.lastVerified)}
        } finally {executor.shutdownNow();other.close()}
    }
    @Test fun failedVerifiedTransactionRollsBackToWrittenWithoutInventingSuccess() {
        val id=history.start(session,destination,at);history.written(id,at)
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("CREATE TRIGGER fail_verify BEFORE UPDATE ON attempts WHEN NEW.stage='VERIFIED' BEGIN SELECT RAISE(ABORT,'injected'); END")
        }
        try {history.verified(id,hash,at);fail("Fault must fail")} catch(_: SQLiteException) { }
        reopen();assertEquals(MotionExportStage.WRITTEN,history.summary(session).lastAttempt!!.stage)
        assertNull(history.summary(session).lastVerified);assertNull(history.summary(session).lastAttempt!!.fingerprint)
    }
    @Test fun independentSchemaFixtureAndUnknownVersionAreRetained() {
        history.close();val path=context.getDatabasePath(name);path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path,null).use {db->
            val sql=InstrumentationRegistry.getInstrumentation().context.assets.open("motion-exports/1.sql").bufferedReader().use {it.readText()}
            sql.lines().filterNot {it.trimStart().startsWith("--")}.joinToString("\n").split(';').map {it.trim()}.filter {it.isNotEmpty()}.forEach(db::execSQL)
            db.execSQL("INSERT INTO attempts(id,session_id,started_ms,updated_ms,stage,fingerprint) VALUES(?,?,?,?,?,?)",
                arrayOf<Any>(UUID.randomUUID().toString(),session.toString(),at.toEpochMilli(),at.toEpochMilli(),"VERIFIED",hash))
        }
        history=MotionExportHistory(context,name);assertEquals(hash,history.summary(session).lastVerified!!.fingerprint);history.close()
        SQLiteDatabase.openDatabase(path.path,null,SQLiteDatabase.OPEN_READWRITE).use {it.version=2}
        history=MotionExportHistory(context,name)
        try {history.summary(session);fail("Must retain future schema")} catch(_: SQLiteException) { }
        SQLiteDatabase.openDatabase(path.path,null,SQLiteDatabase.OPEN_READONLY).use {db->
            assertEquals(2,db.version);db.rawQuery("SELECT fingerprint FROM attempts",null).use {assertTrue(it.moveToFirst());assertEquals(hash,it.getString(0))}
        }
    }
    @Test fun actualProviderOutcomesRecordOnlyClosedMatchingReadbackAndNeverModifySource() {
        val (recording,chunk)=fixture();val docs=MotionDocuments(context,captureName,directory)
        val first=docs.exportAudited(session,uri("normal"),name) as AuditedMotionResult.Verified
        assertTrue(first.auditRecorded);assertEquals(recording,first.preview.recording)
        val successful=history.summary(session).lastVerified!!
        // The independent provider refuses metadata queries; unknown name cannot block recovery.
        assertNull(successful.destination!!.displayName);assertEquals("dev.bananajeans.solsleep.debug.test.journal",successful.destination!!.authority)
        for(mode in listOf("deny_write","deny_read","truncated","replaced")) {
            val result=docs.exportAudited(session,uri(mode),name) as AuditedMotionResult.Failed
            assertTrue(result.auditRecorded)
            val current=history.summary(session)
            assertEquals(successful,current.lastVerified)
            assertEquals(if(mode=="deny_write") MotionExportStage.FAILED_WRITE else MotionExportStage.UNVERIFIED,current.lastAttempt!!.stage)
            CaptureStore(context,captureName).use {assertEquals(recording,it.recording(session))}
            assertEquals(listOf(chunk),MotionFiles.forSession(context,session).recover().chunks)
        }
    }
    @Test fun corruptReceiptBytesRemainRetainedAndVerifiedRecoveryStillWorks() {
        val (recording,_)=fixture();history.start(session,null,at);history.close()
        val path=context.getDatabasePath(name);val damaged=path.readBytes().also {"BROKEN-RECEIPT!!".toByteArray().copyInto(it)}
        path.writeBytes(damaged);history=MotionExportHistory(context,name)
        try {history.summary(session);fail("Unavailable") } catch(_: SQLiteException) { }
        assertArrayEquals(damaged,path.readBytes())
        val result=MotionDocuments(context,captureName,directory).exportAudited(session,uri("normal"),name) as AuditedMotionResult.Verified
        assertFalse(result.auditRecorded);assertEquals(recording,result.preview.recording);assertArrayEquals(damaged,path.readBytes())
        CaptureStore(context,captureName).use {assertEquals(recording,it.recording(session))}
    }
    @Test fun deletionIsScopedAndNeverRemovesAnotherSessionsReceipt() {
        val first=history.start(session,null,at);history.written(first,at);history.verified(first,hash,at)
        val other=UUID.randomUUID();val retained=history.start(other,destination,at)
        history.deleteForSession(session);history.deleteForSession(session);reopen()
        assertEquals(MotionExportSummary(session),history.summary(session));assertEquals(retained,history.summary(other).lastAttempt!!.id)
    }
}
