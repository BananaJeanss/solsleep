// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.core

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CancellationException

class MotionExportAuditTest {
    private val at=Instant.parse("2026-10-18T08:00:00Z")
    private val preview=javaClass.getResourceAsStream("/motion-archive-v2.solmotion")!!.use {MotionArchive.read(it)}
    private val session=preview.recording.session.id
    private val destination=MotionExportDestination("1".repeat(64),"test.documents","眠り 🌙.solmotion")
    private class Audit(private val failAt: String?=null): MotionExportAudit {
        val calls=mutableListOf<String>();var stage=MotionExportStage.STARTED;var hash: String?=null
        val id=UUID.randomUUID()
        private fun call(name: String) {calls+=name;if(failAt==name) throw IOException("Receipt unavailable")}
        override fun start(sessionId: UUID,destination: MotionExportDestination?,at: Instant): UUID {call("start");return id}
        override fun written(id: UUID,at: Instant) {call("written");stage=MotionExportStage.WRITTEN}
        override fun verified(id: UUID,fingerprint: String,at: Instant) {call("verified");stage=MotionExportStage.VERIFIED;hash=fingerprint}
        override fun failed(id: UUID,at: Instant) {call("failed");stage=if(stage==MotionExportStage.STARTED) MotionExportStage.FAILED_WRITE else MotionExportStage.UNVERIFIED}
    }
    @Test fun verifiedReceiptRequiresClosedOutputAndSameSessionPreview() {
        val audit=Audit()
        val result=AuditedMotionExport.run(session,destination,audit,{at}) {written->
            assertEquals(MotionExportStage.STARTED,audit.stage);written()
            assertEquals(MotionExportStage.WRITTEN,audit.stage);preview
        }
        assertTrue(result is AuditedMotionResult.Verified);assertTrue(result.auditRecorded)
        assertEquals(listOf("start","written","verified"),audit.calls);assertEquals(preview.fingerprint,audit.hash)
    }
    @Test fun failedSnapshotOrWriteNeverPublishesWrittenOrVerified() {
        val audit=Audit();val error=IOException("Spool or provider full")
        val result=AuditedMotionExport.run(session,null,audit,{at}) {throw error}
        assertSame(error,(result as AuditedMotionResult.Failed).error)
        assertEquals(listOf("start","failed"),audit.calls);assertEquals(MotionExportStage.FAILED_WRITE,audit.stage)
    }
    @Test fun readbackFailureIsExplicitlyWrittenButUnverified() {
        val audit=Audit();val error=IOException("Readback denied")
        val result=AuditedMotionExport.run(session,destination,audit,{at}) {written->written();throw error}
        assertSame(error,(result as AuditedMotionResult.Failed).error);assertTrue(result.auditRecorded)
        assertEquals(MotionExportStage.UNVERIFIED,audit.stage);assertNull(audit.hash)
    }
    @Test fun eachReceiptFailureStillAllowsVerifiedOriginalArchiveRecovery() {
        for(phase in listOf("start","written","verified")) {
            val audit=Audit(phase)
            val result=AuditedMotionExport.run(session,destination,audit,{at}) {written->written();preview}
            assertSame(preview,(result as AuditedMotionResult.Verified).preview);assertFalse(result.auditRecorded)
            assertNotEquals(MotionExportStage.VERIFIED,audit.stage);assertNull(audit.hash)
        }
    }
    @Test fun failedErrorReceiptRetainsTheOriginalTransferFailure() {
        val audit=Audit("failed");val original=IOException("Provider write failed")
        val result=AuditedMotionExport.run(session,null,audit,{at}) {throw original}
        assertSame(original,(result as AuditedMotionResult.Failed).error);assertFalse(result.auditRecorded)
    }
    @Test fun cancellationAtEitherTransferPhaseNeverBecomesSuccess() {
        for(writtenFirst in listOf(false,true)) {
            val audit=Audit()
            try {AuditedMotionExport.run(session,null,audit,{at}) {written->
                if(writtenFirst) written();throw CancellationException()
            };fail("Must cancel")} catch(_: CancellationException) { }
            assertNull(audit.hash);assertFalse("failed" in audit.calls)
            assertEquals(if(writtenFirst) MotionExportStage.WRITTEN else MotionExportStage.STARTED,audit.stage)
        }
    }
    @Test fun auditCancellationIsNotSwallowedToStartAnUnrequestedTransfer() {
        val audit=object: MotionExportAudit by Audit() {
            override fun start(sessionId: UUID,destination: MotionExportDestination?,at: Instant): UUID=throw CancellationException()
        }
        try {AuditedMotionExport.run(session,null,audit,{at}) {fail("Must not transfer");preview};fail("Must cancel")}
        catch(_: CancellationException) { }
    }
    @Test fun unrelatedOrUnclosedSuccessfulTransferCannotPublishAReceipt() {
        for(id in listOf(session,UUID.randomUUID())) {
            val audit=Audit()
            val result=AuditedMotionExport.run(id,null,audit,{at}) {written->if(id!=session) written();preview}
            assertTrue(result is AuditedMotionResult.Failed);assertNull(audit.hash)
        }
    }
    @Test fun duplicateOutputCompletionCannotPublishVerified() {
        val audit=Audit()
        val result=AuditedMotionExport.run(session,null,audit,{at}) {written->written();written();preview}
        assertTrue(result is AuditedMotionResult.Failed);assertEquals(MotionExportStage.UNVERIFIED,audit.stage)
    }
    @Test fun invalidDestinationAndCrossSessionSummaryCannotMasqueradeAsKnownHistory() {
        fun rejects(block: ()->Unit) {try {block();fail("Must reject")} catch(_: IllegalArgumentException) { }}
        rejects {MotionExportDestination("x",null,null)}
        rejects {destination.copy(authority="provider?token=secret")}
        rejects {destination.copy(displayName="invisible\nname")}
        rejects {destination.copy(displayName="x".repeat(257))}
        rejects {destination.copy(displayName="\ud800")}
        val known=MotionExportAttempt(UUID.randomUUID(),session,1,at,at,destination,MotionExportStage.VERIFIED,preview.fingerprint)
        rejects {MotionExportSummary(UUID.randomUUID(),known,known)}
        rejects {MotionExportSummary(session,null,known)}
        rejects {known.copy(stage=MotionExportStage.WRITTEN)}
        assertEquals(null,MotionExportSummary(session).lastAttempt)
    }
}
