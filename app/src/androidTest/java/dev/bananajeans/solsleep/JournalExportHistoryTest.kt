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
class JournalExportHistoryTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val name="export-history-${UUID.randomUUID()}.db"
    private var history=JournalExportHistory(context,name)
    private val at=Instant.parse("2026-10-17T08:00:00Z")
    private val hash="1".repeat(64)
    @After fun cleanup() {runCatching {history.close()};context.deleteDatabase(name)}
    @Test fun everyStageSurvivesReopenAndALaterFailureKeepsTheLastVerifiedCopy() {
        val first=history.start("眠り 🌙.json",at);history.written(first,at.plusMillis(1));history.verified(first,hash,at.plusMillis(2))
        val later=history.start(null,at.plusSeconds(1));history.failed(later,at.plusSeconds(2))
        history.close();history=JournalExportHistory(context,name)
        val summary=history.summary()
        assertEquals(first,summary.lastVerified!!.id);assertEquals(hash,summary.lastVerified!!.fingerprint)
        assertEquals("眠り 🌙.json",summary.lastVerified!!.displayName)
        assertEquals(at.plusMillis(2),summary.lastVerified!!.updatedAt)
        assertEquals(later,summary.lastAttempt!!.id);assertEquals(JournalExportStage.FAILED_WRITE,summary.lastAttempt!!.stage)
        assertTrue(summary.lastAttempt!!.sequence>summary.lastVerified!!.sequence)
        assertTrue(context.getDatabasePath(name).exists())
        assertFalse(context.createDeviceProtectedStorageContext().getDatabasePath(name).exists())
    }
    @Test fun interruptedAttemptsNeverBecomeVerifiedFromTheirPresenceOnDisk() {
        val first=history.start(null,at);history.close();history=JournalExportHistory(context,name)
        assertEquals(JournalExportStage.STARTED,history.summary().lastAttempt!!.stage);assertNull(history.summary().lastVerified)
        history.written(first,at.plusMillis(1));history.close();history=JournalExportHistory(context,name)
        assertEquals(JournalExportStage.WRITTEN,history.summary().lastAttempt!!.stage);assertNull(history.summary().lastVerified)
        history.failed(first,at.plusMillis(2))
        assertEquals(JournalExportStage.UNVERIFIED,history.summary().lastAttempt!!.stage)
    }
    @Test fun sequenceOrdersAttemptsEvenWhenTheWallClockMovesBackward() {
        val first=history.start(null,at);history.written(first,at);history.verified(first,hash,at.minusSeconds(5))
        val second=history.start(null,at.minusSeconds(100))
        assertEquals(second,history.summary().lastAttempt!!.id)
        assertEquals(at,history.summary().lastVerified!!.updatedAt)
    }
    @Test fun concurrentColdHelpersRetainBothAttemptsAndUniqueOrdering() {
        val other=JournalExportHistory(context,name);val executor=Executors.newFixedThreadPool(2);val gate=CountDownLatch(1)
        try {
            val futures=listOf(history,other).map {h->executor.submit<String> {gate.await();h.start(null,at)}}
            gate.countDown();val ids=futures.map {it.get(10,TimeUnit.SECONDS)};assertEquals(2,ids.distinct().size)
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READONLY).use {db->
                db.rawQuery("SELECT id,sequence FROM attempts ORDER BY sequence",null).use {c->
                    val actual=mutableListOf<String>();val order=mutableListOf<Long>()
                    while(c.moveToNext()) {actual.add(c.getString(0));order.add(c.getLong(1))}
                    assertEquals(ids.toSet(),actual.toSet());assertEquals(listOf(1L,2L),order)
                }
            }
        } finally {executor.shutdownNow();other.close()}
    }
    @Test fun failedVerifiedPublicationRollsBackAndCannotFabricateSuccess() {
        val id=history.start(null,at);history.written(id,at)
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READWRITE).use {db->
            db.execSQL("CREATE TRIGGER reject_verification BEFORE UPDATE ON attempts WHEN NEW.stage='VERIFIED' BEGIN SELECT RAISE(ABORT,'injected'); END")
        }
        try {history.verified(id,hash,at);fail("Must fail")} catch(_: SQLiteException) { }
        history.close();history=JournalExportHistory(context,name)
        assertEquals(JournalExportStage.WRITTEN,history.summary().lastAttempt!!.stage)
        assertNull(history.summary().lastAttempt!!.fingerprint);assertNull(history.summary().lastVerified)
    }
    @Test fun competingTerminalTransitionsCannotOverwriteEachOther() {
        val id=history.start(null,at);history.written(id,at)
        val other=JournalExportHistory(context,name);val executor=Executors.newFixedThreadPool(2);val gate=CountDownLatch(1)
        try {
            val verify=executor.submit<Boolean> {gate.await();try {history.verified(id,hash,at);true} catch(_: IllegalStateException) {false}}
            val fail=executor.submit<Boolean> {gate.await();try {other.failed(id,at);true} catch(_: IllegalStateException) {false}}
            gate.countDown();assertEquals(1,listOf(verify,fail).count {it.get(10,TimeUnit.SECONDS)})
            val summary=history.summary();val latest=summary.lastAttempt!!
            if(latest.stage==JournalExportStage.VERIFIED) {assertEquals(hash,latest.fingerprint);assertEquals(latest,summary.lastVerified)}
            else {assertEquals(JournalExportStage.UNVERIFIED,latest.stage);assertNull(latest.fingerprint);assertNull(summary.lastVerified)}
        } finally {executor.shutdownNow();other.close()}
    }
    @Test fun schemaFixtureOpensAndUnknownNewerSchemaIsRetained() {
        history.close();val path=context.getDatabasePath(name);path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path,null).use {db->
            val schema=InstrumentationRegistry.getInstrumentation().context.assets.open("export-history/1.sql").bufferedReader().use {it.readText()}
            schema.lines().filterNot {it.trimStart().startsWith("--")}.joinToString("\n").split(';').map {it.trim()}.filter {it.isNotEmpty()}.forEach {db.execSQL(it)}
            db.execSQL("INSERT INTO attempts(id,started_ms,updated_ms,display_name,stage,fingerprint) VALUES (?,?,?,?,?,?)",
                arrayOf<Any>(UUID.randomUUID().toString(),at.toEpochMilli(),at.toEpochMilli(),"fixture.json","VERIFIED",hash))
        }
        history=JournalExportHistory(context,name);assertEquals("fixture.json",history.summary().lastVerified!!.displayName);history.close()
        SQLiteDatabase.openDatabase(path.path,null,SQLiteDatabase.OPEN_READWRITE).use {it.version=2}
        history=JournalExportHistory(context,name)
        try {history.summary();fail("Unknown schema must be retained")} catch(_: SQLiteException) { }
        SQLiteDatabase.openDatabase(path.path,null,SQLiteDatabase.OPEN_READONLY).use {db->
            assertEquals(2,db.version);db.rawQuery("SELECT fingerprint FROM attempts",null).use {c->assertTrue(c.moveToFirst());assertEquals(hash,c.getString(0))}
        }
    }
    @Test fun corruptionRetainsOriginalBytesAndDoesNotResetHistory() {
        history.start(null,at);history.close();val path=context.getDatabasePath(name)
        val damaged=path.readBytes().also {"BROKEN-EXPORT!!".toByteArray().copyInto(it)}
        path.writeBytes(damaged);history=JournalExportHistory(context,name)
        try {history.summary();fail("Must report unavailable history")} catch(_: SQLiteException) { }
        assertTrue(path.exists());assertArrayEquals(damaged,path.readBytes())
    }
}
