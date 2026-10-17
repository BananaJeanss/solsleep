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
class JournalStoreTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val name="journal-test-${UUID.randomUUID()}.db"
    private val credential=context.applicationContext
    private var store=JournalStore(context,name)
    private val r=SleepRecord(createdAt=Instant.parse("2026-10-17T08:00:00Z"),modifiedAt=Instant.parse("2026-10-17T08:00:00Z"),
        start=Instant.parse("2026-10-16T20:30:00Z"),end=Instant.parse("2026-10-17T05:00:00Z"),zone="Europe/Tallinn",
        note="Original 🌙\nline",tags=setOf("tea"),rating=null)
    @After fun cleanup() {runCatching {store.close()};credential.deleteDatabase(name)}
    @Test fun everyCorrectionAndNullRatingSurviveReopenInCredentialStorage() {
        store.save(r,0)
        val revised=r.copy(revision=2,note="Corrected",rating=1,start=r.start.plusSeconds(60),modifiedAt=r.modifiedAt.plusSeconds(1))
        store.save(revised,1);store.close();store=JournalStore(context,name)
        assertEquals(listOf(revised),store.current());assertEquals(listOf(r,revised),store.history(r.id))
        assertTrue(credential.getDatabasePath(name).exists())
        assertFalse(context.createDeviceProtectedStorageContext().getDatabasePath(name).exists())
    }
    @Test fun staleDraftNeverOverwritesAConfirmedCorrection() {
        store.save(r,0);val revised=r.copy(revision=2,note="Winner")
        store.save(revised,1)
        try {store.save(revised.copy(note="Stale"),1);fail("Must reject stale revision")} catch(_: RevisionConflict) { }
        assertEquals(listOf(r,revised),store.snapshot())
    }
    @Test fun twoConnectionsCannotBothCommitTheSameRevision() {
        store.save(r,0);val other=JournalStore(context,name)
        val executor=Executors.newFixedThreadPool(2);val gate=CountDownLatch(1)
        try {
            val futures=listOf(store,other).mapIndexed {i,s->executor.submit<Boolean> {
                gate.await();try {s.save(r.copy(revision=2,note="Writer $i"),1);true} catch(_: RevisionConflict) {false}
            }}
            gate.countDown();assertEquals(1,futures.count {it.get(10,TimeUnit.SECONDS)})
            assertEquals(2,store.history(r.id).size)
        } finally {executor.shutdownNow();other.close()}
    }
    @Test fun failureAfterPointerUpdateRollsBackBothPointerAndRevision() {
        store.save(r,0)
        SQLiteDatabase.openDatabase(credential.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READWRITE).use {db->
            db.execSQL("CREATE TRIGGER reject_revision BEFORE INSERT ON revisions WHEN NEW.revision=2 BEGIN SELECT RAISE(ABORT,'injected'); END")
        }
        try {store.save(r.copy(revision=2,note="Must roll back"),1);fail("Expected injected failure")}
        catch(_: SQLiteException) { }
        store.close();store=JournalStore(context,name)
        assertEquals(listOf(r),store.current());assertEquals(listOf(r),store.history(r.id))
    }
    @Test fun cleanInstallationRestoreHasSemanticParityAcrossAllRevisions() {
        store.save(r,0);val revised=r.copy(revision=2,note="New",rating=5);store.save(revised,1)
        val bytes=java.io.ByteArrayOutputStream().also {JournalArchive.write(store.snapshot(),it)}.toByteArray()
        store.close();credential.deleteDatabase(name);store=JournalStore(context,name)
        val preview=JournalArchive.inspect(bytes.inputStream())
        assertEquals(1,store.restore(preview));assertEquals(0,store.restore(preview))
        assertEquals(listOf(r,revised),store.snapshot());assertEquals(listOf(revised),store.current())
    }
    @Test fun restoreBatchRollsBackIfALaterRecordCannotBeInserted() {
        store.save(r,0)
        SQLiteDatabase.openDatabase(credential.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READWRITE).use {db->
            db.execSQL("CREATE TRIGGER reject_record BEFORE INSERT ON revisions WHEN NEW.payload LIKE '%reject-me%' BEGIN SELECT RAISE(ABORT,'injected'); END")
        }
        val first=r.copy(id=UUID.randomUUID().toString(),note="first")
        val second=r.copy(id=UUID.randomUUID().toString(),note="reject-me")
        try {store.restore(JournalPreview(r.createdAt,listOf(first,second),"test"));fail("Expected injected failure")}
        catch(_: SQLiteException) { }
        store.close();store=JournalStore(context,name)
        assertEquals(listOf(r),store.snapshot())
    }
    @Test fun deletedNightCannotReturnFromAnOldArchiveOrStaleDraft() {
        store.save(r,0);val preview=JournalPreview(r.createdAt,listOf(r),"test")
        store.delete(r.id,1);store.close();store=JournalStore(context,name)
        assertEquals(0,store.restore(preview));assertTrue(store.current().isEmpty());assertTrue(store.snapshot().isEmpty())
        try {store.save(r,0);fail("Deleted identity must stay deleted")} catch(_: RevisionConflict) { }
    }
    @Test fun localCorrectionWinsOverAnOlderArchiveAndDeleteChecksRevision() {
        store.save(r,0);store.save(r.copy(revision=2,note="Local"),1)
        assertEquals(0,store.restore(JournalPreview(r.createdAt,listOf(r),"test")))
        try {store.delete(r.id,1);fail("Stale delete must fail")} catch(_: RevisionConflict) { }
        assertEquals("Local",store.current().single().note)
    }
    @Test fun checkedInSchemaIsCompatibleAndUnknownNewerSchemaIsRetained() {
        store.close();val path=credential.getDatabasePath(name);path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path,null).use {db->
            val schema=InstrumentationRegistry.getInstrumentation().context.assets.open("journal/1.sql").bufferedReader().use {it.readText()}
            schema.lines().filterNot {it.trimStart().startsWith("--")}.joinToString("\n").split(';').map {it.trim()}
                .filter {it.isNotEmpty()}.forEach {db.execSQL(it)}
            db.execSQL("INSERT INTO sessions VALUES (?,1)",arrayOf(r.id))
            db.execSQL("INSERT INTO revisions VALUES (?,1,?)",arrayOf(r.id,SleepRecordCodec.encode(r)))
        }
        store=JournalStore(context,name);assertEquals(listOf(r),store.snapshot());store.close()
        SQLiteDatabase.openDatabase(path.path,null,SQLiteDatabase.OPEN_READWRITE).use {it.version=2}
        store=JournalStore(context,name)
        try {store.current();fail("Unknown schema must fail")} catch(_: SQLiteException) { }
        assertTrue(path.exists())
        SQLiteDatabase.openDatabase(path.path,null,SQLiteDatabase.OPEN_READONLY).use {db->
            assertEquals(2,db.version);db.rawQuery("SELECT payload FROM revisions",null).use {c->
                assertTrue(c.moveToFirst());assertEquals(r,SleepRecordCodec.decode(c.getString(0)))
            }
        }
    }
    @Test fun journalFailureDoesNotPreventIndependentAlarmStoreUse() {
        store.close()
        val path=credential.getDatabasePath(name);path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path,null).use {it.version=99}
        store=JournalStore(context,name)
        try {store.current();fail("Must reject schema")} catch(_: SQLiteException) { }
        val alarmName="alarm-isolation-${UUID.randomUUID()}.db"
        try {AlarmStore(context,alarmName).use {alarms->
            val rule=AlarmRule("independent",java.time.LocalTime.of(7,0),Repeat.Weekly(setOf(java.time.DayOfWeek.MONDAY)))
            alarms.put(rule,null);assertEquals(rule,alarms.entries().single().rule)
        }} finally {context.createDeviceProtectedStorageContext().deleteDatabase(alarmName)}
    }
}
