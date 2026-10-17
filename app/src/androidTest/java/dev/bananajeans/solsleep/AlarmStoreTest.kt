// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.bananajeans.solsleep.core.*
import dev.bananajeans.solsleep.data.AlarmStore
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.*
import java.util.UUID
import java.util.concurrent.*

@RunWith(AndroidJUnit4::class)
class AlarmStoreTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val name="store-test-"+UUID.randomUUID()+".db"
    private var store=AlarmStore(context,name)
    private val rule=AlarmRule("one",LocalTime.of(7,0),Repeat.Once(LocalDate.parse("2026-10-17")),
        label="Morning 🌙",zoneId="Europe/Tallinn",vibrate=false,rampSeconds=0)
    private val pending=PendingAlarm("first-token",Instant.parse("2026-10-17T04:00:00Z"),LocalDate.parse("2026-10-17"))
    @After fun cleanup() {runCatching {store.close()};context.createDeviceProtectedStorageContext().deleteDatabase(name)}

    @Test fun confirmedRecordAndActionSurviveCloseAndReopen() {
        store.put(rule,pending)
        store.close();store=AlarmStore(context,name)
        assertEquals(AlarmEntry(rule,pending),store.entries().single())
        val claimed=store.claim(pending.token,pending.at)!!
        store.close();store=AlarmStore(context,name)
        assertEquals(claimed,store.entries().single())
        assertTrue(store.finish(pending.token))
        store.close();store=AlarmStore(context,name)
        assertFalse(store.entries().single().rule.enabled)
        assertEquals(DeliveryState.DONE,store.entries().single().pending!!.state)
    }
    @Test fun disabledAlarmsAreRetained() {
        store.put(rule.copy(enabled=false),null)
        store.close();store=AlarmStore(context,name)
        assertEquals(rule.copy(enabled=false),store.entries().single().rule)
    }
    @Test fun failedForeignKeyWriteRollsBackWithoutLosingOtherRows() {
        store.put(rule,pending)
        val before=store.entries()
        try {store.replacePending("absent",pending.copy(token="bad"));fail("Expected invalid reference")}
        catch(_: android.database.sqlite.SQLiteConstraintException) { }
        assertEquals(before,store.entries())
    }
    @Test fun staleGenerationCannotClaimAnEditedAlarm() {
        store.put(rule,pending)
        store.put(rule.copy(revision=2),pending.copy(token="replacement"))
        assertNull(store.claim(pending.token,pending.at))
        assertNotNull(store.claim("replacement",pending.at))
    }
    @Test fun twoDatabaseConnectionsCanClaimOnlyOnce() {
        store.put(rule,pending)
        val other=AlarmStore(context,name)
        val executor=Executors.newFixedThreadPool(2)
        val gate=CountDownLatch(1)
        val results=listOf(store,other).map {s->executor.submit<Boolean> {gate.await();s.claim(pending.token,pending.at)!=null}}
        gate.countDown()
        assertEquals(1,results.count {it.get(10,TimeUnit.SECONDS)})
        executor.shutdown();other.close()
    }
    @Test fun missingDowngradePathRetainsOriginalDatabase() {
        store.put(rule,pending);store.close()
        val path=context.createDeviceProtectedStorageContext().getDatabasePath(name).path
        SQLiteDatabase.openDatabase(path,null,SQLiteDatabase.OPEN_READWRITE).use {it.version=2}
        store=AlarmStore(context,name)
        try {store.entries();fail("Must refuse an unknown newer schema")} catch(_: android.database.sqlite.SQLiteException) { }
        assertTrue(java.io.File(path).exists())
        SQLiteDatabase.openDatabase(path,null,SQLiteDatabase.OPEN_READONLY).use {db->
            db.rawQuery("SELECT COUNT(*) FROM alarms",null).use {c->c.moveToFirst();assertEquals(1,c.getInt(0))}
            assertEquals(2,db.version)
        }
    }
    @Test fun deleteAlsoRemovesTheScheduledOccurrence() {
        store.put(rule,pending);store.delete(rule.id)
        assertTrue(store.entries().isEmpty())
        val path=context.createDeviceProtectedStorageContext().getDatabasePath(name).path
        SQLiteDatabase.openDatabase(path,null,SQLiteDatabase.OPEN_READONLY).use {db->
            db.rawQuery("SELECT COUNT(*) FROM pending",null).use {c->c.moveToFirst();assertEquals(0,c.getInt(0))}
        }
    }
}
