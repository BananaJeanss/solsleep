// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.core

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CancellationException

enum class MotionExportStage { STARTED, WRITTEN, VERIFIED, FAILED_WRITE, UNVERIFIED }

/** Local destination identity only. No URI capability, tokens or persisted grant. */
data class MotionExportDestination(val uriSha256: String,val authority: String?,val displayName: String?) {
    init {
        require(uriSha256.matches(Regex("[0-9a-f]{64}")))
        require(authority==null || authority.matches(Regex("[A-Za-z0-9._-]{1,255}")))
        require(displayName==null || (hasValidUnicode(displayName) && displayName.isNotBlank() &&
            displayName.length<=256 && displayName.none {it<' ' || it=='\u007f'}))
    }
}

data class MotionExportAttempt(val id: UUID,val sessionId: UUID,val sequence: Long,
    val startedAt: Instant,val updatedAt: Instant,val destination: MotionExportDestination?,
    val stage: MotionExportStage,val fingerprint: String?) {
    init {
        require(sequence>0 && startedAt.nano%1_000_000==0 && updatedAt.nano%1_000_000==0 && updatedAt>=startedAt)
        require(listOf(startedAt,updatedAt).all {it.atOffset(java.time.ZoneOffset.UTC).year in 1900..9999})
        require((stage==MotionExportStage.VERIFIED)==(fingerprint!=null))
        require(fingerprint==null || fingerprint.matches(Regex("[0-9a-f]{64}")))
    }
}

data class MotionExportSummary(val sessionId: UUID,val lastAttempt: MotionExportAttempt?=null,
    val lastVerified: MotionExportAttempt?=null) {
    init {
        require(lastAttempt==null || lastAttempt.sessionId==sessionId)
        require(lastVerified==null || (lastVerified.sessionId==sessionId && lastVerified.stage==MotionExportStage.VERIFIED &&
            lastAttempt!=null && lastAttempt.sequence>=lastVerified.sequence))
    }
}

interface MotionExportAudit {
    fun start(sessionId: UUID,destination: MotionExportDestination?,at: Instant): UUID
    fun written(id: UUID,at: Instant)
    fun verified(id: UUID,fingerprint: String,at: Instant)
    fun failed(id: UUID,at: Instant)
}

sealed interface AuditedMotionResult {
    val auditRecorded: Boolean
    data class Verified(val preview: MotionArchivePreview,override val auditRecorded: Boolean): AuditedMotionResult
    data class Failed(val error: Exception,override val auditRecorded: Boolean): AuditedMotionResult
}

/** The transfer invokes written only after successful output close, before provider readback.
 * A damaged receipt store must never prevent a verified recovery copy of original observations.
 */
object AuditedMotionExport {
    fun run(sessionId: UUID,destination: MotionExportDestination?,audit: MotionExportAudit,
        clock: ()->Instant={Instant.now().truncatedTo(ChronoUnit.MILLIS)},
        transfer: (written: ()->Unit)->MotionArchivePreview): AuditedMotionResult {
        var recorded=true;var id: UUID?=null;var written=false
        fun note(block: ()->Unit) {
            if(!recorded) return
            try {block()} catch(c: CancellationException) {throw c} catch(_: Exception) {recorded=false}
        }
        note {id=audit.start(sessionId,destination,clock())}
        return try {
            val preview=transfer {
                check(!written) {"Output completion already observed"};written=true
                note {audit.written(requireNotNull(id),clock())}
            }
            check(written && preview.recording.session.id==sessionId) {"Transfer returned an unrelated or unclosed recording"}
            note {audit.verified(requireNotNull(id),preview.fingerprint,clock())}
            AuditedMotionResult.Verified(preview,recorded)
        } catch(c: CancellationException) {throw c}
        catch(error: Exception) {
            note {audit.failed(requireNotNull(id),clock())}
            AuditedMotionResult.Failed(error,recorded)
        }
    }
}
