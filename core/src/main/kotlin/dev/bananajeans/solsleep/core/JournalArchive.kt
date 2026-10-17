// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.core

import kotlinx.serialization.json.*
import java.io.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

object SleepRecordCodec {
    fun encode(r: SleepRecord)=element(r).toString()
    fun decode(text: String)=decode(Json.parseToJsonElement(text).jsonObject)
    internal fun element(r: SleepRecord)=buildJsonObject {
        put("version",1);put("id",r.id);put("revision",r.revision)
        put("createdAt",r.createdAt.toString());put("modifiedAt",r.modifiedAt.toString())
        put("start",r.start.toString());put("end",r.end.toString());put("zone",r.zone)
        put("note",r.note);put("rating",r.rating?.let(::JsonPrimitive) ?: JsonNull)
        put("tags",JsonArray(r.tags.sorted().map(::JsonPrimitive)))
    }
    internal fun decode(j: JsonObject): SleepRecord {
        require(j.keys==setOf("version","id","revision","createdAt","modifiedAt","start","end","zone","note","rating","tags"))
        require(j.number("version")==1L)
        val tags=j.getValue("tags").jsonArray.map {it.jsonPrimitive.also {p->require(p.isString)}.content}
        require(tags.distinct().size==tags.size)
        return SleepRecord(j.text("id"),j.number("revision"),Instant.parse(j.text("createdAt")),
            Instant.parse(j.text("modifiedAt")),Instant.parse(j.text("start")),Instant.parse(j.text("end")),
            j.text("zone"),j.text("note"),if(j["rating"]==JsonNull) null else j.integer("rating"),tags.toSet())
    }
}

/** Complete logical journal state. A deleted identity cannot also have a live history. */
data class JournalSnapshot(val records: List<SleepRecord>,val deletedIds: Set<String> = emptySet()) {
    init {
        records.groupBy {it.id}.values.forEach {validateHistory(it.sortedBy {record->record.revision})}
        deletedIds.forEach {require(UUID.fromString(it).toString()==it)}
        require(records.none {it.id in deletedIds})
    }
}

data class JournalPreview(val createdAt: Instant,val records: List<SleepRecord>,val fingerprint: String,
    val deletedIds: Set<String> = emptySet()) {
    init {validateRecords(records);require(deletedIds.size<=JournalArchive.MAX_DELETIONS);JournalSnapshot(records,deletedIds)}
}

data class JournalRestoreResult(val nightsAdded: Int,val nightsSkipped: Int,val markersAdded: Int,
    val markersSkipped: Int,val localNightsKept: Int)

class JournalExportLimitException : IllegalArgumentException("Journal exceeds bounded export capacity")

internal fun validateRecords(records: List<SleepRecord>) {
    require(records.size<=JournalArchive.MAX_REVISIONS)
    records.groupBy {it.id}.values.forEach(::validateHistory)
}

/** Plaintext native journal format. All original revisions are retained. No sensor/audio payloads. */
object JournalArchive {
    const val MAX_BYTES=8*1024*1024
    const val MAX_REVISIONS=10_000
    const val MAX_DELETIONS=10_000
    fun write(records: List<SleepRecord>,out: OutputStream,createdAt: Instant=Instant.now()) =
        write(JournalSnapshot(records),out,createdAt)

    /** Writers emit v2; readers also accept v1 archives, which contain no deletion markers. */
    fun write(snapshot: JournalSnapshot,out: OutputStream,createdAt: Instant=Instant.now()) {
        if(snapshot.records.size>MAX_REVISIONS || snapshot.deletedIds.size>MAX_DELETIONS) throw JournalExportLimitException()
        JournalSnapshot(snapshot.records,snapshot.deletedIds)
        val sorted=snapshot.records.sortedWith(compareBy<SleepRecord> {it.id}.thenBy {it.revision})
        validateRecords(sorted)
        // Encode one bounded record at a time. Never first materialize a potentially huge array/string.
        val buffer=object: ByteArrayOutputStream() {
            override fun write(b: Int) {
                if(count>=MAX_BYTES) throw JournalExportLimitException()
                super.write(b)
            }
            override fun write(b: ByteArray,off: Int,len: Int) {
                if(len>MAX_BYTES-count) throw JournalExportLimitException()
                super.write(b,off,len)
            }
        }
        fun append(text: String) {buffer.write(text.toByteArray(Charsets.UTF_8))}
        append("{\"records\":[")
        sorted.forEachIndexed {index,record->
            val encoded=SleepRecordCodec.encode(record)
            SleepRecordCodec.decode(encoded)
            if(index>0) append(",")
            append(encoded)
        }
        append("],\"deletedIds\":[")
        snapshot.deletedIds.sorted().forEachIndexed {index,id->
            if(index>0) append(",")
            append(JsonPrimitive(id).toString())
        }
        append("]}")
        val payloadBytes=buffer.toByteArray()
        val header=buildJsonObject {
            put("format","solsleep-journal");put("version",2);put("createdAt",createdAt.toString())
            put("sha256",sha256(payloadBytes))
        }.toString().dropLast(1).toByteArray(Charsets.UTF_8)
        if(header.size.toLong()+payloadBytes.size>MAX_BYTES) throw JournalExportLimitException()
        out.write(header);out.write(','.code);out.write(payloadBytes,1,payloadBytes.size-1);out.flush()
    }
    fun inspect(input: InputStream): JournalPreview {
        val buffer=ByteArrayOutputStream()
        val block=ByteArray(8192)
        while(true) {
            val n=input.read(block,0,minOf(block.size,MAX_BYTES+1-buffer.size()))
            if(n<0) break
            require(n>0);buffer.write(block,0,n);require(buffer.size()<=MAX_BYTES)
        }
        val bytes=buffer.toByteArray()
        val text=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        var depth=0;var quoted=false;var escaped=false
        text.forEach {c->
            if(quoted) {if(escaped) escaped=false else if(c=='\\') escaped=true else if(c=='"') quoted=false}
            else when(c) {'"'->quoted=true;'{','['->{depth++;require(depth<=8)};'}',']'->{depth--;require(depth>=0)}}
        }
        require(depth==0 && !quoted)
        val j=Json.parseToJsonElement(text).jsonObject
        val version=j.number("version")
        require(j.text("format")=="solsleep-journal" && version in 1L..2L)
        val keys=setOf("format","version","createdAt","sha256","records")
        require(j.keys==if(version==1L) keys else keys+"deletedIds")
        val array=j.getValue("records").jsonArray
        val deleted=if(version==1L) JsonArray(emptyList()) else j.getValue("deletedIds").jsonArray
        require(array.size<=MAX_REVISIONS && deleted.size<=MAX_DELETIONS)
        val checksumInput=if(version==1L) array else payload(array,deleted)
        require(j.text("sha256")==sha256(checksumInput.toString().toByteArray(Charsets.UTF_8)))
        val ids=deleted.map {it.jsonPrimitive.also {p->require(p.isString)}.content}
        require(ids.distinct().size==ids.size)
        return JournalPreview(Instant.parse(j.text("createdAt")),array.map {SleepRecordCodec.decode(it.jsonObject)},sha256(bytes),ids.toSet())
    }

    private fun payload(records: JsonArray,deletedIds: JsonArray)=buildJsonObject {
        put("records",records);put("deletedIds",deletedIds)
    }
}

internal fun sha256(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") {"%02x".format(it.toInt() and 255)}
