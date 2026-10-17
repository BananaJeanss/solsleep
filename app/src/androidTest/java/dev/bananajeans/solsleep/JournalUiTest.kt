// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep

import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.io.PlatformTestStorageRegistry
import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.bananajeans.solsleep.data.JournalStore
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JournalUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Before fun openJournal() {
        JournalStore(compose.activity).use {store->store.current().forEach {store.delete(it.id,it.revision)}}
        compose.onNodeWithText("Journal").performClick()
        compose.waitUntil(10000) {compose.onAllNodes(hasTestTag("journal_add") and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
    }
    @After fun cleanup() {JournalStore(compose.activity).use {store->store.current().forEach {store.delete(it.id,it.revision)}}}
    private fun records()=JournalStore(compose.activity).use {it.current()}
    @Test fun manualEntryCorrectionAndDeletionUseRealDurableStorage() {
        compose.onNodeWithText("Add a night").performClick()
        compose.onNodeWithTag("journal_note").performScrollTo().performTextInput("Remembered a dream")
        compose.onNodeWithTag("journal_tag_draft").performScrollTo().performTextInput("tea, late")
        capture("journal-editor")
        compose.onNodeWithTag("journal_save").assertIsDisplayed().performClick()
        compose.waitUntil(10000) {records().size==1}
        val original=records().single()
        assertEquals(setOf("tea, late"),original.tags)
        compose.waitUntil(10000) {compose.onAllNodesWithTag("journal_${original.id}").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("journal_${original.id}").performScrollTo().performClick()
        compose.onNodeWithTag("journal_note").performScrollTo().performTextClearance()
        compose.onNodeWithTag("journal_note").performTextInput("Corrected dream")
        compose.onNodeWithTag("journal_save").assertIsDisplayed().performClick()
        compose.waitUntil(10000) {records().single().revision==2L}
        assertEquals(original.id,records().single().id)
        JournalStore(compose.activity).use {assertEquals("Remembered a dream",it.history(original.id).first().note)}
        waitFor("Corrected dream")
        compose.waitUntil(10000) {compose.onAllNodesWithTag("journal_save").fetchSemanticsNodes().isEmpty()}
        compose.onNodeWithText("Saved corrections").performScrollTo().performClick()
        compose.waitUntil(10000) {compose.onAllNodesWithTag("restore_revision_1").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("restore_revision_1").performScrollTo().performClick()
        compose.waitUntil(10000) {records().single().revision==3L}
        assertEquals("Remembered a dream",records().single().note)
        compose.waitUntil(10000) {compose.onAllNodesWithTag("restore_revision_1").fetchSemanticsNodes().isEmpty()}
        capture("journal-night")
        compose.onNodeWithText("Delete").performScrollTo().performClick()
        compose.onNodeWithTag("journal_delete_confirm").performClick()
        compose.waitUntil(10000) {records().isEmpty()}
    }
    @Test fun unsavedDraftSurvivesActivityRecreation() {
        compose.onNodeWithText("Add a night").performClick()
        compose.onNodeWithTag("journal_note").performScrollTo().performTextInput("Draft kept after rotation")
        val editor=compose.onAllNodes(SemanticsMatcher("journal editor identity") {
            it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)?.startsWith("journal_editor_")==true
        }).fetchSemanticsNodes().single().config[androidx.compose.ui.semantics.SemanticsProperties.TestTag]
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag(editor).assertExists()
        compose.onNodeWithTag("journal_note").performScrollTo().assertTextContains("Draft kept after rotation")
        compose.onNodeWithTag("journal_save").assertIsDisplayed().performClick()
        compose.waitUntil(10000) {records().any {it.note=="Draft kept after rotation"}}
        assertEquals(editor.removePrefix("journal_editor_"),records().single().id)
    }
    @Test fun staleUiDraftRemainsVisibleAndCannotOverwriteAnotherWriter() {
        compose.onNodeWithText("Add a night").performClick()
        compose.onNodeWithTag("journal_save").assertIsDisplayed().performClick()
        compose.waitUntil(10000) {records().size==1}
        val original=records().single()
        compose.waitUntil(10000) {compose.onAllNodesWithTag("journal_save").fetchSemanticsNodes().isEmpty()}
        compose.onNodeWithTag("journal_${original.id}").performScrollTo().performClick()
        compose.onNodeWithTag("journal_note").performScrollTo().performTextInput("Unsaved local draft")
        JournalStore(compose.activity).use {it.save(original.copy(revision=2,note="Other writer"),1)}
        compose.onNodeWithTag("journal_save").assertIsDisplayed().performClick()
        val message=compose.activity.getString(R.string.journal_conflict)
        waitFor(message)
        compose.onNodeWithTag("journal_note").performScrollTo().assertTextContains("Unsaved local draft")
        assertEquals("Other writer",records().single().note)
        assertEquals(2L,records().single().revision)
    }
    @Test fun manualEntryWorksAtTwoHundredPercentSystemTextSize() {
        val instrumentation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        fun shell(command: String) {instrumentation.uiAutomation.executeShellCommand(command).use {fd->
            java.io.FileInputStream(fd.fileDescriptor).use {it.readBytes()}
        }}
        val old=compose.activity.resources.configuration.fontScale
        try {
            shell("settings put system font_scale 2.0")
            compose.waitUntil(15000) {runCatching {compose.activity.resources.configuration.fontScale>=1.99f}.getOrDefault(false)}
            compose.onNodeWithTag("journal_add").performScrollTo().performClick()
            compose.onNodeWithTag("journal_note").performScrollTo().performTextInput("Large text night")
            compose.onNodeWithTag("journal_save").assertIsDisplayed().performClick()
            compose.waitUntil(10000) {records().any {it.note=="Large text night"}}
            compose.waitUntil(10000) {compose.onAllNodesWithTag("journal_save").fetchSemanticsNodes().isEmpty()}
            capture("journal-large-text")
        } finally {
            shell("settings put system font_scale $old")
            compose.waitUntil(15000) {runCatching {kotlin.math.abs(compose.activity.resources.configuration.fontScale-old)<0.01f}.getOrDefault(false)}
        }
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        PlatformTestStorageRegistry.getInstance().openOutputFile(name+".png").use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)
        }
    }
    private fun waitFor(text: String)=compose.waitUntil(10000) {compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()}
}
