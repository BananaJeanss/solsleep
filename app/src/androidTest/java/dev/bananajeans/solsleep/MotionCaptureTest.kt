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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import dev.bananajeans.solsleep.ui.CaptureViewModel
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
        quiesceScreen()
        context.stopService(Intent(context,MotionCaptureService::class.java));await {CaptureRuntime.liveOwner==null}
        clearRecords()
        if(Build.VERSION.SDK_INT>=33) shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        shell("appops set ${context.packageName} POST_NOTIFICATION allow")
        shell("appops set ${context.packageName} SCHEDULE_EXACT_ALARM allow")
        shell("input keyevent KEYCODE_WAKEUP")
        CaptureRuntime.clearError()
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.activityRule.scenario.recreate()
    }
    @After fun cleanup() {
        quiesceScreen()
        compose.activityRule.scenario.moveToState(Lifecycle.State.DESTROYED)
        shell("input keyevent KEYCODE_WAKEUP");shell("settings put system font_scale 1.0")
        await {kotlin.math.abs(context.resources.configuration.fontScale-1f)<.01f}
        shell("appops set ${context.packageName} POST_NOTIFICATION allow")
        context.stopService(Intent(context,MotionCaptureService::class.java));await {CaptureRuntime.liveOwner==null}
        clearRecords();AlarmGraph.engine(context).delete(alarmId)
    }
    private fun quiesceScreen() {
        val model=java.util.concurrent.atomic.AtomicReference<CaptureViewModel>()
        compose.activityRule.scenario.onActivity {model.set(ViewModelProvider(it)[CaptureViewModel::class.java])}
        // A fresh screen starts busy before repeatOnLifecycle launches its load.
        // Let that real load finish while the Activity is still STARTED.
        compose.waitUntil(25000) {!model.get().state.value.busy}
        // Stop periodic reloads, then drain work already launched on Main/IO.
        // Neither wait requires an attached or idle Compose root.
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.waitUntil(25000) {!model.get().state.value.busy}
    }
    private fun clearRecords() {
        // Disposable test installation only. Tests never target a personal installation.
        CaptureStore(context).use {store->store.sessions(100).forEach {MotionFiles.forSession(context,it.id).eraseOwned()}}
        MotionDocuments(context).discard()
        context.deleteDatabase("motion-capture.db")
        context.deleteDatabase("motion-exports.db")
    }
    private fun click(text: String) {
        // The durable service acknowledgement can precede the next screen projection/frame.
        compose.waitUntil(15000) {runCatching {
            compose.onNodeWithTag("capture_home").performScrollToNode(hasText(text))
            compose.onNodeWithText(text).assertIsDisplayed().assertIsEnabled();true
        }.getOrDefault(false)}
        compose.onNodeWithText(text).assertIsDisplayed().assertIsEnabled().performClick()
    }
    private fun start() {
        click("Set up a motion recording")
        compose.onNodeWithText("Start recording").assertIsDisplayed().performClick()
        await {runCatching {session().state==CaptureState.RECORDING}.getOrDefault(false)}
    }
    @Test fun nativeArchivePreviewRestoreAndLargeTextExposeRealSavedRecordingWithoutStartingCapture() {
        shell("settings put system font_scale 2.0")
        val id=UUID.randomUUID();val request=UUID.randomUUID();val owner=UUID.randomUUID();val boot=UUID.randomUUID();val sourceId=UUID.randomUUID()
        val wall=Instant.parse("2026-10-17T23:00:00Z")
        val recording=CaptureRecording(CaptureSession(id,request,owner,CaptureState.COMPLETED,wall,wall.plusSeconds(1),2),
            listOf(CaptureStream(sourceId,id,boot,wall,100,CaptureSource("UI fixture","Native",1,0.01f,40f,false),1,0,0)),
            listOf(CaptureEvent(42,id,CaptureReason.STOP,wall.plusSeconds(1),101,boot,null)))
        val chunk=MotionChunk(id,sourceId,boot,0,wall,100,listOf(MotionSample(101,-0.0f,1f,9.81f)))
        val bytes=java.io.ByteArrayOutputStream().also {out->MotionArchive.write(recording,out) {it(MotionChunkCodec.encode(chunk))}}.toByteArray()
        val uri=android.net.Uri.parse("content://dev.bananajeans.solsleep.debug.test.journal/normal/${UUID.randomUUID()}")
        try {
            context.contentResolver.openOutputStream(uri,"wt")!!.use {it.write(bytes)}
            compose.activityRule.scenario.onActivity {ViewModelProvider(it)[CaptureViewModel::class.java].stage(uri)}
            compose.waitUntil(15000) {
                var ready=false
                compose.activityRule.scenario.onActivity {ready=ViewModelProvider(it)[CaptureViewModel::class.java].state.value.pending!=null}
                ready
            }
            compose.activityRule.scenario.recreate()
            click("Restore this recording")
            await {CaptureStore(context).use {it.get(id)==recording.session}}
            compose.waitUntil(15000) {runCatching {
                compose.onNodeWithTag("capture_home").performScrollToNode(hasTestTag("motion_archive_message"))
                compose.onNodeWithTag("motion_archive_message").assertTextContains("Recording restored.",substring=true);true
            }.getOrDefault(false)}
            assertFalse(MotionDocuments(context).hasPending());assertNull(CaptureRuntime.liveOwner)
            CaptureStore(context).use {assertEquals(recording,it.recording(id));assertNull(it.active())}
            assertEquals(listOf(chunk),MotionFiles.forSession(context,id).recover().chunks)
            compose.onNodeWithTag("capture_home").performScrollToNode(hasTestTag("motion_export_$id"))
            compose.onNodeWithTag("motion_export_$id").assertIsDisplayed().assertIsEnabled()
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let {bitmap->
                PlatformTestStorageRegistry.getInstance().openOutputFile("motion-archive-large-text.png").use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
            }
        } finally {context.contentResolver.delete(uri,null,null)}
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
            val actualApk=java.security.MessageDigest.getInstance("SHA-256").let {digest->
                java.io.File(context.applicationInfo.sourceDir).inputStream().use {input->
                    val bytes=ByteArray(16384);while(true) {val count=input.read(bytes);if(count<0) break;digest.update(bytes,0,count)}
                };digest.digest().joinToString("") {"%02x".format(it)}
            }
            streams.forEach {assertEquals(CaptureProvenance(actualApk,ZoneId.systemDefault().id,MotionCaptureBuffer.PERIOD_MICROS),it.provenance)}
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
    private fun coverageFixture(legacy: Boolean=false): Pair<CaptureRecording,MotionChunk> {
        val id=UUID.randomUUID();val sourceId=UUID.randomUUID();val boot=UUID.randomUUID()
        val wall=Instant.parse("2026-10-17T23:00:00Z")
        val stream=CaptureStream(sourceId,id,boot,wall,0,CaptureSource("Coverage UI fixture","Synthetic test data",1,.01f,40f,false),4,2,1,
            if(legacy) null else CaptureProvenance("a".repeat(64),"Europe/Tallinn",100_000))
        val recording=CaptureRecording(CaptureSession(id,UUID.randomUUID(),UUID.randomUUID(),CaptureState.COMPLETED,wall,wall.plusSeconds(2),2),
            listOf(stream),listOf(CaptureEvent(42,id,CaptureReason.STOP,wall.plusSeconds(2),2_000_000_000,boot,null)))
        val chunk=MotionChunk(id,sourceId,boot,0,wall,0,listOf(1_000_000_000L,1_100_000_000,1_200_000_000,1_600_000_000).map {MotionSample(it,-0.0f,1f,9.81f)})
        CaptureStore(context).use {store->assertTrue(store.restore(recording) {MotionFiles.forSession(context,id).publish(chunk)})}
        return recording to chunk
    }
    private fun openCoverage() {
        click("Check saved observations")
        compose.waitUntil(15000) {compose.onAllNodesWithTag("coverage_open").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()}
        compose.onNodeWithTag("coverage_open").performScrollTo().performClick()
        compose.waitUntil(15000) {compose.onAllNodesWithTag("coverage_screen").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()}
    }
    @Test fun exportReceiptsKeepThePriorVerifiedCopyVisibleAtLargeTextAcrossRecreation() {
        val old=context.resources.configuration.fontScale
        try {
            shell("settings put system font_scale 2.0")
            compose.waitUntil(15000) {context.resources.configuration.fontScale>=1.99f}
            val (recording,chunk)=coverageFixture()
            MotionExportHistory(context).use {history->
                val first=history.start(recording.session.id,MotionExportDestination("b".repeat(64),"test.documents","Receipt UI fixture.solmotion"),recording.session.startedAt)
                history.written(first,recording.session.startedAt);history.verified(first,"c".repeat(64),recording.session.startedAt)
                val later=history.start(recording.session.id,null,recording.session.updatedAt);history.failed(later,recording.session.updatedAt)
            }
            compose.activityRule.scenario.recreate()
            click("Recording export history")
            compose.waitUntil(15000) {compose.onAllNodesWithTag("motion_receipts").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()}
            compose.onNodeWithTag("motion_receipts").performScrollToNode(hasText("Write did not complete"))
            compose.onNodeWithText("Write did not complete").assertIsDisplayed()
            compose.onNodeWithTag("motion_receipts").performScrollToNode(hasText("Document: Receipt UI fixture.solmotion"))
            compose.onNodeWithText("Document: Receipt UI fixture.solmotion").assertIsDisplayed()
            compose.activityRule.scenario.recreate()
            compose.waitUntil(15000) {compose.onAllNodesWithTag("motion_receipts").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()}
            compose.onNodeWithTag("motion_receipts").performScrollToNode(hasText("Matching readback verified"))
            compose.onNodeWithText("Matching readback verified").assertIsDisplayed()
            compose.onNodeWithTag("motion_receipts").performScrollToNode(hasText("Document: Receipt UI fixture.solmotion"))
            compose.onNodeWithText("Document: Receipt UI fixture.solmotion").assertIsDisplayed()
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let {bitmap->
                PlatformTestStorageRegistry.getInstance().openOutputFile("recording-export-history-large-text.png").use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
            }
            compose.onNodeWithTag("motion_receipts").performScrollToNode(hasText("Verified archive content fingerprint: "+"c".repeat(64)))
            compose.onNodeWithText("Verified archive content fingerprint: "+"c".repeat(64)).assertIsDisplayed()
            CaptureStore(context).use {assertEquals(recording,it.recording(recording.session.id))}
            assertEquals(listOf(chunk),MotionFiles.forSession(context,recording.session.id).recover().chunks)
            compose.onNodeWithContentDescription("Close").performClick()
            compose.onNodeWithTag("capture_home").assertExists()
            click("Delete");compose.onNode(hasText("Delete") and hasAnyAncestor(isDialog())).performClick()
            await {sessions().isEmpty()}
            MotionExportHistory(context).use {assertEquals(MotionExportSummary(recording.session.id),it.summary(recording.session.id))}
        } finally {shell("settings put system font_scale $old")}
    }
    @Test fun unavailableReceiptHistoryNeverShowsKnownAbsenceAndRetainsOriginalDatabase() {
        val (recording,_)=coverageFixture()
        MotionExportHistory(context).use {it.start(recording.session.id,null,recording.session.startedAt)}
        val path=context.getDatabasePath("motion-exports.db")
        val damaged=path.readBytes().also {"BROKEN-RECEIPT!!".toByteArray().copyInto(it)};path.writeBytes(damaged)
        compose.activityRule.scenario.recreate();click("Recording export history")
        compose.waitUntil(15000) {compose.onAllNodesWithTag("motion_receipts").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()}
        compose.onNodeWithTag("motion_receipts").performScrollToNode(hasText("Export history is unavailable.",substring=true))
        compose.onNodeWithText("Export history is unavailable.",substring=true).assertIsDisplayed()
        compose.onAllNodes(hasText("No export attempt has been recorded",substring=true)).assertCountEquals(0)
        assertArrayEquals(damaged,path.readBytes())
        CaptureStore(context).use {assertEquals(recording,it.recording(recording.session.id))}
        compose.onNodeWithContentDescription("Close").performClick()
        click("Delete");compose.onNode(hasText("Delete") and hasAnyAncestor(isDialog())).performClick()
        await {sessions().isEmpty()}
        compose.waitUntil(15000) {runCatching {compose.onNodeWithTag("motion_archive_message").assertTextContains("Local export receipts could not be removed",substring=true);true}.getOrDefault(false)}
        assertArrayEquals(damaged,path.readBytes())
    }
    @Test fun nativeCoverageAtTwoHundredPercentTextRetainsRawDataAndSurvivesRecreation() {
        val old=context.resources.configuration.fontScale
        try {
            shell("settings put system font_scale 2.0")
            compose.waitUntil(15000) {context.resources.configuration.fontScale>=1.99f}
            val (recording,chunk)=coverageFixture()
            compose.activityRule.scenario.recreate()
            openCoverage()
            compose.onNodeWithTag("coverage_screen").performScrollToNode(hasTestTag("coverage_supported_0"))
            compose.onNodeWithTag("coverage_supported_0").assertIsDisplayed().assertTextEquals("Time between nearby readings: 0.2 s")
            compose.onNodeWithText("First reading:",substring=true).assertTextContains(":01.000",substring=true)
            compose.onNodeWithText("Last reading:",substring=true).assertTextContains(":01.600",substring=true)
            compose.activityRule.scenario.recreate()
            compose.waitUntil(15000) {compose.onAllNodesWithTag("coverage_screen").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()}
            compose.onNodeWithTag("coverage_screen").performScrollToNode(hasTestTag("coverage_supported_0"))
            compose.onNodeWithTag("coverage_supported_0").assertIsDisplayed().assertTextEquals("Time between nearby readings: 0.2 s")
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let {bitmap->
                PlatformTestStorageRegistry.getInstance().openOutputFile("recording-coverage-large-text.png").use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
            }
            compose.onNodeWithTag("coverage_screen").performScrollToNode(hasText("Show data provenance"))
            compose.onNodeWithText("Show data provenance").performClick()
            compose.onNodeWithTag("coverage_screen").performScrollToNode(hasText("Producing base APK SHA-256: "+"a".repeat(64)))
            compose.onNodeWithText("Producing base APK SHA-256: "+"a".repeat(64)).assertIsDisplayed()
            CaptureStore(context).use {assertEquals(recording,it.recording(recording.session.id))}
            assertEquals(listOf(chunk),MotionFiles.forSession(context,recording.session.id).recover().chunks)
        } finally {shell("settings put system font_scale $old")}
    }
    @Test fun legacyCoverageShowsUnknownCadenceAndLabeledUtcWithoutInventingDuration() {
        val (recording,_)=coverageFixture(legacy=true)
        openCoverage()
        compose.onNodeWithTag("coverage_screen").performScrollToNode(hasText("Original sampling request unknown. A supported duration cannot be calculated for this segment."))
        compose.onNodeWithText("Original sampling request unknown. A supported duration cannot be calculated for this segment.").assertIsDisplayed()
        compose.onAllNodes(hasText("Requested interval:",substring=true)).assertCountEquals(0)
        compose.onAllNodesWithTag("coverage_supported_0").assertCountEquals(0)
        compose.onNodeWithTag("coverage_screen").performScrollToNode(hasText("Original time zone unknown. Times below use UTC."))
        compose.onNodeWithText("Original time zone unknown. Times below use UTC.").assertIsDisplayed()
        CaptureStore(context).use {assertNull(it.streams(recording.session.id).single().provenance)}
    }
    @Test fun corruptRawCoverageIsUnavailableAndNeverRenderedAsAZeroReadingSuccess() {
        val (recording,chunk)=coverageFixture()
        val path=context.noBackupFilesDir.resolve("motion/v1/${recording.session.id}/${chunk.streamId}-0.motion")
        val original=path.readBytes().also {it[it.lastIndex]=(it.last().toInt() xor 1).toByte()};path.writeBytes(original)
        openCoverage()
        compose.onNodeWithTag("coverage_unavailable").assertIsDisplayed()
        compose.onAllNodesWithTag("coverage_total").assertCountEquals(0)
        CaptureStore(context).use {assertEquals(recording,it.recording(recording.session.id))}
        assertArrayEquals(original,path.readBytes())
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
        compose.waitUntil(15000) {runCatching {
            compose.onNodeWithTag("capture_home").performScrollToNode(hasTestTag("capture_state_$id"))
            compose.onNodeWithTag("capture_state_$id").assertIsDisplayed().assertTextEquals("Finished · Saved observations retained");true
        }.getOrDefault(false)}
        compose.onNodeWithTag("capture_state_$id").assertTextEquals("Finished · Saved observations retained")
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let {bitmap->
            PlatformTestStorageRegistry.getInstance().openOutputFile("phone-motion-large-text.png").use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        }
    }
    @Test fun aPriorProcessPreparingSessionIsInterruptedWithoutStartingSensors() {
        val old=CaptureStore(context).use {it.start(UUID.randomUUID(),UUID.randomUUID(),Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS),1,UUID.randomUUID())}
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.waitUntil(25000) {CaptureStore(context).use {it.get(old.id)?.state==CaptureState.INTERRUPTED}}
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
