// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.io.PlatformTestStorageRegistry
import dev.bananajeans.solsleep.alarm.*
import dev.bananajeans.solsleep.core.*
import dev.bananajeans.solsleep.data.AlarmStore
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.*
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ChallengeUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val id="challenge-ui-${UUID.randomUUID()}"
    private fun engine()=AlarmGraph.engine(compose.activity)
    private fun shell(cmd: String) {InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd).use {
        java.io.FileInputStream(it.fileDescriptor).use {s->s.readBytes()}}}
    @After fun cleanup() {
        engine().entries().filter {it.rule.id==id || it.rule.label==id}.forEach {e->
            e.pending?.takeIf {it.state==DeliveryState.RINGING}?.let {engine().timeout(it.token)}
            engine().delete(e.rule.id)
        }
        compose.activity.stopService(Intent(compose.activity,RingingService::class.java))
    }
    private fun fixture(p: ChallengePolicy): PendingAlarm {
        val now=Instant.ofEpochMilli(System.currentTimeMillis())
        val pending=PendingAlarm(UUID.randomUUID().toString(),now,LocalDate.now(),DeliveryState.RINGING,startedAt=now)
        AlarmStore(compose.activity).use {it.put(AlarmRule(id,LocalTime.of(7,0),Repeat.Once(LocalDate.now()),label=id,vibrate=false,challenge=p),pending)}
        return pending
    }
    private fun open(p: PendingAlarm,action: String?=null)=ActivityScenario.launch<RingingActivity>(Intent(compose.activity,RingingActivity::class.java)
        .putExtra(AlarmIntents.TOKEN,p.token).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    private fun waitText(text: String)=compose.waitUntil(15000) {compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()}
    @Test fun perAlarmChallengeConfigurationSurvivesEditorRecreationAndSave() {
        waitText("Add alarm");compose.onNodeWithText("Add alarm").performClick()
        compose.waitUntil(10000) {compose.onAllNodesWithTag("alarm_label").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("alarm_label").performTextInput(id);compose.onNodeWithTag("alarm_label").performImeAction()
        compose.onNodeWithText("Add a wake-up challenge").performScrollTo().performClick()
        compose.onNodeWithTag("challenge_enabled").performClick();compose.onNodeWithText("Hard").performClick()
        compose.onNodeWithText("Sleeping sheep").performScrollTo().performClick()
        compose.onNodeWithTag("challenge_save").performClick();compose.activityRule.scenario.recreate()
        compose.waitUntil(10000) {compose.onAllNodesWithTag("save_alarm").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("save_alarm").assertIsDisplayed().performClick()
        compose.waitUntil(10000) {engine().entries().any {it.rule.label==id}}
        val saved=engine().entries().single {it.rule.label==id}.rule.challenge!!
        assertEquals(ChallengeDifficulty.HARD,saved.difficulty);assertEquals(setOf(ChallengeKind.MATH,ChallengeKind.SHEEP),saved.kinds)
    }
    @Test fun realDismissTaskRetainsMissAndIdentityAcrossRecreationThenCompletes() {
        val p=fixture(ChallengePolicy(setOf(ChallengeKind.COPY_CODE),rounds=1))
        open(p,AlarmIntents.DISMISS).use {scenario->
            waitText("Copy the code")
            val first=engine().entries().single {it.rule.id==id}.pending!!.challenge!!
            compose.onNodeWithText("Your answer").performScrollTo().performTextInput("wrong")
            compose.onNodeWithText("Check answer").performScrollTo().performClick()
            compose.waitUntil(10000) {engine().entries().single {it.rule.id==id}.pending!!.challenge!!.failures==1}
            scenario.recreate();waitText("Copy the code")
            val restored=engine().entries().single {it.rule.id==id}.pending!!.challenge!!
            assertEquals(first.id,restored.id);assertEquals(first.seed,restored.seed);assertEquals(first.startedAt,restored.startedAt)
            val policy=engine().entries().single {it.rule.id==id}.rule.challenge!!
            val code=WakeChallenges.question(policy,restored).expected
            compose.onNodeWithText("Your answer").performScrollTo().performTextClearance()
            compose.onNodeWithText("Your answer").performTextInput(code)
            compose.onNodeWithText("Check answer").performScrollTo().performClick()
            compose.waitUntil(10000) {engine().entries().single {it.rule.id==id}.pending!!.state==DeliveryState.DONE}
        }
    }
    @Test fun sleepingSheepHasOneAccessibleTargetAndPhoneAccessAtTwoHundredPercentText() {
        val old=compose.activity.resources.configuration.fontScale
        try {
            shell("settings put system font_scale 2.0")
            compose.waitUntil(15000) {runCatching {compose.activity.resources.configuration.fontScale>=1.99f}.getOrDefault(false)}
            val policy=ChallengePolicy(setOf(ChallengeKind.SHEEP),ChallengeDifficulty.HARD,rounds=1);val p=fixture(policy)
            open(p,AlarmIntents.SNOOZE).use {
                waitText("Sleeping sheep")
                compose.onNodeWithText("Phone / emergency access").assertIsDisplayed().assertIsEnabled()
                val task=engine().entries().single {it.rule.id==id}.pending!!.challenge!!
                val q=WakeChallenges.question(policy,task)
                compose.onNodeWithContentDescription("Sheep ${q.target+1}, eyes closed").performScrollTo().assertIsDisplayed()
                PlatformTestStorageRegistry.getInstance().openOutputFile("wake-challenge-large-text.png").use {out->
                    compose.onNodeWithTag("challenge_ring_root").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,out)}
                compose.onNodeWithText("Phone / emergency access").assertIsDisplayed()
                compose.onNodeWithText("Use the accessible code task (+2 rounds)").performScrollTo().performClick()
                compose.waitUntil(10000) {engine().entries().single {it.rule.id==id}.pending!!.challenge!!.fallback}
                val fallback=engine().entries().single {it.rule.id==id}.pending!!.challenge!!
                assertEquals(3,fallback.total(policy));assertEquals(DeliveryState.RINGING,engine().entries().single {it.rule.id==id}.pending!!.state)
            }
        } finally {
            shell("settings put system font_scale $old")
            compose.waitUntil(15000) {runCatching {kotlin.math.abs(compose.activity.resources.configuration.fontScale-old)<.01f}.getOrDefault(false)}
        }
    }
    @Test fun staleActivityTokenCannotActOnANewerRingingOccurrence() {
        val p=fixture(ChallengePolicy())
        open(p.copy(token="expired-token"),AlarmIntents.DISMISS).use {
            compose.waitUntil(10000) {it.state==androidx.lifecycle.Lifecycle.State.DESTROYED}
            val current=engine().entries().single {e->e.rule.id==id}.pending!!
            assertEquals(p,current);assertNull(current.challenge)
        }
    }
    @Test fun legacyBroadcastActionsCannotBypassConfiguredTasks() {
        val p=fixture(ChallengePolicy())
        compose.activity.sendBroadcast(Intent(compose.activity,AlarmReceiver::class.java).setAction(AlarmIntents.DISMISS).putExtra(AlarmIntents.TOKEN,p.token))
        compose.waitUntil(10000) {engine().entries().single {it.rule.id==id}.pending!!.challenge!=null}
        assertEquals(DeliveryState.RINGING,engine().entries().single {it.rule.id==id}.pending!!.state)
        assertFalse(engine().snooze(p.token));assertFalse(engine().dismiss(p.token))
    }
}
