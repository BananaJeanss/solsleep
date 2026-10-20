// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.core

import org.junit.Test
import org.junit.Assert.*
import java.io.*
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CancellationException

class RecordingDeletionsTest {
    private val a=RecordingDeletion(UUID(0,1),UUID(0,11))
    private val b=RecordingDeletion(UUID(Long.MIN_VALUE,2),UUID(-1,-2))
    private val epoch=Instant.EPOCH
    private fun bytes(snapshot: RecordingDeletionSnapshot=RecordingDeletionSnapshot(listOf(b,a)),time: Instant=epoch)=
        ByteArrayOutputStream().also {RecordingDeletionArchive.write(snapshot,it,time)}.toByteArray()
    private fun read(bytes: ByteArray)=RecordingDeletionArchive.inspect(ByteArrayInputStream(bytes))
    private fun rejects(block: ()->Unit) {
        try {block();fail("Invalid deletion history accepted")} catch(_: IllegalArgumentException) {} catch(_: IOException) {}
    }
    private fun changed(original: ByteArray,edit: (ByteBuffer)->Unit): ByteArray {
        val body=original.copyOf(original.size-32);edit(ByteBuffer.wrap(body))
        return body+MessageDigest.getInstance("SHA-256").digest(body)
    }
    @Test fun canonicalRoundtripPreservesAllIdentityBitsAndMillisecondClock() {
        val time=Instant.ofEpochMilli(-123456789)
        val preview=read(bytes(time=time))
        assertEquals(listOf(a,b),preview.snapshot.markers);assertEquals(time,preview.createdAt)
        assertEquals(64,preview.fingerprint.length)
        assertArrayEquals(bytes(time=time),bytes(preview.snapshot,time))
    }
    @Test fun independentlySpecifiedFixtureMatchesWriterAndReaderExactly() {
        val fixture=javaClass.getResource("/recording-deletions-v1.hex")!!.readText().trim().chunked(2).map {it.toInt(16).toByte()}.toByteArray()
        assertArrayEquals(fixture,bytes());assertEquals(listOf(a,b),read(fixture).snapshot.markers)
        assertEquals(epoch,read(fixture).createdAt)
    }
    @Test fun emptyAndMaximumSizedSnapshotsRoundtripWithoutDroppingIdentities() {
        assertTrue(read(bytes(RecordingDeletionSnapshot(emptyList()))).snapshot.markers.isEmpty())
        val markers=List(RecordingDeletionArchive.MAX_MARKERS) {RecordingDeletion(UUID(0,it.toLong()),UUID(1,it.toLong()))}
        val serialized=bytes(RecordingDeletionSnapshot(markers))
        assertEquals(RecordingDeletionArchive.MAX_BYTES,serialized.size)
        assertEquals(markers,read(serialized).snapshot.markers)
        rejects {RecordingDeletionSnapshot(markers+RecordingDeletion(UUID(2,0),UUID(3,0)))}
    }
    @Test fun snapshotIsImmutableAndRejectsDuplicateSessionOrRequestIdentity() {
        val list=mutableListOf(a,b);val snapshot=RecordingDeletionSnapshot(list);list.clear()
        assertEquals(listOf(a,b),snapshot.markers)
        try {(snapshot.markers as MutableList<RecordingDeletion>).clear();fail("Mutable snapshot")} catch(_: UnsupportedOperationException) {}
        rejects {RecordingDeletionSnapshot(listOf(a,a))}
        rejects {RecordingDeletionSnapshot(listOf(a,b.copy(sessionId=a.sessionId)))}
        rejects {RecordingDeletionSnapshot(listOf(a,b.copy(requestId=a.requestId)))}
    }
    @Test fun everyTruncationChecksumChangeAndTrailingByteIsRejected() {
        val valid=bytes()
        for(size in valid.indices) rejects {read(valid.copyOf(size))}
        valid.indices.forEach {i->val corrupt=valid.copyOf();corrupt[i]=(corrupt[i].toInt() xor 1).toByte();rejects {read(corrupt)}}
        rejects {read(valid+byteArrayOf(0))}
    }
    @Test fun resignedUnknownVersionBadCountsAndDuplicateIdentitiesRemainInvalid() {
        val valid=bytes()
        listOf<(ByteBuffer)->Unit>(
            {it.putInt(0,0)}, {it.putInt(4,2)}, {it.putInt(16,-1)}, {it.putInt(16,10_001)},
            {it.putInt(16,1)}, {it.putLong(52,0).putLong(60,1)}, {it.putLong(68,0).putLong(76,11)}
        ).forEach {edit->rejects {read(changed(valid,edit))}}
    }
    @Test fun reorderedButResignedPairsCannotProduceAnotherCanonicalEncoding() {
        val valid=bytes()
        val reordered=changed(valid) {b->val first=valid.copyOfRange(20,52);val second=valid.copyOfRange(52,84)
            b.position(20);b.put(second);b.put(first)}
        rejects {read(reordered)}
    }
    @Test fun byteAndNoProgressBoundsRejectHostileInputs() {
        rejects {read(ByteArray(RecordingDeletionArchive.MAX_BYTES+1))}
        rejects {RecordingDeletionArchive.inspect(object: InputStream() {
            override fun read()=0
            override fun read(b: ByteArray,off: Int,len: Int)=0
        })}
    }
    @Test fun verifiedExportFreezesBeforeOpeningAndRequiresClosedDestination() {
        val original=mutableListOf(a);val snapshot=RecordingDeletionSnapshot(original);var closed=false
        val destination=object: ByteArrayOutputStream() {override fun close() {closed=true;super.close()}}
        val result=RecordingDeletionArchive.exportVerified(snapshot,{original.add(b);destination},{
            assertTrue(closed);ByteArrayInputStream(destination.toByteArray())
        },epoch)
        assertEquals(listOf(a),result.snapshot.markers)
    }
    @Test fun writeAndCloseFailuresAreNotMisreportedAsReadbackFailures() {
        var read=false
        val error=IOException("output close")
        try {RecordingDeletionArchive.exportVerified(RecordingDeletionSnapshot(listOf(a)),{
            object: ByteArrayOutputStream() {override fun close() {throw error}}
        },{read=true;ByteArrayInputStream(bytes())},epoch);fail("Close failure")}
        catch(e: IOException) {assertSame(error,e);assertFalse(e is RecordingDeletionVerificationException)}
        assertFalse(read)
        val writeError=IOException("output write")
        try {RecordingDeletionArchive.exportVerified(RecordingDeletionSnapshot(listOf(a)),{
            object: OutputStream() {override fun write(b: Int) {throw writeError}}
        },{read=true;ByteArrayInputStream(bytes())},epoch);fail("Write failure")}
        catch(e: IOException) {assertSame(writeError,e)}
        assertFalse(read)
        var opened=false
        rejects {RecordingDeletionArchive.exportVerified(RecordingDeletionSnapshot(listOf(a)),
            {opened=true;ByteArrayOutputStream()},{ByteArrayInputStream(bytes())},Instant.ofEpochSecond(0,1))}
        assertFalse(opened)
    }
    @Test fun deniedTruncatedOrReplacedReadbackIsUnverifiedNotSuccessful() {
        listOf<()->InputStream>({throw IOException("denied")},{ByteArrayInputStream(bytes().copyOf(12))},
            {ByteArrayInputStream(bytes(RecordingDeletionSnapshot(listOf(b))))}).forEach {input->
            try {RecordingDeletionArchive.exportVerified(RecordingDeletionSnapshot(listOf(a)),{ByteArrayOutputStream()},input,epoch)
                fail("Readback accepted")} catch(_: RecordingDeletionVerificationException) {}
        }
    }
    @Test fun cancellationDuringReadbackPreservesItsOriginalTypeAndCause() {
        val cancel=CancellationException("cancel")
        try {RecordingDeletionArchive.exportVerified(RecordingDeletionSnapshot(listOf(a)),{ByteArrayOutputStream()},{throw cancel},epoch)
            fail("Cancellation lost")} catch(e: CancellationException) {assertSame(cancel,e)}
    }
}
