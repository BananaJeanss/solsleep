// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.core

import java.io.IOException
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import org.junit.*
import org.junit.Assert.*

class MotionCaptureTest {
    private val session=UUID.randomUUID()
    private val stream=UUID.randomUUID()
    private val boot=UUID.randomUUID()
    private val wall=Instant.parse("2026-10-17T23:00:00Z")
    private val anchor=10_000_000_000L
    private fun sample(n: Long)=MotionSample(anchor+n,1.234f,-0.0f,9.81f)
    private fun buffer(publish: (MotionChunk)->Unit)=MotionCaptureBuffer(session,stream,boot,wall,anchor,publish)
    @Test fun actualSamplesAndAnchorsSurviveTimedCheckpointsAndFinalTail() {
        val chunks=mutableListOf<MotionChunk>();val capture=buffer {chunks+=it}
        repeat(100) {assertNull(capture.append(sample(it*100_000_000L)))}
        capture.flush()
        assertEquals(100L,capture.committedSamples);assertEquals(3L,capture.sequence)
        assertEquals((0 until 100).map {sample(it*100_000_000L)},chunks.flatMap {it.samples})
        chunks.forEachIndexed {index,value->
            assertEquals(session,value.sessionId);assertEquals(stream,value.streamId);assertEquals(boot,value.bootId)
            assertEquals(wall,value.anchorWall);assertEquals(anchor,value.anchorElapsedNanos);assertEquals(index.toLong(),value.sequence)
            assertEquals(value,MotionChunkCodec.decode(MotionChunkCodec.encode(value)))
        }
    }
    @Test fun invalidDuplicateLateAndNonFiniteValuesStayMissingWithoutReplacingGoodSamples() {
        val chunks=mutableListOf<MotionChunk>();val capture=buffer {chunks+=it}
        capture.append(sample(1))
        listOf(sample(1),sample(0),sample(-1),sample(2).copy(x=Float.NaN),sample(2).copy(z=Float.POSITIVE_INFINITY)).forEach {
            assertEquals(CaptureReason.INVALID_SAMPLE,capture.append(it))
        }
        assertNull(capture.append(sample(2)));capture.flush()
        assertEquals(5L,capture.rejectedSamples);assertEquals(listOf(sample(1),sample(2)),chunks.single().samples)
    }
    @Test fun realTimestampGapsAreCountedAndNeverFilled() {
        val chunks=mutableListOf<MotionChunk>();val capture=buffer {chunks+=it}
        capture.append(sample(0));assertEquals(CaptureReason.SENSOR_GAP,capture.append(sample(3_000_000_000)))
        capture.flush();assertEquals(1L,capture.gapCount);assertEquals(2L,capture.committedSamples)
        assertEquals(listOf(sample(0),sample(3_000_000_000)),chunks.single().samples)
    }
    @Test fun maximumBatchBoundsApplyEvenWhenSensorDeliversMuchFasterThanRequested() {
        val chunks=mutableListOf<MotionChunk>();val capture=buffer {chunks+=it}
        repeat(1000) {capture.append(sample(it.toLong()))};capture.flush()
        assertEquals(1000L,capture.committedSamples);assertTrue(chunks.all {it.samples.size<=128})
        assertEquals(1000,chunks.flatMap {it.samples}.distinct().size)
    }
    @Test fun failedPublishRetainsExactBatchAndSequenceForAnIdenticalRetry() {
        val attempts=mutableListOf<MotionChunk>();var fail=true
        val capture=buffer {attempts+=it;if(fail) throw IOException("storage unavailable")}
        capture.append(sample(0));capture.append(sample(100_000_000))
        try {capture.flush();fail("Must fail")} catch(_: IOException) { }
        assertEquals(0L,capture.committedSamples);assertEquals(0L,capture.sequence)
        fail=false;capture.flush();assertEquals(attempts[0],attempts[1]);assertEquals(2L,capture.committedSamples)
        capture.flush();assertEquals(2,attempts.size)
    }
    @Test fun retryAfterRealFileRenameKeepsOneCopyAndSummaryStreamsAllValidSamples() {
        val directory=Files.createTempDirectory("capture-retry")
        var fail=true
        try {
            val files=MotionChunkStore(directory,session,checkpoint={if(it==ChunkCheckpoint.RENAMED && fail) throw IOException("process loss")})
            val capture=buffer(files::publish);capture.append(sample(0))
            try {capture.flush();fail("Must fail")} catch(_: IOException) { }
            fail=false;capture.flush()
            val summary=files.summary();assertEquals(1L,summary.chunks);assertEquals(1L,summary.samples);assertTrue(summary.issues.isEmpty())
            assertEquals(1,files.recover().chunks.size)
        } finally {Files.walk(directory).use {it.sorted(Comparator.reverseOrder()).forEach(Files::delete)}}
    }
    @Test fun ownershipDeletionRetainsUnknownFilesAndCanRetryAfterPartialRemoval() {
        val directory=Files.createTempDirectory("capture-delete")
        try {
            val files=MotionChunkStore(directory,session)
            val capture=buffer(files::publish);capture.append(sample(0));capture.flush()
            val unknown=directory.resolve("unrelated.txt");Files.writeString(unknown,"keep")
            try {files.eraseOwned();fail("Unknown files must be retained")} catch(_: IllegalArgumentException) { }
            assertEquals(1L,files.summary().samples);assertEquals("keep",Files.readString(unknown))
            Files.delete(unknown);files.eraseOwned();files.eraseOwned()
            assertEquals(0L,files.summary().samples);assertTrue(Files.exists(directory.resolve(".writer.lock")))
        } finally {Files.walk(directory).use {it.sorted(Comparator.reverseOrder()).forEach(Files::delete)}}
    }
    @Test fun lifecycleCannotResumeTerminalSessionsOrSkipDurablePreparation() {
        assertFalse(CaptureTransitions.permits(CaptureState.COMPLETED,CaptureState.RECORDING))
        assertFalse(CaptureTransitions.permits(CaptureState.INTERRUPTED,CaptureState.RECORDING))
        assertTrue(CaptureTransitions.permits(CaptureState.INTERRUPTED,CaptureState.PREPARING))
        assertTrue(CaptureTransitions.permits(CaptureState.RECORDING,CaptureState.PAUSED))
        assertFalse(CaptureTransitions.permits(CaptureState.RECORDING,CaptureState.DELETING))
        assertTrue(CaptureTransitions.permits(CaptureState.COMPLETED,CaptureState.DELETING))
        assertTrue(CaptureState.entries.none {CaptureTransitions.permits(CaptureState.DELETING,it)})
    }
    @Test fun interruptionPolicyUsesMonotonicFreshnessAndDetectsBothDirectionsOfClockChange() {
        fun reason(ns: Long,wallDelta: Long,last: Long?=ns)=MotionCapturePolicy.interruption(100_000,0,last,100_000+wallDelta,ns)
        assertNull(reason(4_000_000_000,4000))
        assertNull(reason(4_000_000_000,6000))
        assertEquals(CaptureReason.CLOCK_CHANGE,reason(4_000_000_000,6001))
        assertEquals(CaptureReason.CLOCK_CHANGE,reason(4_000_000_000,1999))
        assertEquals(CaptureReason.SENSOR_STALE,reason(15_000_000_000,15000,null))
        assertNull(reason(14_999_999_999,14999,null))
        assertEquals(CaptureReason.MAX_DURATION,reason(MotionCapturePolicy.MAX_NANOS,MotionCapturePolicy.MAX_NANOS/1_000_000))
    }
    @Test fun storagePolicyPreservesReserveAndRejectsTheEntireNextChunkBeforeOverflow() {
        val reserve=MotionCapturePolicy.RESERVE_BYTES;val cap=MotionCapturePolicy.SESSION_BYTES
        assertTrue(MotionCapturePolicy.hasSpace(reserve,0,128*1024))
        assertFalse(MotionCapturePolicy.hasSpace(reserve-1,0))
        assertTrue(MotionCapturePolicy.hasSpace(reserve,cap-10,10))
        assertFalse(MotionCapturePolicy.hasSpace(reserve,cap-10,11))
        assertFalse(MotionCapturePolicy.hasSpace(reserve,cap))
    }
}
