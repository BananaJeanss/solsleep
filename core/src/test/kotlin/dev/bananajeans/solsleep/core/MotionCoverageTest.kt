// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.core

import org.junit.Test
import org.junit.Assert.*
import java.time.Instant
import java.util.UUID

class MotionCoverageTest {
    private val session=CaptureSession(UUID(0,1),UUID(0,2),UUID(0,3),CaptureState.COMPLETED,
        Instant.parse("2026-10-17T23:00:00Z"),Instant.parse("2026-10-17T22:00:00Z"),2)
    private val source=CaptureStream(UUID(0,4),session.id,UUID(0,5),session.startedAt,0,
        CaptureSource("Test","Synthetic fixture",1,.01f,40f,false),0,0,0,
        CaptureProvenance("a".repeat(64),"Europe/Tallinn",100_000))
    private fun recording(streams: List<CaptureStream> = listOf(source))=CaptureRecording(session,streams,
        listOf(CaptureEvent(1,session.id,CaptureReason.CLOCK_CHANGE,session.updatedAt,Long.MAX_VALUE,source.bootId,null)))
    private fun chunk(vararg times: Long,s: CaptureStream=source,sequence: Long=0)=MotionChunk(session.id,s.id,s.bootId,
        sequence,s.wall,s.elapsedNanos,times.map {MotionSample(it,0f,0f,9.81f)})
    private fun report(r: CaptureRecording=recording(),vararg chunks: MotionChunk)=MotionCoverage(r).also {a->
        chunks.forEach {a.accept(MotionChunkCodec.encode(it))}
    }.finish()
    private fun reject(block: ()->Unit) {try {block();fail("Must reject unverified input")} catch(_: IllegalArgumentException) {}}
    @Test fun nearbyIntervalsCountExactlyWithoutExtrapolatingFromTheAnchorOrToStop() {
        val row=report(chunks=arrayOf(chunk(1_000_000_000,1_100_000_000,1_200_000_000))).streams.single()
        assertEquals(3L,row.samples);assertEquals(200_000_000L,row.supportedNanos)
        assertEquals(0L,row.gapNanos);assertEquals(200_000_000L,row.spanNanos)
        assertEquals(1_000_000_000L,row.firstNanos);assertEquals(1_200_000_000L,row.lastNanos)
    }
    @Test fun cadenceThresholdIsInclusiveAndLongerIntervalsRemainUnjoined() {
        val row=report(chunks=arrayOf(chunk(0,300_000_000,600_000_001,700_000_001))).streams.single()
        assertEquals(300_000_000L,row.thresholdNanos);assertEquals(400_000_000L,row.supportedNanos)
        assertEquals(300_000_001L,row.gapNanos);assertEquals(1L,row.gaps)
    }
    @Test fun anExtremelySlowRequestStillCannotBridgeMoreThanTwoSeconds() {
        val s=source.copy(provenance=source.provenance!!.copy(requestedPeriodMicros=60_000_000))
        val row=report(recording(listOf(s)),chunk(0,2_000_000_000,4_000_000_001,s=s)).streams.single()
        assertEquals(2_000_000_000L,row.supportedNanos);assertEquals(2_000_000_001L,row.gapNanos)
    }
    @Test fun consecutiveChunksJoinButMissingChunksBreakEvenNearbyReadings() {
        val a=chunk(0,100_000_000);val b=chunk(150_000_000,250_000_000,sequence=1)
        assertEquals(250_000_000L,report(chunks=arrayOf(a,b)).streams.single().supportedNanos)
        val result=report(chunks=arrayOf(a,b.copy(sequence=2)));val row=result.streams.single()
        assertEquals(200_000_000L,row.supportedNanos);assertEquals(50_000_000L,row.gapNanos)
        assertEquals(1L,row.missingChunks);assertEquals(1L,row.gaps);assertTrue(result.incomplete)
    }
    @Test fun legacyCadenceRemainsUnknownAlthoughRawCountsAndExtentAreKnown() {
        val s=source.copy(provenance=null)
        val row=report(recording(listOf(s)),chunk(0,100_000_000,s=s)).streams.single()
        assertEquals(2L,row.samples);assertEquals(100_000_000L,row.spanNanos)
        assertNull(row.supportedNanos);assertNull(row.gapNanos);assertNull(row.thresholdNanos)
    }
    @Test fun emptyAndSingleReadingSegmentsHaveNoInventedTime() {
        val empty=report().streams.single()
        assertEquals(0L,empty.samples);assertNull(empty.firstNanos);assertNull(empty.spanNanos)
        assertEquals(0L,empty.supportedNanos)
        val one=report(chunks=arrayOf(chunk(1_000_000_000))).streams.single()
        assertEquals(0L,one.supportedNanos);assertEquals(0L,one.spanNanos)
    }
    @Test fun missingIndexedSamplesRemainVisibleWhileAPublishedTailMayLeadTheIndex() {
        val missing=source.copy(committedSamples=7)
        val result=report(recording(listOf(missing)),chunk(0,100_000_000))
        assertEquals(5L,result.streams.single().missingIndexedSamples);assertTrue(result.incomplete)
        assertFalse(report(chunks=arrayOf(chunk(0,100_000_000))).incomplete)
    }
    @Test fun segmentsAreNeverJoinedAndWallClockRollbackDoesNotChangeElapsedCoverage() {
        val second=source.copy(id=UUID(0,6),wall=source.wall.minusSeconds(7200))
        val value=report(recording(listOf(source,second)),chunk(0,100_000_000),chunk(150_000_000,250_000_000,s=second))
        assertFalse(value.overlappingStreams);assertEquals(listOf(100_000_000L,100_000_000L),value.streams.map {it.supportedNanos})
    }
    @Test fun sameBootOverlapIsFlaggedButDifferentBootClocksAreNeverCompared() {
        val second=source.copy(id=UUID(0,6))
        val r=recording(listOf(source,second))
        assertTrue(report(r,chunk(0,100),chunk(50,150,s=second)).overlappingStreams)
        val nextBoot=second.copy(bootId=UUID(0,7))
        assertFalse(report(recording(listOf(source,nextBoot)),chunk(0,100),chunk(50,150,s=nextBoot)).overlappingStreams)
    }
    @Test fun checksumSourceClockAndSessionMismatchesNeverProduceAnEmptySuccess() {
        val bytes=MotionChunkCodec.encode(chunk(1,2));bytes[bytes.lastIndex]=(bytes.last().toInt() xor 1).toByte()
        reject {MotionCoverage(recording()).accept(bytes)}
        for(bad in listOf(chunk(1,2).copy(sessionId=UUID.randomUUID()),chunk(1,2).copy(streamId=UUID.randomUUID()),
            chunk(1,2).copy(bootId=UUID.randomUUID()),chunk(1,2).copy(anchorWall=source.wall.plusMillis(1)))) {
            reject {report(chunks=arrayOf(bad))}
        }
    }
    @Test fun canonicalOrderDuplicateAndRegressedTimestampsAreEnforced() {
        reject {report(chunks=arrayOf(chunk(1,2),chunk(1,2,sequence=1)))}
        reject {report(chunks=arrayOf(chunk(1,2),chunk(3,4)))}
        reject {report(chunks=arrayOf(chunk(3,4,sequence=1),chunk(5,6)))}
        val second=source.copy(id=UUID(0,6))
        reject {report(recording(listOf(source,second)),chunk(1,2,s=second),chunk(3,4))}
    }
    @Test fun exactInputAndMetadataAreFingerprintBoundAndRebuildingIsDeterministic() {
        val a=chunk(0,100_000_000);val original=report(chunks=arrayOf(a))
        assertEquals(original,report(chunks=arrayOf(a)));assertEquals(1,original.policyVersion)
        assertNotEquals(original.inputSha256,report(chunks=arrayOf(a.copy(samples=a.samples.map {it.copy(x=-0.0f)}))).inputSha256)
        assertNotEquals(original.inputSha256,report(recording(listOf(source.copy(rejectedSamples=1))),a).inputSha256)
        assertNotEquals(original.inputSha256,report(recording(listOf(source.copy(provenance=source.provenance!!.copy(zoneId="UTC")))),a).inputSha256)
    }
    @Test fun fullTwelveHourSpanIsStreamedWithOnlyNearbyIntervalsCounted() {
        val analyzer=MotionCoverage(recording())
        // Sparse readings do not invent 12-hour continuity for a 100 ms request.
        for(i in 0..432) analyzer.accept(MotionChunkCodec.encode(chunk(i*100_000_000_000L,sequence=i.toLong())))
        val row=analyzer.finish().streams.single()
        assertEquals(43_200_000_000_000L,row.spanNanos);assertEquals(0L,row.supportedNanos)
        assertEquals(row.spanNanos,row.gapNanos);assertEquals(433L,row.samples)
    }
    @Test fun unfinishedSessionsCannotBePresentedAsAnImmutableCoverageSnapshot() {
        for(state in listOf(CaptureState.RECORDING,CaptureState.PAUSED,CaptureState.INTERRUPTED,CaptureState.DELETING))
            reject {MotionCoverage(recording().copy(session=session.copy(state=state)))}
    }
    @Test fun recordedInterruptionBoundariesBreakOtherwiseNearbyIntervals() {
        for(reason in listOf(CaptureReason.PAUSE,CaptureReason.RESUME,CaptureReason.STOP,CaptureReason.CLOCK_CHANGE,
            CaptureReason.PROCESS_LOSS,CaptureReason.SERVICE_STOP,CaptureReason.STORAGE,CaptureReason.LOW_SPACE,
            CaptureReason.MAX_DURATION,CaptureReason.SENSOR_STALE,CaptureReason.NO_SENSOR)) {
            val r=recording().copy(events=listOf(CaptureEvent(1,session.id,reason,session.updatedAt,150_000_000,source.bootId,null)))
            val row=report(r,chunk(100_000_000,200_000_000,300_000_000)).streams.single()
            assertEquals(reason.name,100_000_000L,row.supportedNanos);assertEquals(100_000_000L,row.gapNanos)
            assertEquals(1L,row.gaps)
        }
    }
    @Test fun anotherBootsControlAndAccuracyEventsDoNotEraseValidIntervals() {
        val events=listOf(CaptureEvent(1,session.id,CaptureReason.CLOCK_CHANGE,session.updatedAt,150_000_000,UUID(0,99),null),
            CaptureEvent(2,session.id,CaptureReason.ACCURACY,session.updatedAt,250_000_000,source.bootId,-1))
        assertEquals(200_000_000L,report(recording().copy(events=events),chunk(100_000_000,200_000_000,300_000_000)).streams.single().supportedNanos)
    }
    @Test fun independentPythonFramingOracleMatchesBothFixtureInputFingerprints() {
        for((version,expected) in listOf(1 to "9304190021d10f69d0b83e1afb20fd5ceb1af4ffb0854f515de134efc102ba6c",
            2 to "96e0297e0fe210486efad09742ac5549ddae89bb9009a178ceffca7fbad4bc24")) {
            val bytes=javaClass.getResourceAsStream("/motion-archive-v$version.solmotion")!!
            val raw=mutableListOf<ByteArray>()
            val r=MotionArchive.read(bytes) {_,b->raw+=b}.recording
            val analyzer=MotionCoverage(r);raw.forEach(analyzer::accept)
            val report=analyzer.finish()
            assertEquals(expected,report.inputSha256);assertEquals(1L,report.samples)
            if(version==1) assertNull(report.streams.single().supportedNanos)
            else assertEquals(0L,report.streams.single().supportedNanos)
        }
    }
    @Test fun mutableCallerListsCannotChangeTheFrozenInputOrRemoveAStreamMidAnalysis() {
        val original=recording();val streams=original.streams.toMutableList();val events=original.events.toMutableList()
        val analyzer=MotionCoverage(original.copy(streams=streams,events=events))
        streams.clear();events.clear()
        val raw=MotionChunkCodec.encode(chunk(0,100_000_000));analyzer.accept(raw)
        assertEquals(report(chunks=arrayOf(chunk(0,100_000_000))),analyzer.finish())
    }
    @Test fun oversizedMetadataCannotAllocateAnUnboundedAnalysisIndex() {
        var streamReads=0
        val streams=object: AbstractList<CaptureStream>() {
            override val size=Int.MAX_VALUE
            override fun get(index: Int): CaptureStream {streamReads++;check(streamReads<=MotionArchive.MAX_STREAMS+1);return source.copy(id=UUID(0,index.toLong()))}
        }
        reject {MotionCoverage(recording(streams))}
        assertTrue(streamReads<=MotionArchive.MAX_STREAMS+1)
        var eventReads=0
        val events=object: AbstractList<CaptureEvent>() {
            override val size=Int.MAX_VALUE
            override fun get(index: Int): CaptureEvent {eventReads++;check(eventReads<=MotionArchive.MAX_EVENTS+1);return recording().events.single().copy(sequence=index+1L)}
        }
        reject {MotionCoverage(recording().copy(events=events))}
        assertTrue(eventReads<=MotionArchive.MAX_EVENTS+1)
    }
}
