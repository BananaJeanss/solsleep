// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.bananajeans.solsleep.core.*
import dev.bananajeans.solsleep.data.JournalStore
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.*
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class JournalDocumentProviderTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver=context.contentResolver
    private val name="journal-provider-${UUID.randomUUID()}.db"
    private val store=JournalStore(context,name)
    private val uris=mutableListOf<Uri>()
    private val instant=Instant.parse("2026-10-17T08:00:00Z")
    private val r=SleepRecord(createdAt=instant,modifiedAt=instant,start=instant.minusSeconds(28800),
        end=instant,zone="Europe/Tallinn",note="Provider transfer 🌙",tags=setOf("tea, late"))
    @Before fun seed() {
        store.save(r,0);store.save(r.copy(revision=2,note="Correction"),1)
        val deleted=r.copy(id=UUID.randomUUID().toString());store.save(deleted,0);store.delete(deleted.id,1)
    }
    @After fun cleanup() {
        store.close();context.deleteDatabase(name)
        uris.forEach {resolver.delete(it,null,null)}
    }
    private fun uri(scenario: String)=Uri.parse("content://dev.bananajeans.solsleep.debug.test.journal/$scenario/${UUID.randomUUID()}").also {uris.add(it)}
    private fun export(uri: Uri)=JournalDocument.exportVerified(store.archiveSnapshot(),
        {requireNotNull(resolver.openOutputStream(uri,"wt"))},
        {requireNotNull(resolver.openInputStream(uri))},instant)
    @Test fun realContentResolverRoundTripRestoresEveryRevisionAndMarkerInACleanStore() {
        val before=store.archiveSnapshot();val preview=export(uri("normal"))
        assertEquals(before.records,preview.records);assertEquals(before.deletedIds,preview.deletedIds)
        val target="provider-restored-${UUID.randomUUID()}.db"
        try {JournalStore(context,target).use {restored->
            assertEquals(1,restored.restore(preview).nightsAdded)
            assertEquals(before,restored.archiveSnapshot())
        }} finally {context.deleteDatabase(target)}
        assertEquals(before,store.archiveSnapshot())
    }
    @Test fun providerWriteDenialDoesNotClaimVerificationOrModifySource() {
        val before=store.archiveSnapshot()
        try {export(uri("deny_write"));fail("Must fail")}
        catch(e: IOException) {assertFalse(e is JournalVerificationException)}
        assertEquals(before,store.archiveSnapshot())
    }
    @Test fun providerReadDenialIsExplicitlyUnverifiedAndSourceSurvives()=unverified("deny_read")
    @Test fun providerTruncationIsExplicitlyUnverifiedAndSourceSurvives()=unverified("truncated")
    @Test fun providerValidReplacementIsExplicitlyUnverifiedAndSourceSurvives()=unverified("replaced")
    private fun unverified(scenario: String) {
        val before=store.archiveSnapshot();val uri=uri(scenario)
        try {export(uri);fail("Must report unverified document")}
        catch(_: JournalVerificationException) { }
        assertEquals(before,store.archiveSnapshot())
    }
}
