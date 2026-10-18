// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.core

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

data class StreamCoverage(val streamId: UUID,val samples: Long,val chunks: Long,
    val firstNanos: Long?,val lastNanos: Long?,val supportedNanos: Long?,val gapNanos: Long?,
    val thresholdNanos: Long?,val gaps: Long,val missingChunks: Long,val missingIndexedSamples: Long) {
    val spanNanos: Long? get()=firstNanos?.let {requireNotNull(lastNanos)-it}
}
data class MotionCoverageReport(val policyVersion: Int,val inputSha256: String,
    val streams: List<StreamCoverage>,val overlappingStreams: Boolean) {
    val samples: Long get()=streams.sumOf {it.samples}
    val incomplete: Boolean get()=overlappingStreams || streams.any {it.missingChunks>0 || it.missingIndexedSamples>0}
}

/** Rebuildable data continuity, never sleep duration. Accept canonical stream/sequence order.
 * No interval is inferred outside first..last, across segments or a missing chunk.
 * Unknown original cadence means unknown supported duration, even for an old 10 Hz build.
 */
class MotionCoverage(value: CaptureRecording) {
    companion object {const val POLICY_VERSION=1;const val MAX_JOIN_NANOS=2_000_000_000L}
    // Copy bounded metadata before maps/hashes. Caller-owned mutable lists cannot
    // change the report after its input identity has been established.
    private val recording=value.copy(streams=value.streams.take(MotionArchive.MAX_STREAMS+1),
        events=value.events.take(MotionArchive.MAX_EVENTS+1)).also {it.validate()}
    private class Counter(val stream: CaptureStream,val boundaries: LongArray) {
        val threshold=stream.provenance?.let {minOf(MAX_JOIN_NANOS,it.requestedPeriodMicros*3000L)}
        var first: Long?=null;var last: Long?=null;var sequence=-1L
        var samples=0L;var chunks=0L;var supported=0L;var gaps=0L;var missing=0L
        private var boundaryIndex=0
        fun interrupted(previous: Long,next: Long): Boolean {
            while(boundaryIndex<boundaries.size && boundaries[boundaryIndex]<=previous) boundaryIndex++
            return boundaryIndex<boundaries.size && boundaries[boundaryIndex]<=next
        }
        fun result()=StreamCoverage(stream.id,samples,chunks,first,last,threshold?.let {supported},
            threshold?.let {if(first==null) 0 else requireNotNull(last)-requireNotNull(first)-supported},
            threshold,gaps,missing,(stream.committedSamples-samples).coerceAtLeast(0))
    }
    private val interruptions=recording.events.filter {it.reason in setOf(CaptureReason.PAUSE,CaptureReason.RESUME,
        CaptureReason.STOP,CaptureReason.PROCESS_LOSS,CaptureReason.SERVICE_STOP,CaptureReason.CLOCK_CHANGE,
        CaptureReason.STORAGE,CaptureReason.LOW_SPACE,CaptureReason.MAX_DURATION,CaptureReason.SENSOR_STALE,
        CaptureReason.NO_SENSOR)}.groupBy {it.bootId}.mapValues {(_,events)->events.map {it.elapsedNanos}.distinct().sorted().toLongArray()}
    private val counters=recording.streams.associate {it.id to Counter(it,interruptions[it.bootId] ?: longArrayOf())}
    private val digest=MessageDigest.getInstance("SHA-256")
    private var previousStream: UUID?=null
    private var rawBytes=0L;private var chunkCount=0L;private var finished=false
    init {
        recording.validate()
        digest.update("SolSleep observed coverage\u0000".toByteArray())
        digest.update(intBytes(POLICY_VERSION));digest.update(intBytes(MotionArchive.formatVersion(recording)))
        val metadata=MotionArchive.encodeMetadata(recording)
        digest.update(intBytes(metadata.size));digest.update(metadata)
    }
    fun accept(bytes: ByteArray) {
        check(!finished)
        require(chunkCount<MotionArchive.MAX_CHUNKS && bytes.size<=MotionArchive.MAX_RAW_BYTES-rawBytes)
        val chunk=MotionChunkCodec.decode(bytes)
        require(chunk.sessionId==recording.session.id && chunk.sequence<Long.MAX_VALUE)
        val counter=requireNotNull(counters[chunk.streamId]) {"Missing source metadata"}
        val source=counter.stream
        require(chunk.bootId==source.bootId && chunk.anchorWall==source.wall && chunk.anchorElapsedNanos==source.elapsedNanos)
        require(previousStream?.let {it<=chunk.streamId}!=false) {"Noncanonical stream order"}
        require(chunk.sequence>counter.sequence && chunk.samples.first().elapsedNanos>=source.elapsedNanos &&
            chunk.samples.first().elapsedNanos>(counter.last ?: -1)) {"Regressed sequence or sample time"}
        val missing=chunk.sequence-counter.sequence-1
        counter.missing+=missing
        var previous=counter.last.takeIf {missing==0L}
        if(missing>0 && counter.last!=null) counter.gaps++
        chunk.samples.forEach {sample->
            if(counter.first==null) counter.first=sample.elapsedNanos
            previous?.let {time->
                val delta=sample.elapsedNanos-time
                if(counter.threshold!=null && delta<=counter.threshold && !counter.interrupted(time,sample.elapsedNanos)) counter.supported+=delta
                else if(counter.threshold!=null) counter.gaps++
            }
            previous=sample.elapsedNanos;counter.last=sample.elapsedNanos;counter.samples++
        }
        counter.sequence=chunk.sequence;counter.chunks++
        previousStream=chunk.streamId;rawBytes+=bytes.size;chunkCount++
        digest.update(intBytes(bytes.size));digest.update(bytes)
    }
    fun finish(): MotionCoverageReport {
        check(!finished);finished=true
        var overlap=false
        counters.values.filter {it.first!=null}.groupBy {it.stream.bootId}.values.forEach {group->
            var latest=-1L
            group.sortedBy {it.first}.forEach {counter->
                if(requireNotNull(counter.first)<=latest) overlap=true
                latest=maxOf(latest,requireNotNull(counter.last))
            }
        }
        return MotionCoverageReport(POLICY_VERSION,digest.digest().joinToString("") {"%02x".format(it)},
            recording.streams.map {counters.getValue(it.id).result()},overlap)
    }
    private fun intBytes(value: Int)=ByteBuffer.allocate(4).putInt(value).array()
}
