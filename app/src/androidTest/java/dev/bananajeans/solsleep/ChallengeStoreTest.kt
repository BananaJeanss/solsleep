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
class ChallengeStoreTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private val name="challenge-test-${UUID.randomUUID()}.db"
    private var store=AlarmStore(context,name)
    private val now=Instant.ofEpochMilli(System.currentTimeMillis())
    private val policy=ChallengePolicy(setOf(ChallengeKind.COPY_CODE),rounds=1)
    private val rule=AlarmRule("task",LocalTime.of(7,0),Repeat.Once(LocalDate.now()),challenge=policy)
    private val pending=PendingAlarm(UUID.randomUUID().toString(),now,LocalDate.now(),DeliveryState.RINGING,startedAt=now)
    private fun challenge()=WakeChallenge(UUID.randomUUID().toString(),42,policy.fingerprint(),ChallengeAction.DISMISS,now)
    @After fun cleanup() {store.close();context.createDeviceProtectedStorageContext().deleteDatabase(name)}
    @Test fun taskStateSurvivesReopenAndStaleCasCannotOverwriteNewProgress() {
        store.put(rule,pending);val first=challenge();assertTrue(store.updateChallenge(pending.token,null,first))
        val next=WakeChallenges.respond(policy,first,ChallengeResponse.Text("wrong"))
        assertTrue(store.updateChallenge(pending.token,first,next));store.close();store=AlarmStore(context,name)
        assertEquals(next,store.entries().single().pending!!.challenge)
        assertFalse(store.updateChallenge(pending.token,first,first));assertFalse(store.updateChallenge("old",next,first))
        assertEquals(next,store.entries().single().pending!!.challenge)
    }
    @Test fun storageRefusesUnsolvedDismissAndSnoozeButAcceptsHostValidatedProof() {
        store.put(rule,pending);assertFalse(store.finish(pending.token))
        try {store.replacePending(rule.id,pending.copy(token="replacement",state=DeliveryState.SNOOZED));fail("Incomplete task must not snooze")}
        catch(_: IllegalArgumentException) {}
        assertEquals(pending,store.entries().single().pending)
        val s=challenge();val complete=WakeChallenges.respond(policy,s,ChallengeResponse.Text(WakeChallenges.question(policy,s).expected))
        assertTrue(store.updateChallenge(pending.token,null,complete));assertTrue(store.finish(pending.token))
        store.close();store=AlarmStore(context,name);assertEquals(DeliveryState.DONE,store.entries().single().pending!!.state)
    }
    @Test fun databaseCasAcrossConnectionsAcceptsOnlyOneConcurrentAnswer() {
        store.put(rule,pending);val s=challenge();store.updateChallenge(pending.token,null,s)
        val other=AlarmStore(context,name);val pool=Executors.newFixedThreadPool(2);val gate=CountDownLatch(1)
        try {
            val futures=listOf(store,other).map {db->pool.submit<Boolean> {gate.await();db.updateChallenge(pending.token,s,s.copy(revision=2,failures=1))}}
            gate.countDown();assertEquals(1,futures.count {it.get(10,TimeUnit.SECONDS)})
            assertEquals(1,store.entries().single().pending!!.challenge!!.failures)
        } finally {pool.shutdownNow();other.close()}
    }
    @Test fun releasedV1MigrationRetainsARingingDeadlineAndEveryExistingField() {
        store.close();val path=context.createDeviceProtectedStorageContext().getDatabasePath(name);path.parentFile!!.mkdirs()
        val legacy=rule.copy(challenge=null)
        SQLiteDatabase.openOrCreateDatabase(path,null).use {db->
            instrumentation.context.assets.open("alarm/1.sql").bufferedReader().use {it.readText()}.lines()
                .filterNot {it.trimStart().startsWith("--")}.joinToString("\n").split(';').map {it.trim()}.filter {it.isNotEmpty()}.forEach {db.execSQL(it)}
            db.execSQL("INSERT INTO alarms VALUES (?,?)",arrayOf(legacy.id,AlarmRuleCodec.encode(legacy)))
            db.execSQL("INSERT INTO pending VALUES (?,?,?,?,?,?,?,?)",arrayOf<Any>(legacy.id,pending.token,now.toEpochMilli(),pending.date.toString(),"RINGING",2,1,now.minusSeconds(12).toEpochMilli()))
        }
        store=AlarmStore(context,name);val migrated=store.entries().single()
        assertEquals(legacy,migrated.rule);assertEquals(pending.copy(snoozes=2,armed=true,startedAt=now.minusSeconds(12)),migrated.pending)
        store.close();SQLiteDatabase.openDatabase(path.path,null,SQLiteDatabase.OPEN_READONLY).use {assertEquals(2,it.version)}
        store=AlarmStore(context,name);assertEquals(migrated,store.entries().single())
    }
    @Test fun releasedV2FixtureCanOpenWithoutResetAndFrozenVoiceReferencesPersist() {
        store.close();val path=context.createDeviceProtectedStorageContext().getDatabasePath(name);path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path,null).use {db->instrumentation.context.assets.open("alarm/2.sql").bufferedReader().use {it.readText()}.lines()
            .filterNot {it.trimStart().startsWith("--")}.joinToString("\n").split(';').map {it.trim()}.filter {it.isNotEmpty()}.forEach {db.execSQL(it)}}
        val samples=context.resources.openRawResource(R.raw.voice_captain).use(VoiceWav::read)
        val p=ChallengePolicy(setOf(ChallengeKind.VOICEOVER,ChallengeKind.CUSTOM),pack=ChallengePackCodec.example(),voiceClip=VoiceClip.from("Captain",samples))
        store=AlarmStore(context,name);store.put(rule.copy(challenge=p),pending);store.close();store=AlarmStore(context,name)
        assertEquals(p,store.entries().single().rule.challenge)
    }
    @Test fun originalIncludedVoiceClipsAreBoundedPlayablePcmAndMatchThemselves() {
        for(id in listOf(R.raw.voice_morning,R.raw.voice_captain,R.raw.voice_moon)) {
            val samples=context.resources.openRawResource(id).use(VoiceWav::read)
            assertTrue(samples.size in 4000..48000);assertEquals(100,VoiceMatch.score(samples,samples))
            assertEquals(0,VoiceMatch.score(samples,ShortArray(samples.size)));assertTrue(VoiceWav.encode(samples).size<100000)
        }
    }
    @Test fun challengesDoNotAddNetworkCameraCallOrDeviceAdministrationCapabilities() {
        @Suppress("DEPRECATION")
        val requested=context.packageManager.getPackageInfo(context.packageName,android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toSet()
        assertTrue(android.Manifest.permission.RECORD_AUDIO in requested);assertTrue(android.Manifest.permission.NFC in requested)
        for(p in listOf(android.Manifest.permission.INTERNET,android.Manifest.permission.CALL_PHONE,android.Manifest.permission.CAMERA,android.Manifest.permission.SYSTEM_ALERT_WINDOW)) assertFalse(p in requested)
    }
}
