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
import java.io.File

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
        compose.onNodeWithTag("alarm_label").performTextInput("Morning")
        compose.onNodeWithTag("alarm_label").performImeAction()
        compose.onNodeWithText("Weekly").performClick()
        capture("alarm-editor")
        compose.onNodeWithTag("save_alarm").performScrollTo().performClick()
        compose.waitUntil(10000) {AlarmGraph.engine(compose.activity).entries().any {it.rule.label=="Morning"}}
        compose.onNodeWithContentDescription("Edit alarm").assertExists()
        capture("tonight-alarm")
        compose.onNodeWithContentDescription("Edit alarm").performClick()
        compose.onNodeWithTag("alarm_label").performTextClearance()
        compose.onNodeWithTag("alarm_label").performTextInput("Fresh start")
        compose.onNodeWithTag("alarm_label").performImeAction()
        compose.onNodeWithTag("save_alarm").performScrollTo().performClick()
        compose.waitUntil(10000) {AlarmGraph.engine(compose.activity).entries().single().rule.label=="Fresh start"}
        compose.onNodeWithText("Skip next alarm").performScrollTo().performClick()
        compose.onNodeWithText("Undo skip").assertExists().performClick()
        compose.onNodeWithContentDescription("Enable 07:00 alarm").performClick()
        compose.waitUntil(10000) {!AlarmGraph.engine(compose.activity).entries().single().rule.enabled}
        compose.onNodeWithContentDescription("Enable 07:00 alarm").assertIsOff().performClick()
        compose.waitUntil(10000) {AlarmGraph.engine(compose.activity).entries().single().rule.enabled}
        compose.onNodeWithContentDescription("Delete").performScrollTo().performClick()
        compose.onNodeWithText("Delete",useUnmergedTree=true).performClick()
        compose.waitUntil(10000) {AlarmGraph.engine(compose.activity).entries().isEmpty()}
    }
    @Test fun editorKeepsDraftAndExistingIdentityAcrossActivityRecreation() {
        compose.onNodeWithText("Add alarm").performClick()
        compose.onNodeWithTag("alarm_label").performTextInput("Draft before rotation")
        compose.onNodeWithTag("alarm_label").performImeAction()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("alarm_label").assertTextContains("Draft before rotation")
        compose.onNodeWithTag("save_alarm").performScrollTo().performClick()
        compose.waitUntil(10000) {AlarmGraph.engine(compose.activity).entries().size==1}
        val original=AlarmGraph.engine(compose.activity).entries().single().rule.id
        compose.onNodeWithContentDescription("Edit alarm").performClick()
        compose.onNodeWithTag("alarm_label").performTextClearance()
        compose.onNodeWithTag("alarm_label").performTextInput("Edited after rotation")
        compose.onNodeWithTag("alarm_label").performImeAction()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("alarm_label").assertTextContains("Edited after rotation")
        compose.onNodeWithTag("save_alarm").performScrollTo().performClick()
        compose.waitUntil(10000) {AlarmGraph.engine(compose.activity).entries().single().rule.label=="Edited after rotation"}
        assertEquals(original,AlarmGraph.engine(compose.activity).entries().single().rule.id)
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val folder=File(compose.activity.filesDir,"qa").apply {mkdirs()}
        File(folder,name+".png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)
        }
    }
}
