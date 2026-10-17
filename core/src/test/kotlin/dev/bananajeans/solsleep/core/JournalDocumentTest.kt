// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.core

import org.junit.Test
import org.junit.Assert.*
import java.io.*
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CancellationException

class JournalDocumentTest {
    private val instant=Instant.parse("2026-10-17T08:00:00Z")
    private val original=SleepRecord(createdAt=instant,modifiedAt=instant,start=instant.minusSeconds(28800),
        end=instant,zone="Europe/Tallinn",note="Original 🌙",tags=setOf("tea, late"))
    private val snapshot=JournalSnapshot(listOf(original,original.copy(revision=2,note="Correction")),setOf(UUID.randomUUID().toString()))
    private fun archive(time: Instant=instant)=ByteArrayOutputStream().also {JournalArchive.write(snapshot,it,time)}.toByteArray()
    private fun verificationFailure(block: ()->Unit) {
        try {block();fail("Must report unverified destination")} catch(_: JournalVerificationException) { }
    }
    @Test fun verifiesExactDestinationOnlyAfterOutputHasFlushedAndClosed() {
        var flushed=false;var closed=false
        val output=object: ByteArrayOutputStream() {
            override fun flush() {flushed=true;super.flush()}
            override fun close() {closed=true;super.close()}
        }
        val preview=JournalDocument.exportVerified(snapshot,{output},{assertTrue(flushed && closed);output.toByteArray().inputStream()},instant)
        assertEquals(snapshot.records,preview.records);assertEquals(snapshot.deletedIds,preview.deletedIds)
        assertArrayEquals(archive(),output.toByteArray())
    }
    @Test fun partialWriteFailureClosesTheStreamAndNeverAttemptsReadback() {
        var closed=false;var writes=0
        val output=object: OutputStream() {
            override fun write(b: Int) {if(writes++>=80) throw IOException("quota")}
            override fun close() {closed=true}
        }
        try {JournalDocument.exportVerified(snapshot,{output},{fail("Must not read");error("unreachable")});fail("Must fail")}
        catch(e: IOException) {assertFalse(e is JournalVerificationException)}
        assertTrue(closed);assertEquals(81,writes)
    }
    @Test fun flushAndCloseFailuresCannotBecomeVerifiedSuccess() {
        for(failFlush in listOf(true,false)) {
            var closed=false
            val output=object: ByteArrayOutputStream() {
                override fun flush() {if(failFlush) throw IOException("flush")}
                override fun close() {closed=true;if(!failFlush) throw IOException("close")}
            }
            try {JournalDocument.exportVerified(snapshot,{output},{fail("Must not read");error("unreachable")});fail("Must fail")}
            catch(e: IOException) {assertFalse(e is JournalVerificationException)}
            assertTrue(closed)
        }
    }
    @Test fun truncatedOrCorruptDestinationIsWrittenButUnverified() {
        val valid=archive()
        for(bytes in listOf(valid.copyOf(valid.size/2),valid.clone().also {it[20]=(it[20].toInt() xor 1).toByte()})) {
            val out=ByteArrayOutputStream()
            verificationFailure {JournalDocument.exportVerified(snapshot,{out},{bytes.inputStream()},instant)}
            assertArrayEquals(valid,out.toByteArray())
        }
    }
    @Test fun validDifferentDocumentFailsEvenWhenItsLogicalRecordsMatch() {
        verificationFailure {JournalDocument.exportVerified(snapshot,{ByteArrayOutputStream()},{archive(instant.plusSeconds(1)).inputStream()},instant)}
        val empty=ByteArrayOutputStream().also {JournalArchive.write(emptyList(),it,instant)}.toByteArray()
        verificationFailure {JournalDocument.exportVerified(snapshot,{ByteArrayOutputStream()},{empty.inputStream()},instant)}
    }
    @Test fun unavailableOrUnreadableDestinationCannotClaimVerification() {
        verificationFailure {JournalDocument.exportVerified(snapshot,{ByteArrayOutputStream()},{throw SecurityException("revoked")})}
        verificationFailure {JournalDocument.exportVerified(snapshot,{ByteArrayOutputStream()},{object: InputStream() {
            override fun read(): Int=throw IOException("offline")
        }})}
    }
    @Test fun inputCloseFailureIsUnverifiedAndCancellationPropagates() {
        var closed=false
        verificationFailure {JournalDocument.exportVerified(snapshot,{ByteArrayOutputStream()},{object: ByteArrayInputStream(archive()) {
            override fun close() {closed=true;throw IOException("close")}
        }},instant)}
        assertTrue(closed)
        try {JournalDocument.exportVerified(snapshot,{ByteArrayOutputStream()},{throw CancellationException()});fail("Must cancel")}
        catch(_: CancellationException) { }
    }
    @Test fun validationHappensBeforeOpeningOrTruncatingDestination() {
        val markers=snapshot.deletedIds.toMutableSet()
        val mutated=JournalSnapshot(snapshot.records,markers);markers.add(original.id)
        try {JournalDocument.exportVerified(mutated,{fail("Must not open");error("unreachable")},{fail("Must not read");error("unreachable")});fail("Must reject")}
        catch(_: IllegalArgumentException) { }
    }
}
