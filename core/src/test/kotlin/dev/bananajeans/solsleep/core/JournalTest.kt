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
        reject {JournalArchive.inspect(text.replace("\"version\":2","\"version\":3").byteInputStream())}
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
    @Test fun invalidUnicodeCannotBeSilentlyReplacedDuringExport() {
        reject {r.copy(note="\ud800")};reject {r.copy(tags=setOf("\udfff"))}
        reject {SleepRecordCodec.decode(SleepRecordCodec.encode(r).replace("Dream","\\ud800"))}
        val exact=r.copy(note="e\u0301 é 🌙 \uD83D\uDE34")
        assertEquals(exact,JournalArchive.inspect(bytes(listOf(exact)).inputStream()).records.single())
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
    private val deleted="11111111-1111-4111-8111-111111111111"
    private val deleted2="22222222-2222-4222-8222-222222222222"
    private fun bytes(snapshot: JournalSnapshot)=ByteArrayOutputStream().also {JournalArchive.write(snapshot,it,r.createdAt)}.toByteArray()
    @Test fun independentV2FixturePreservesLiveHistoryAndPortableDeletions() {
        val preview=javaClass.getResourceAsStream("/archives/journal-v2.json")!!.use(JournalArchive::inspect)
        assertEquals(listOf(r),preview.records);assertEquals(setOf(deleted,deleted2),preview.deletedIds)
        assertArrayEquals(javaClass.getResourceAsStream("/archives/journal-v2.json")!!.readBytes(),
            bytes(JournalSnapshot(listOf(r),setOf(deleted2,deleted))))
        assertTrue(javaClass.getResourceAsStream("/archives/journal-v1.json")!!.use(JournalArchive::inspect).deletedIds.isEmpty())
    }
    @Test fun deletionMarkersAreCoveredByChecksumAndRequireExactIdentities() {
        val text=bytes(JournalSnapshot(listOf(r),setOf(deleted))).toString(Charsets.UTF_8)
        reject {JournalArchive.inspect(text.replace(deleted,deleted2).byteInputStream())}
        reject {bytes(JournalSnapshot(listOf(r),setOf(r.id)))}
        listOf("../night","1-1-1-1-1","4BD58C41-0803-4344-BF22-75B65E929280","").forEach {id->
            reject {JournalSnapshot(emptyList(),setOf(id))}
        }
    }
    @Test fun malformedDeletionListsFailEvenWithARecomputedChecksum() {
        val json=kotlinx.serialization.json.Json
        val original=json.parseToJsonElement(bytes(JournalSnapshot(listOf(r),setOf(deleted))).toString(Charsets.UTF_8)) as kotlinx.serialization.json.JsonObject
        val lists=listOf("[42]","[null]","[\"$deleted\",\"$deleted\"]","[\"${r.id}\"]","[\"../night\"]")
        lists.forEach {list->
            val ids=json.parseToJsonElement(list)
            val payload=kotlinx.serialization.json.buildJsonObject {put("records",original.getValue("records"));put("deletedIds",ids)}
            val malformed=kotlinx.serialization.json.JsonObject(original.toMutableMap().apply {
                put("deletedIds",ids);put("sha256",kotlinx.serialization.json.JsonPrimitive(sha256(payload.toString().toByteArray())))
            })
            reject {JournalArchive.inspect(malformed.toString().byteInputStream())}
        }
    }
    @Test fun deletionOnlyArchiveIsNotLostAndEveryTruncationFails() {
        val data=bytes(JournalSnapshot(emptyList(),setOf(deleted)))
        val preview=JournalArchive.inspect(data.inputStream())
        assertTrue(preview.records.isEmpty());assertEquals(setOf(deleted),preview.deletedIds)
        for(i in 0 until data.size) reject {JournalArchive.inspect(data.copyOf(i).inputStream())}
    }
    @Test fun deletionCountLimitFailsExplicitlyWithoutDroppingIdentities() {
        val ids=(1L..JournalArchive.MAX_DELETIONS.toLong()).map {java.util.UUID(0,it).toString()}.toSet()
        val preview=JournalArchive.inspect(bytes(JournalSnapshot(emptyList(),ids)).inputStream())
        assertEquals(ids,preview.deletedIds)
        reject {bytes(JournalSnapshot(emptyList(),ids+deleted))}
    }
    @Test fun writerRevalidatesMutableInputInsteadOfPublishingContradictoryState() {
        val ids=mutableSetOf(deleted);val snapshot=JournalSnapshot(listOf(r),ids)
        ids.add(r.id)
        reject {bytes(snapshot)}
    }

}
