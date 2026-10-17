// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.core

import java.time.*
import java.util.UUID

/** A user's report, never an inferred physiological sleep stage or measured sleep duration. */
data class SleepRecord(
    val id: String = UUID.randomUUID().toString(),
    val revision: Long = 1,
    val createdAt: Instant,
    val modifiedAt: Instant,
    val start: Instant,
    val end: Instant,
    val zone: String,
    val note: String = "",
    val rating: Int? = null,
    val tags: Set<String> = emptySet(),
) {
    init {
        require(UUID.fromString(id).toString()==id) {"Invalid session identity"}
        require(revision>0)
        listOf(createdAt,modifiedAt,start,end).forEach {
            require(it.nano%1_000_000==0 && it.atOffset(ZoneOffset.UTC).year in 1900..9999)
        }
        require(modifiedAt>=createdAt && end>start && Duration.between(start,end)<=Duration.ofDays(7))
        ZoneId.of(zone)
        require(hasValidUnicode(note) && note.length<=16_000 && '\u0000' !in note)
        require(rating==null || rating in 1..5)
        require(tags.size<=32 && tags.all {hasValidUnicode(it) && it.isNotBlank() && it==it.trim() && it.length<=40 && '\u0000' !in it})
    }
    val reportedDuration: Duration get()=Duration.between(start,end)
}

/** Consecutive, immutable revisions. Original identity and creation time cannot change. */
fun validateHistory(history: List<SleepRecord>) {
    require(history.isNotEmpty())
    val first=history.first()
    history.forEachIndexed {i,r->
        require(r.id==first.id && r.createdAt==first.createdAt && r.revision==i.toLong()+1)
        if(i>0) require(r.modifiedAt>=history[i-1].modifiedAt)
    }
}

class RevisionConflict : IllegalStateException("Record changed; reload before saving")

/** Never silently shift a reported time through a DST gap or choose an overlap occurrence. */
fun reportedInstant(local: LocalDateTime,zone: ZoneId,selectedOffset: ZoneOffset?=null): Instant {
    val offsets=zone.rules.getValidOffsets(local)
    require(offsets.isNotEmpty()) {"Time does not exist in this time zone"}
    val offset=if(offsets.size==1) offsets.single() else requireNotNull(selectedOffset) {"Choose the time's UTC offset"}
    require(offset in offsets)
    return local.toInstant(offset)
}

/** Reject unpaired UTF-16 surrogates before UTF-8 encoding could replace them silently. */
fun hasValidUnicode(value: String): Boolean {
    var index=0
    while(index<value.length) {
        val c=value[index++]
        if(c.isHighSurrogate()) {
            if(index>=value.length || !value[index++].isLowSurrogate()) return false
        } else if(c.isLowSurrogate()) return false
    }
    return true
}
