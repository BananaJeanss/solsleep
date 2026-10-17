// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.core

import org.junit.Test
import org.junit.Assert.*
import java.io.*
import java.time.*

class JournalTest {
    private val r=SleepRecord("4bd58c41-0803-4344-bf22-75b65e929280",1,
        Instant.parse("2026-10-17T08:00:00Z"),Instant.parse("2026-10-17T08:00:00Z"),
        Instant.parse("2026-10-16T20:00:00Z"),Instant.parse("2026-10-17T05:15:00Z"),
        "Europe/Tallinn","Dream 🌙\nQuoted \"line\", comma",null,setOf("tea","night shift"))
    private fun bytes(records: List<SleepRecord> = listOf(r))=ByteArrayOutputStream().also {JournalArchive.write(records,it,r.createdAt)}.toByteArray()
    private fun reject(block: ()->Unit) {try {block();fail("Must reject")} catch(_: Exception) {}}
    @Test fun archivePreservesUnicodeNullRatingAndAllOriginalRevisions() {
        val revised=r.copy(revision=2,modifiedAt=r.modifiedAt.plusSeconds(1),note="Corrected",rating=5,start=r.start.plusSeconds(60))
        val preview=JournalArchive.inspect(bytes(listOf(revised,r)).inputStream())
        assertEquals(listOf(r,revised),preview.records)
        assertEquals(Duration.ofMinutes(555),preview.records.first().reportedDuration)
        assertEquals(preview.fingerprint,JournalArchive.inspect(bytes(listOf(r,revised)).inputStream()).fingerprint)
    }
    @Test fun everyTruncationFailsBeforeAStoreIsTouched() {
        val data=bytes()
        for(i in 0 until data.size) reject {JournalArchive.inspect(data.copyOf(i).inputStream())}
    }
    @Test fun digestAndVersionAndUnknownFieldsAreEnforced() {
        val text=bytes().toString(Charsets.UTF_8)
        reject {JournalArchive.inspect(text.replace("Dream","Changed").byteInputStream())}
        reject {JournalArchive.inspect(text.replace("\"version\":1","\"version\":2").byteInputStream())}
        reject {JournalArchive.inspect(text.replace("\"format\":","\"extra\":true,\"format\":").byteInputStream())}
        reject {SleepRecordCodec.decode(SleepRecordCodec.encode(r).replace("\"note\":","\"future\":1,\"note\":"))}
    }
    @Test fun gapsDuplicatesAndChangedCreationIdentityAreRejected() {
        reject {bytes(listOf(r,r))}
        reject {bytes(listOf(r.copy(revision=2)))}
        reject {bytes(listOf(r,r.copy(revision=2,createdAt=r.createdAt.minusSeconds(1))))}
        reject {bytes(listOf(r,r.copy(revision=2,modifiedAt=r.modifiedAt.minusSeconds(1),createdAt=r.createdAt.minusSeconds(1))))}
    }
    @Test fun invalidIntervalsRatingsPrecisionAndTagsCannotBeSaved() {
        reject {r.copy(end=r.start)};reject {r.copy(end=r.start.plus(Duration.ofDays(8)))}
        reject {r.copy(rating=0)};reject {r.copy(rating=6)}
        reject {r.copy(start=r.start.plusNanos(1))};reject {r.copy(tags=setOf(" spaced "))}
        reject {r.copy(note="x".repeat(16001))};reject {r.copy(id="../record")}
    }
    @Test fun sizeDepthUtf8AndReadFailureAreBounded() {
        reject {JournalArchive.inspect(ByteArray(JournalArchive.MAX_BYTES+1) {32}.inputStream())}
        reject {JournalArchive.inspect(("[".repeat(9)+"]".repeat(9)).byteInputStream())}
        reject {JournalArchive.inspect(byteArrayOf(0xc3.toByte(),0x28).inputStream())}
        reject {JournalArchive.inspect(object: InputStream() {override fun read(): Int=throw IOException("disk")})}
    }
    @Test fun outputFailureIsNeverReportedAsACompleteArchive() {
        reject {JournalArchive.write(listOf(r),object: OutputStream() {override fun write(b: Int) {throw IOException("full")}})}
    }
    @Test fun dstOverlapRequiresExplicitOccurrenceAndGapCannotBeShifted() {
        val zone=ZoneId.of("Europe/Tallinn")
        val overlap=LocalDateTime.parse("2026-10-25T03:30:00")
        reject {reportedInstant(overlap,zone)}
        assertEquals(Instant.parse("2026-10-25T00:30:00Z"),reportedInstant(overlap,zone,ZoneOffset.ofHours(3)))
        assertEquals(Instant.parse("2026-10-25T01:30:00Z"),reportedInstant(overlap,zone,ZoneOffset.ofHours(2)))
        reject {reportedInstant(LocalDateTime.parse("2026-03-29T03:30:00"),zone)}
    }
    @Test fun independentFixtureDoesNotDependOnTheWriter() {
        val preview=javaClass.getResourceAsStream("/archives/journal-v1.json")!!.use(JournalArchive::inspect)
        assertEquals(r,preview.records.single())
    }
}
