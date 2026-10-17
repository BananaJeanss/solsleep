// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep

import androidx.compose.ui.test.*
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
        compose.onNodeWithTag("journal_save").performScrollTo().performClick()
        compose.waitUntil(10000) {records().size==1}
        val original=records().single()
        compose.waitUntil(10000) {compose.onAllNodesWithTag("journal_${original.id}").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("journal_${original.id}").performScrollTo().performClick()
        compose.onNodeWithTag("journal_note").performScrollTo().performTextClearance()
        compose.onNodeWithTag("journal_note").performTextInput("Corrected dream")
        compose.onNodeWithTag("journal_save").performScrollTo().performClick()
        compose.waitUntil(10000) {records().single().revision==2L}
        assertEquals(original.id,records().single().id)
        JournalStore(compose.activity).use {assertEquals("Remembered a dream",it.history(original.id).first().note)}
        waitFor("Corrected dream")
        compose.onNodeWithText("Delete").performScrollTo().performClick()
        compose.onNodeWithText("Delete",useUnmergedTree=true).performClick()
        compose.waitUntil(10000) {records().isEmpty()}
    }
    @Test fun unsavedDraftSurvivesActivityRecreation() {
        compose.onNodeWithText("Add a night").performClick()
        compose.onNodeWithTag("journal_note").performScrollTo().performTextInput("Draft kept after rotation")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("journal_note").performScrollTo().assertTextContains("Draft kept after rotation")
        compose.onNodeWithTag("journal_save").performScrollTo().performClick()
        compose.waitUntil(10000) {records().any {it.note=="Draft kept after rotation"}}
    }
    private fun waitFor(text: String)=compose.waitUntil(10000) {compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()}
}
