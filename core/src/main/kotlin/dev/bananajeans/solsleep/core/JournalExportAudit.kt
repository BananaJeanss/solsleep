// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.core

import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CancellationException

enum class JournalExportStage { STARTED, WRITTEN, VERIFIED, FAILED_WRITE, UNVERIFIED }

data class JournalExportAttempt(val id: String,val sequence: Long,val startedAt: Instant,val updatedAt: Instant,
    val displayName: String?,val stage: JournalExportStage,val fingerprint: String?) {
    init {
        require(UUID.fromString(id).toString()==id && sequence>0)
        require(startedAt.nano%1_000_000==0 && updatedAt.nano%1_000_000==0 && updatedAt>=startedAt)
        require(listOf(startedAt,updatedAt).all {it.atOffset(java.time.ZoneOffset.UTC).year in 1900..9999})
        require(displayName==null || (hasValidUnicode(displayName) && displayName.isNotBlank() && displayName.length<=256 && displayName.none {it<' '}))
        require((stage==JournalExportStage.VERIFIED)==(fingerprint!=null))
        require(fingerprint==null || fingerprint.matches(Regex("[0-9a-f]{64}")))
    }
}

data class JournalExportSummary(val lastAttempt: JournalExportAttempt?=null,val lastVerified: JournalExportAttempt?=null) {
    init {
        require(lastVerified==null || (lastVerified.stage==JournalExportStage.VERIFIED && lastAttempt!=null && lastAttempt.sequence>=lastVerified.sequence))
    }
}

interface JournalExportAudit {
    fun start(displayName: String?,at: Instant): String
    fun written(id: String,at: Instant)
    fun verified(id: String,fingerprint: String,at: Instant)
    fun failed(id: String,at: Instant)
}

sealed interface AuditedJournalResult {
    val auditRecorded: Boolean
    data class Verified(val preview: JournalPreview,override val auditRecorded: Boolean): AuditedJournalResult
    data class Failed(val error: Exception,override val auditRecorded: Boolean): AuditedJournalResult
}

/** Audit failure must never prevent a user recovering a verified copy of their journal. */
object AuditedJournalExport {
    fun run(snapshot: ()->JournalSnapshot,openOutput: ()->OutputStream,openInput: ()->InputStream,
        audit: JournalExportAudit,displayName: String?,
        clock: ()->Instant = {Instant.now().truncatedTo(ChronoUnit.MILLIS)}): AuditedJournalResult {
        var recorded=true
        var id: String?=null
        fun note(block: ()->Unit) {
            if(!recorded) return
            try {block()} catch(c: CancellationException) {throw c} catch(_: Exception) {recorded=false}
        }
        val started=clock()
        note {id=audit.start(displayName,started)}
        return try {
            val preview=JournalDocument.exportVerified(snapshot(),openOutput,{
                note {audit.written(requireNotNull(id),clock())}
                openInput()
            },started)
            note {audit.verified(requireNotNull(id),preview.fingerprint,clock())}
            AuditedJournalResult.Verified(preview,recorded)
        } catch(c: CancellationException) {
            throw c // A nonterminal persisted attempt remains explicitly unverified after interruption.
        } catch(e: Exception) {
            note {audit.failed(requireNotNull(id),clock())}
            AuditedJournalResult.Failed(e,recorded)
        }
    }
}
