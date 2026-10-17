// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.core

import java.time.Instant
import java.util.UUID

enum class CaptureState { PREPARING, RECORDING, PAUSED, INTERRUPTED, COMPLETED, FAILED, DELETING }
enum class CaptureReason { START, RESUME, PAUSE, STOP, PROCESS_LOSS, SERVICE_STOP, NO_SENSOR, STORAGE,
    LOW_SPACE, MAX_DURATION, SENSOR_STALE, INVALID_SAMPLE, CLOCK_CHANGE, SENSOR_GAP, ACCURACY, DELETE, SETUP }

data class CaptureSession(val id: UUID,val requestId: UUID,val ownerId: UUID,val state: CaptureState,
    val startedAt: Instant,val updatedAt: Instant,val revision: Long) {
    init {
        require(revision>=1)
        listOf(startedAt,updatedAt).forEach {require(it.nano%1_000_000==0 && it>=Instant.parse("1900-01-01T00:00:00Z") && it<Instant.parse("10000-01-01T00:00:00Z"))}
    }
    val terminal get()=state in setOf(CaptureState.COMPLETED,CaptureState.FAILED,CaptureState.DELETING)
}

/** State is durable intent/last known acquisition, never a claim that a dead process is recording. */
object CaptureTransitions {
    fun permits(from: CaptureState,to: CaptureState): Boolean=when(from) {
        CaptureState.PREPARING->to in setOf(CaptureState.RECORDING,CaptureState.INTERRUPTED,CaptureState.FAILED,CaptureState.COMPLETED)
        CaptureState.RECORDING->to in setOf(CaptureState.PAUSED,CaptureState.INTERRUPTED,CaptureState.COMPLETED)
        CaptureState.PAUSED,CaptureState.INTERRUPTED->to in setOf(CaptureState.PREPARING,CaptureState.COMPLETED)
        CaptureState.COMPLETED,CaptureState.FAILED->to==CaptureState.DELETING
        CaptureState.DELETING->false
    }
}

object MotionCapturePolicy {
    const val RESERVE_BYTES=64*1024*1024L
    const val SESSION_BYTES=64*1024*1024L
    const val MAX_NANOS=12*60*60*1_000_000_000L
    const val STALE_NANOS=15_000_000_000L
    fun interruption(anchorWallMs: Long,anchorNanos: Long,lastSampleNanos: Long?,nowWallMs: Long,nowNanos: Long): CaptureReason? {
        require(anchorNanos>=0 && nowNanos>=anchorNanos)
        return when {
            nowNanos-anchorNanos>=MAX_NANOS->CaptureReason.MAX_DURATION
            nowNanos-(lastSampleNanos ?: anchorNanos)>=STALE_NANOS->CaptureReason.SENSOR_STALE
            kotlin.math.abs((nowWallMs-anchorWallMs)-(nowNanos-anchorNanos)/1_000_000)>2000->CaptureReason.CLOCK_CHANGE
            else->null
        }
    }
    fun hasSpace(available: Long,publishedBytes: Long,nextBytes: Int=0): Boolean {
        require(publishedBytes>=0 && nextBytes>=0)
        return available>=RESERVE_BYTES && publishedBytes<SESSION_BYTES && nextBytes<=SESSION_BYTES-publishedBytes
    }
}

data class CaptureSource(val name: String,val vendor: String,val version: Int,val resolution: Float,
    val maximumRange: Float,val wakeUp: Boolean) {
    init {
        require(name.length in 1..512 && vendor.length<=512 && '\u0000' !in name && '\u0000' !in vendor)
        require(resolution.isFinite() && resolution>=0 && maximumRange.isFinite() && maximumRange>0)
    }
}

/** One bounded segment. Publish-before-ack, with exact samples retained in memory on write failure.
 * A new segment/anchor is required after pause, process loss or a wall-clock discontinuity.
 */
class MotionCaptureBuffer(private val sessionId: UUID,private val streamId: UUID,private val bootId: UUID,
    private val anchorWall: Instant,private val anchorElapsedNanos: Long,
    private val publish: (MotionChunk)->Unit) {
    companion object {
        const val PERIOD_MICROS=100_000
        const val CHECKPOINT_NANOS=4_000_000_000L
        const val GAP_NANOS=2_000_000_000L
        const val MAX_SAMPLES=128
    }
    private val samples=ArrayList<MotionSample>(MAX_SAMPLES)
    var sequence=0L;private set
    var committedSamples=0L;private set
    var rejectedSamples=0L;private set
    var lastElapsedNanos: Long?=null;private set
    var firstElapsedNanos: Long?=null;private set
    var gapCount=0L;private set
    init {require(anchorElapsedNanos>=0 && anchorWall.nano%1_000_000==0)}

    /** Null means valid. Invalid/late/duplicate samples remain missing, never replaced with zero. */
    fun append(sample: MotionSample): CaptureReason? {
        if(sample.elapsedNanos<anchorElapsedNanos || sample.elapsedNanos<= (lastElapsedNanos ?: -1L) ||
            !sample.x.isFinite() || !sample.y.isFinite() || !sample.z.isFinite()) {
            rejectedSamples++;return CaptureReason.INVALID_SAMPLE
        }
        // A failed flush must not admit an unbounded tail or overwrite the pending samples.
        if(samples.size==MAX_SAMPLES) flush()
        val gap=lastElapsedNanos?.let {sample.elapsedNanos-it>GAP_NANOS} ?: false
        if(gap) gapCount++
        if(firstElapsedNanos==null) firstElapsedNanos=sample.elapsedNanos
        lastElapsedNanos=sample.elapsedNanos;samples+=sample
        if(samples.size==MAX_SAMPLES || sample.elapsedNanos-samples.first().elapsedNanos>=CHECKPOINT_NANOS) flush()
        return if(gap) CaptureReason.SENSOR_GAP else null
    }
    fun flush() {
        if(samples.isEmpty()) return
        val chunk=MotionChunk(sessionId,streamId,bootId,sequence,anchorWall,anchorElapsedNanos,samples.toList())
        publish(chunk) // An identical retry is supported by MotionChunkStore, even after rename.
        committedSamples+=samples.size;sequence++;samples.clear()
    }
}
