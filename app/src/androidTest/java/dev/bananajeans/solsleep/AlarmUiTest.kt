// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.bananajeans.solsleep.alarm.*
import dev.bananajeans.solsleep.core.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import androidx.test.platform.io.PlatformTestStorageRegistry

@RunWith(AndroidJUnit4::class)
class AlarmUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Before fun clean() {
        val engine=AlarmGraph.engine(compose.activity)
        engine.entries().forEach {e->
            e.pending?.takeIf {it.state==DeliveryState.RINGING}?.let {engine.dismiss(it.token)}
            engine.delete(e.rule.id)
        }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10000) {compose.onAllNodesWithText("Add alarm").fetchSemanticsNodes().isNotEmpty()}
    }
    @After fun cleanup() {
        val context=compose.activity
        val engine=AlarmGraph.engine(context)
        engine.entries().forEach {e->
            e.pending?.takeIf {it.state==DeliveryState.RINGING}?.let {engine.dismiss(it.token)}
            engine.delete(e.rule.id)
        }
        context.stopService(Intent(context,RingingService::class.java))
    }
    @Test fun createEditSkipToggleAndDeleteAnAlarmUsingTheRealUi() {
        capture("tonight-empty")
        compose.onNodeWithText("Add alarm").performClick()
        waitForTag("alarm_label")
        compose.onNodeWithTag("alarm_label").performTextInput("Morning")
        compose.onNodeWithTag("alarm_label").performImeAction()
        compose.onNodeWithText("Weekly").performClick()
        capture("alarm-editor")
        saveAlarm()
        compose.waitUntil(10000) {AlarmGraph.engine(compose.activity).entries().any {it.rule.label=="Morning"}}
        waitForDescription("Edit alarm")
        compose.onNodeWithContentDescription("Edit alarm").assertExists()
        capture("tonight-alarm")
        compose.onNodeWithContentDescription("Edit alarm").performScrollTo().assertIsDisplayed().performClick()
        waitForTag("alarm_label")
        compose.onNodeWithTag("alarm_label").performTextClearance()
        compose.onNodeWithTag("alarm_label").performTextInput("Fresh start")
        compose.onNodeWithTag("alarm_label").performImeAction()
        saveAlarm()
        compose.waitUntil(10000) {AlarmGraph.engine(compose.activity).entries().single().rule.label=="Fresh start"}
        compose.onNodeWithText("Skip next alarm").performScrollTo().performClick()
        waitForText("Undo skip")
        compose.onNodeWithText("Undo skip").assertExists().performClick()
        waitForText("Skip next alarm")
        compose.onNodeWithContentDescription("Enable 07:00 alarm").performClick()
        compose.waitUntil(10000) {!AlarmGraph.engine(compose.activity).entries().single().rule.enabled}
        compose.waitUntil(10000) {compose.onAllNodes(hasContentDescription("Enable 07:00 alarm") and isOff() and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithContentDescription("Enable 07:00 alarm").assertIsOff().performClick()
        compose.waitUntil(10000) {AlarmGraph.engine(compose.activity).entries().single().rule.enabled}
        compose.onNodeWithContentDescription("Delete").performScrollTo().performClick()
        compose.onNodeWithText("Delete",useUnmergedTree=true).performClick()
        compose.waitUntil(10000) {AlarmGraph.engine(compose.activity).entries().isEmpty()}
    }
    @Test fun editorKeepsDraftAndExistingIdentityAcrossActivityRecreation() {
        compose.onNodeWithText("Add alarm").performClick()
        waitForTag("alarm_label")
        compose.onNodeWithTag("alarm_label").performTextInput("Draft before rotation")
        compose.onNodeWithTag("alarm_label").performImeAction()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("alarm_label").assertTextContains("Draft before rotation")
        saveAlarm()
        compose.waitUntil(10000) {AlarmGraph.engine(compose.activity).entries().size==1}
        val original=AlarmGraph.engine(compose.activity).entries().single().rule.id
        waitForDescription("Edit alarm")
        compose.onNodeWithContentDescription("Edit alarm").performScrollTo().assertIsDisplayed().performClick()
        waitForTag("alarm_label")
        compose.onNodeWithTag("alarm_label").performTextClearance()
        compose.onNodeWithTag("alarm_label").performTextInput("Edited after rotation")
        compose.onNodeWithTag("alarm_label").performImeAction()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("alarm_label").assertTextContains("Edited after rotation")
        saveAlarm()
        compose.waitUntil(10000) {AlarmGraph.engine(compose.activity).entries().single().rule.label=="Edited after rotation"}
        assertEquals(original,AlarmGraph.engine(compose.activity).entries().single().rule.id)
    }

    @Test fun editingAnAdvancedRulePreservesRecurrenceZoneAndOutputSettings() {
        val engine=AlarmGraph.engine(compose.activity)
        val original=AlarmRule("advanced",java.time.LocalTime.of(8,45),
            Repeat.EveryDays(java.time.LocalDate.of(2030,3,31),23),"Shift schedule",enabled=false,
            zoneId="Europe/Tallinn",rampSeconds=45,snoozeMinutes=9,snoozeLimit=7,vibrate=false)
        engine.save(original)
        compose.activityRule.scenario.recreate()
        waitForText("Shift schedule")
        waitForDescription("Edit alarm")
        compose.onNodeWithContentDescription("Edit alarm").performScrollTo().assertIsDisplayed().performClick()
        waitForTag("alarm_label")
        compose.onNodeWithTag("alarm_label").performTextClearance()
        compose.onNodeWithTag("alarm_label").performTextInput("Shift edited")
        compose.onNodeWithTag("alarm_label").performImeAction()
        saveAlarm()
        compose.waitUntil(10000) {engine.entries().single().rule.label=="Shift edited"}
        assertEquals(original.copy(label="Shift edited",revision=2),engine.entries().single().rule)
    }

    @Test fun creatingAnAlarmWorksAtTwoHundredPercentSystemTextSize() {
        val instrumentation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        fun shell(command: String) {
            instrumentation.uiAutomation.executeShellCommand(command).use {fd->
                java.io.FileInputStream(fd.fileDescriptor).use {it.readBytes()}
            }
        }
        val oldScale=compose.activity.resources.configuration.fontScale
        try {
            shell("settings put system font_scale 2.0")
            compose.waitUntil(15000) {runCatching {compose.activity.resources.configuration.fontScale>=1.99f}.getOrDefault(false)}
            waitForText("Add alarm")
            compose.onNodeWithText("Add alarm").performClick()
            waitForTag("alarm_label")
            compose.onNodeWithTag("alarm_label").performTextInput("Large text morning")
            compose.onNodeWithTag("alarm_label").performImeAction()
            saveAlarm()
            compose.waitUntil(10000) {AlarmGraph.engine(compose.activity).entries().any {it.rule.label=="Large text morning"}}
            waitForDescription("Edit alarm")
            capture("large-text-alarm")
        } finally {
            shell("settings put system font_scale "+oldScale)
            compose.waitUntil(15000) {runCatching {kotlin.math.abs(compose.activity.resources.configuration.fontScale-oldScale)<0.01f}.getOrDefault(false)}
        }
    }

    private fun saveAlarm() {
        compose.waitUntil(10000) {compose.onAllNodes(hasTestTag("save_alarm") and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("save_alarm").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
    }
    private fun waitForTag(tag: String) = compose.waitUntil(10000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }
    private fun waitForText(text: String) = compose.waitUntil(10000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }
    private fun waitForDescription(text: String) = compose.waitUntil(10000) {
        compose.onAllNodesWithContentDescription(text).fetchSemanticsNodes().isNotEmpty()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        PlatformTestStorageRegistry.getInstance().openOutputFile(name+".png").use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)
        }
    }
}
