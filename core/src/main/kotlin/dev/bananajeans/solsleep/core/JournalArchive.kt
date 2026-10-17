// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.core

import kotlinx.serialization.json.*
import java.io.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.Instant

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

data class JournalPreview(val createdAt: Instant,val records: List<SleepRecord>,val fingerprint: String) {
    init {validateRecords(records)}
}

internal fun validateRecords(records: List<SleepRecord>) {
    require(records.size<=JournalArchive.MAX_REVISIONS)
    records.groupBy {it.id}.values.forEach(::validateHistory)
}

/** Plaintext native journal format. All original revisions are retained. No sensor/audio payloads. */
object JournalArchive {
    const val MAX_BYTES=8*1024*1024
    const val MAX_REVISIONS=10_000
    fun write(records: List<SleepRecord>,out: OutputStream,createdAt: Instant=Instant.now()) {
        val sorted=records.sortedWith(compareBy<SleepRecord> {it.id}.thenBy {it.revision})
        validateRecords(sorted)
        val array=JsonArray(sorted.map(SleepRecordCodec::element))
        array.forEach {SleepRecordCodec.decode(it.jsonObject)}
        val bytes=buildJsonObject {
            put("format","solsleep-journal");put("version",1);put("createdAt",createdAt.toString())
            put("sha256",sha256(array.toString().toByteArray()));put("records",array)
        }.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size<=MAX_BYTES)
        out.write(bytes);out.flush()
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
        require(j.keys==setOf("format","version","createdAt","sha256","records"))
        require(j.text("format")=="solsleep-journal" && j.number("version")==1L)
        val array=j.getValue("records").jsonArray
        require(array.size<=MAX_REVISIONS && j.text("sha256")==sha256(array.toString().toByteArray(Charsets.UTF_8)))
        return JournalPreview(Instant.parse(j.text("createdAt")),array.map {SleepRecordCodec.decode(it.jsonObject)},sha256(bytes))
    }
}

internal fun sha256(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") {"%02x".format(it.toInt() and 255)}
