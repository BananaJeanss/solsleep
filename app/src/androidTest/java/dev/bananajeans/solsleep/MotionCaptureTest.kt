// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.io.PlatformTestStorageRegistry
import dev.bananajeans.solsleep.alarm.AlarmGraph
import dev.bananajeans.solsleep.capture.*
import dev.bananajeans.solsleep.core.*
import dev.bananajeans.solsleep.data.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.*
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MotionCaptureTest {
    @get:Rule val compose=createAndroidComposeRule<MotionCaptureActivity>()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val alarmId="capture-hard-deadline"
    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use {fd->java.io.FileInputStream(fd.fileDescriptor).use {it.readBytes()}}
    }
    private fun sessions()=CaptureStore(context).use {it.sessions(100)}
    private fun session()=sessions().single()
    private fun summary(id: UUID)=MotionFiles.forSession(context,id).summary()
    private fun await(condition: ()->Boolean) {
        val end=SystemClock.elapsedRealtime()+25000
        while(!condition() && SystemClock.elapsedRealtime()<end) SystemClock.sleep(100)
        assertTrue("Capture did not reach the expected durable state",condition())
    }
    @Before fun setup() {
        context.stopService(Intent(context,MotionCaptureService::class.java));await {CaptureRuntime.liveOwner==null}
        clearRecords()
        if(Build.VERSION.SDK_INT>=33) shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        shell("appops set ${context.packageName} POST_NOTIFICATION allow")
        shell("appops set ${context.packageName} SCHEDULE_EXACT_ALARM allow")
        shell("input keyevent KEYCODE_WAKEUP")
        CaptureRuntime.clearError()
        compose.activityRule.scenario.recreate()
    }
    @After fun cleanup() {
        shell("input keyevent KEYCODE_WAKEUP");shell("settings put system font_scale 1.0")
        shell("appops set ${context.packageName} POST_NOTIFICATION allow")
        context.stopService(Intent(context,MotionCaptureService::class.java));await {CaptureRuntime.liveOwner==null}
        clearRecords();AlarmGraph.engine(context).delete(alarmId)
    }
    private fun clearRecords() {
        // Disposable test installation only. Tests never target a personal installation.
        CaptureStore(context).use {store->store.sessions(100).forEach {MotionFiles.forSession(context,it.id).eraseOwned()}}
        context.deleteDatabase("motion-capture.db")
    }
    private fun click(text: String) {
        compose.onNodeWithTag("capture_home").performScrollToNode(hasText(text))
        compose.waitUntil(15000) {runCatching {compose.onNodeWithText(text).assertIsEnabled();true}.getOrDefault(false)}
        compose.onNodeWithText(text).performClick()
    }
    private fun start() {
        click("Set up a motion recording")
        compose.onNodeWithText("Start recording").assertIsDisplayed().performClick()
        await {runCatching {session().state==CaptureState.RECORDING}.getOrDefault(false)}
    }
    @Test fun realScreenOffSamplesPauseResumeFinishAndReadbackLeaveHardAlarmUnchanged() {
        val engine=AlarmGraph.engine(context)
        assertEquals(ArmResult.ARMED,engine.save(AlarmRule(alarmId,LocalTime.of(7,0),Repeat.Once(LocalDate.now().plusDays(1)))))
        val hard=engine.entries().single {it.rule.id==alarmId}.pending!!
        start();val first=session();val before=summary(first.id).samples
        val stale=Intent(context,MotionCaptureService::class.java).setAction(MotionCaptureService.STOP)
            .putExtra(MotionCaptureService.ID,first.id.toString()).putExtra(MotionCaptureService.REVISION,first.revision)
        shell("input keyevent KEYCODE_SLEEP")
        try {
            await {!context.getSystemService(android.os.PowerManager::class.java).isInteractive}
            await {summary(first.id).samples>=before+20}
            assertFalse(context.getSystemService(android.os.PowerManager::class.java).isInteractive)
        } finally {shell("input keyevent KEYCODE_WAKEUP")}
        click("Pause recording");await {session().state==CaptureState.PAUSED && CaptureRuntime.liveOwner==null}
        val paused=summary(first.id);SystemClock.sleep(1200);assertEquals(paused,summary(first.id))
        click("Resume recording");await {session().state==CaptureState.RECORDING}
        context.startService(stale);SystemClock.sleep(300);assertEquals(CaptureState.RECORDING,session().state)
        click("Finish recording");await {session().state==CaptureState.COMPLETED && CaptureRuntime.liveOwner==null}
        val final=summary(first.id);assertTrue(final.samples>paused.samples);assertTrue(final.issues.isEmpty())
        CaptureStore(context).use {store->
            val streams=store.streams(first.id);assertEquals(2,streams.size)
            assertEquals(final.samples,streams.sumOf {it.committedSamples});assertEquals(2,final.streamSamples.size)
            val chunks=MotionFiles.forSession(context,first.id).recover().chunks
            assertTrue(chunks.all {it.sessionId==first.id && it.samples.all {s->s.x.isFinite() && s.y.isFinite() && s.z.isFinite()}})
            assertEquals(streams.map {it.id}.toSet(),chunks.map {it.streamId}.toSet())
            assertTrue(store.events(first.id).any {it.reason==CaptureReason.PAUSE})
        }
        assertEquals(hard,engine.entries().single {it.rule.id==alarmId}.pending)
        assertEquals(hard.at.toEpochMilli(),context.getSystemService(AlarmManager::class.java).nextAlarmClock!!.triggerTime)
        assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.none {it.id==MotionCaptureService.NOTIFICATION})
        click("Check saved observations")
        compose.onNodeWithText("Saved samples: ${final.samples}",substring=true).assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let {bitmap->
            PlatformTestStorageRegistry.getInstance().openOutputFile("phone-motion-saved.png").use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        }
    }
    @Test fun unexpectedServiceStopRetainsChunksAndRequiresAnExplicitResume() {
        start();val id=session().id;val saved=summary(id).samples
        context.stopService(Intent(context,MotionCaptureService::class.java))
        await {session().state==CaptureState.INTERRUPTED && CaptureRuntime.liveOwner==null}
        assertTrue(summary(id).samples>=saved)
        CaptureStore(context).use {assertEquals(CaptureReason.SERVICE_STOP,it.events(id).last().reason)}
        compose.activityRule.scenario.recreate();SystemClock.sleep(500)
        assertEquals(CaptureState.INTERRUPTED,session().state);assertNull(CaptureRuntime.liveOwner)
        click("Finish recording");await {session().state==CaptureState.COMPLETED}
        click("Delete");compose.onNode(hasText("Delete") and hasAnyAncestor(isDialog())).performClick()
        await {sessions().isEmpty()};assertEquals(0L,summary(id).samples)
    }
    @Test fun largeTextConsentAndControlsCanStartAndFinishARealCapture() {
        shell("settings put system font_scale 2.0");compose.activityRule.scenario.recreate()
        start();val id=session().id
        click("Finish recording");await {session().state==CaptureState.COMPLETED && CaptureRuntime.liveOwner==null}
        assertTrue(summary(id).samples>0)
        compose.onNodeWithTag("capture_home").performScrollToNode(hasTestTag("capture_state_$id"))
        compose.onNodeWithTag("capture_state_$id").assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let {bitmap->
            PlatformTestStorageRegistry.getInstance().openOutputFile("phone-motion-large-text.png").use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        }
    }
    @Test fun aPriorProcessPreparingSessionIsInterruptedWithoutStartingSensors() {
        val old=CaptureStore(context).use {it.start(UUID.randomUUID(),UUID.randomUUID(),Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS),1,UUID.randomUUID())}
        compose.activityRule.scenario.recreate()
        await {CaptureStore(context).use {it.get(old.id)?.state==CaptureState.INTERRUPTED}}
        assertNull(CaptureRuntime.liveOwner);assertEquals(0L,summary(old.id).samples)
        compose.onNodeWithTag("capture_home").performScrollToNode(hasTestTag("capture_state_${old.id}"))
        compose.onNodeWithTag("capture_state_${old.id}").assertTextEquals("Interrupted · Recording has stopped")
    }
    @Test fun firstCheckpointDoesNotInvalidateThePreparationFinishControl() {
        start();val recording=session();assertEquals(2L,recording.revision)
        val initialFinish=Intent(context,MotionCaptureService::class.java).setAction(MotionCaptureService.STOP)
            .putExtra(MotionCaptureService.ID,recording.id.toString()).putExtra(MotionCaptureService.REVISION,1L)
        context.startService(initialFinish)
        await {session().state==CaptureState.COMPLETED && CaptureRuntime.liveOwner==null}
        assertTrue(summary(recording.id).samples>0)
    }
}
