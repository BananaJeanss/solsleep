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
        compose.activity.deleteDatabase("journal-exports.db")
        compose.onNodeWithText("Journal").performClick()
        compose.waitUntil(10000) {compose.onAllNodes(hasTestTag("journal_add") and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
    }
    @After fun cleanup() {
        JournalStore(compose.activity).use {store->store.current().forEach {store.delete(it.id,it.revision)}}
        compose.activity.deleteDatabase("journal-exports.db")
    }
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
        compose.onNodeWithTag("journal_tag_draft").performScrollTo().performTextInput("café, late")
        compose.onNodeWithContentDescription("Add a tag").performClick()
        val editor=compose.onAllNodes(SemanticsMatcher("journal editor identity") {
            it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)?.startsWith("journal_editor_")==true
        }).fetchSemanticsNodes().single().config[androidx.compose.ui.semantics.SemanticsProperties.TestTag]
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag(editor).assertExists()
        compose.onNodeWithText("café, late").assertExists()
        compose.onNodeWithTag("journal_note").performScrollTo().assertTextContains("Draft kept after rotation")
        compose.onNodeWithTag("journal_save").assertIsDisplayed().performClick()
        compose.waitUntil(10000) {records().any {it.note=="Draft kept after rotation"}}
        assertEquals(editor.removePrefix("journal_editor_"),records().single().id)
        assertEquals(setOf("café, late"),records().single().tags)
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
    @Test fun archivePreviewAndRestoreShowPortableDeletionsAndPreserveLocalEntries() {
        val instant=java.time.Instant.parse("2026-10-17T08:00:00Z")
        val local=dev.bananajeans.solsleep.core.SleepRecord(createdAt=instant,modifiedAt=instant,
            start=instant.minusSeconds(28800),end=instant,zone="Europe/Tallinn",note="Local entry kept")
        val incoming=local.copy(id=java.util.UUID.randomUUID().toString(),note="Imported entry")
        val deleted=java.util.UUID.randomUUID().toString()
        JournalStore(compose.activity).use {it.save(local,0)}
        val file=java.io.File(compose.activity.cacheDir,"journal-ui-${java.util.UUID.randomUUID()}.json")
        try {
            file.outputStream().use {dev.bananajeans.solsleep.core.JournalArchive.write(
                dev.bananajeans.solsleep.core.JournalSnapshot(listOf(incoming),setOf(local.id,deleted)),it)}
            compose.runOnUiThread {
                val model=androidx.lifecycle.ViewModelProvider(compose.activity)[dev.bananajeans.solsleep.ui.JournalViewModel::class.java]
                model.inspect(android.net.Uri.fromFile(file))
            }
            waitFor("2 deletion markers")
            compose.onNodeWithText("1 existing night will be kept despite an imported deletion marker").assertExists()
            capture("journal-restore-preview")
            compose.onNodeWithText("Restore journal").performClick()
            compose.waitUntil(10000) {records().size==2}
            compose.waitUntil(10000) {compose.onAllNodesWithTag("journal_restore_result").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("journal_restore_result").performScrollTo()
            compose.onNodeWithText("1 night added").assertExists()
            compose.onNodeWithText("1 deletion marker added").assertExists()
            compose.onNodeWithText("1 existing night kept despite an imported deletion marker").assertExists()
            capture("journal-restore-result")
            JournalStore(compose.activity).use {
                assertEquals(local,it.history(local.id).single())
                assertTrue(deleted in it.archiveSnapshot().deletedIds)
                assertFalse(local.id in it.archiveSnapshot().deletedIds)
            }
        } finally {file.delete()}
    }
    @Test fun exportShowsUnverifiedReadbackThenARealVerifiedSuccessWithoutChangingTheJournal() {
        compose.onNodeWithText("Add a night").performClick()
        compose.onNodeWithTag("journal_note").performScrollTo().performTextInput("Keep this source night")
        compose.onNodeWithTag("journal_save").assertIsDisplayed().performClick()
        compose.waitUntil(10000) {records().size==1}
        compose.waitUntil(10000) {compose.onAllNodesWithTag("journal_save").fetchSemanticsNodes().isEmpty()}
        val before=JournalStore(compose.activity).use {it.archiveSnapshot()}
        val uris=listOf("deny_read","normal").map {
            android.net.Uri.parse("content://dev.bananajeans.solsleep.debug.test.journal/$it/${java.util.UUID.randomUUID()}")
        }
        try {
            fun export(uri: android.net.Uri) {compose.runOnUiThread {
                androidx.lifecycle.ViewModelProvider(compose.activity)[dev.bananajeans.solsleep.ui.JournalViewModel::class.java].export(uri)
            }}
            export(uris[0])
            waitFor(compose.activity.getString(R.string.journal_export_verify_error))
            capture("journal-export-unverified")
            assertEquals(before,JournalStore(compose.activity).use {it.archiveSnapshot()})
            export(uris[1])
            waitFor(compose.activity.getString(R.string.journal_exported))
            compose.onAllNodesWithText(compose.activity.getString(R.string.journal_export_verify_error)).assertCountEquals(0)
            capture("journal-export-verified")
            compose.onNodeWithTag("journal_home").performScrollToNode(hasTestTag("journal_export_history"))
            compose.onNodeWithText("Export history").assertExists()
            compose.onNodeWithText("Verified by readback").assertExists()
            capture("journal-export-history")
            dev.bananajeans.solsleep.data.JournalExportHistory(compose.activity).use {audit->
                assertEquals(dev.bananajeans.solsleep.core.JournalExportStage.VERIFIED,audit.summary().lastAttempt!!.stage)
                assertEquals(audit.summary().lastAttempt,audit.summary().lastVerified)
            }
            val preview=requireNotNull(compose.activity.contentResolver.openInputStream(uris[1])).use(dev.bananajeans.solsleep.core.JournalArchive::inspect)
            assertEquals(before.records,preview.records);assertEquals(before.deletedIds,preview.deletedIds)
            assertEquals(before,JournalStore(compose.activity).use {it.archiveSnapshot()})
        } finally {uris.forEach {compose.activity.contentResolver.delete(it,null,null)}}
    }
    @Test fun unavailableExportHistoryIsNotShownAsNoVerifiedCopiesAndDoesNotBlockRecovery() {
        compose.onNodeWithText("Add a night").performClick()
        compose.onNodeWithTag("journal_note").performScrollTo().performTextInput("Recover without export history")
        compose.onNodeWithTag("journal_save").assertIsDisplayed().performClick()
        compose.waitUntil(10000) {records().size==1}
        compose.waitUntil(10000) {compose.onAllNodesWithTag("journal_save").fetchSemanticsNodes().isEmpty()}
        val path=compose.activity.getDatabasePath("journal-exports.db")
        android.database.sqlite.SQLiteDatabase.openDatabase(path.path,null,android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use {it.version=99}
        compose.runOnUiThread {
            androidx.lifecycle.ViewModelProvider(compose.activity)[dev.bananajeans.solsleep.ui.JournalViewModel::class.java].reload()
        }
        compose.onNodeWithTag("journal_home").performScrollToNode(hasTestTag("journal_export_history"))
        waitFor(compose.activity.getString(R.string.journal_export_history_unavailable))
        compose.onAllNodesWithText(compose.activity.getString(R.string.journal_export_no_verified)).assertCountEquals(0)
        val uri=android.net.Uri.parse("content://dev.bananajeans.solsleep.debug.test.journal/normal/${java.util.UUID.randomUUID()}")
        try {
            compose.runOnUiThread {
                androidx.lifecycle.ViewModelProvider(compose.activity)[dev.bananajeans.solsleep.ui.JournalViewModel::class.java].export(uri)
            }
            compose.onNodeWithTag("journal_home").performScrollToNode(hasTestTag("journal_add"))
            waitFor(compose.activity.getString(R.string.journal_exported))
            assertEquals("Recover without export history",records().single().note)
            requireNotNull(compose.activity.contentResolver.openInputStream(uri)).use {
                assertEquals(records(),dev.bananajeans.solsleep.core.JournalArchive.inspect(it).records)
            }
        } finally {compose.activity.contentResolver.delete(uri,null,null)}
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        PlatformTestStorageRegistry.getInstance().openOutputFile(name+".png").use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)
        }
    }
    private fun waitFor(text: String)=compose.waitUntil(10000) {compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()}
}
