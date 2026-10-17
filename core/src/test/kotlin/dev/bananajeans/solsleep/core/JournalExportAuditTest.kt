// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.core

import org.junit.Test
import org.junit.Assert.*
import java.io.*
import java.time.Instant
import java.util.concurrent.CancellationException

class JournalExportAuditTest {
    private val at=Instant.parse("2026-10-17T08:00:00Z")
    private val snapshot=JournalSnapshot(emptyList(),setOf("11111111-1111-4111-8111-111111111111"))
    private class Audit(val failAt: String?=null): JournalExportAudit {
        val calls=mutableListOf<String>();var stage=JournalExportStage.STARTED;var hash: String?=null
        private fun call(name: String) {calls.add(name);if(name==failAt) throw IOException("audit unavailable")}
        override fun start(displayName: String?,at: Instant): String {call("start");return "22222222-2222-4222-8222-222222222222"}
        override fun written(id: String,at: Instant) {call("written");stage=JournalExportStage.WRITTEN}
        override fun verified(id: String,fingerprint: String,at: Instant) {call("verified");stage=JournalExportStage.VERIFIED;hash=fingerprint}
        override fun failed(id: String,at: Instant) {call("failed");stage=if(stage==JournalExportStage.STARTED) JournalExportStage.FAILED_WRITE else JournalExportStage.UNVERIFIED}
    }
    @Test fun onlyClosedAndMatchingReadbackBecomesAVerifiedAudit() {
        val audit=Audit();var closed=false;val bytes=object: ByteArrayOutputStream() {override fun close() {closed=true}}
        val result=AuditedJournalExport.run({snapshot},{bytes},{assertTrue(closed);assertEquals(JournalExportStage.WRITTEN,audit.stage);bytes.toByteArray().inputStream()},audit,"眠り 🌙.json",{at})
        assertTrue(result is AuditedJournalResult.Verified);assertTrue(result.auditRecorded)
        assertEquals(listOf("start","written","verified"),audit.calls)
        assertEquals((result as AuditedJournalResult.Verified).preview.fingerprint,audit.hash)
    }
    @Test fun invalidSnapshotRecordsAFailedAttemptWithoutOpeningDestination() {
        val audit=Audit();val failure=JournalExportLimitException()
        val result=AuditedJournalExport.run({throw failure},{fail("Must not open");error("unreachable")},{fail("Must not read");error("unreachable")},audit,null,{at})
        assertSame(failure,(result as AuditedJournalResult.Failed).error);assertTrue(result.auditRecorded)
        assertEquals(JournalExportStage.FAILED_WRITE,audit.stage)
    }
    @Test fun writeFailureCannotPublishVerifiedOrWrittenMetadata() {
        val audit=Audit()
        val result=AuditedJournalExport.run({snapshot},{object: OutputStream() {override fun write(b: Int) {throw IOException("full")}}},
            {fail("Must not read");error("unreachable")},audit,null,{at})
        assertTrue(result is AuditedJournalResult.Failed);assertEquals(listOf("start","failed"),audit.calls)
        assertEquals(JournalExportStage.FAILED_WRITE,audit.stage)
    }
    @Test fun unavailableReadIsRecordedAsWrittenButUnverified() {
        val audit=Audit()
        val result=AuditedJournalExport.run({snapshot},{ByteArrayOutputStream()},{throw IOException("offline")},audit,null,{at})
        assertTrue((result as AuditedJournalResult.Failed).error is JournalVerificationException)
        assertEquals(JournalExportStage.UNVERIFIED,audit.stage);assertEquals(listOf("start","written","failed"),audit.calls)
    }
    @Test fun unavailableAuditStartCannotPreventVerifiedJournalRecovery()=auditFailure("start")
    @Test fun auditPhaseFailureCannotPreventVerifiedJournalRecovery()=auditFailure("written")
    @Test fun failedAuditCompletionCannotInventARecordedSuccess()=auditFailure("verified")
    private fun auditFailure(phase: String) {
        val audit=Audit(phase);val out=ByteArrayOutputStream()
        val result=AuditedJournalExport.run({snapshot},{out},{out.toByteArray().inputStream()},audit,null,{at})
        assertTrue(result is AuditedJournalResult.Verified);assertFalse(result.auditRecorded)
        assertNotEquals(JournalExportStage.VERIFIED,audit.stage);assertNull(audit.hash)
        assertEquals(snapshot.deletedIds,(result as AuditedJournalResult.Verified).preview.deletedIds)
    }
    @Test fun failureToRecordAnErrorNeverReplacesTheOriginalTransferError() {
        val audit=Audit("failed");val source=IOException("write failed")
        val result=AuditedJournalExport.run({snapshot},{throw source},{error("Must not read")},audit,null,{at})
        assertSame(source,(result as AuditedJournalResult.Failed).error);assertFalse(result.auditRecorded)
    }
    @Test fun cancellationNeverBecomesVerifiedOrSwallowedAsAuditFailure() {
        val audit=Audit()
        try {AuditedJournalExport.run({throw CancellationException()},{error("Must not open")},{error("Must not read")},audit,null,{at});fail("Must cancel")}
        catch(_: CancellationException) { }
        assertEquals(listOf("start"),audit.calls);assertEquals(JournalExportStage.STARTED,audit.stage)
        val cancelled=object: JournalExportAudit by audit {override fun start(displayName: String?,at: Instant): String=throw CancellationException()}
        try {AuditedJournalExport.run({snapshot},{error("Must not open")},{error("Must not read")},cancelled,null,{at});fail("Must cancel")}
        catch(_: CancellationException) { }
    }
}
