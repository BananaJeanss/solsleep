// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.core

import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.*

/** One shared, versioned logical representation for storage and portable records. */
object AlarmRuleCodec {
    private val common = setOf("version","id","time","label","enabled","zone","skip","vibrate","ramp","snooze","limit","revision","kind")
    fun encode(rule: AlarmRule): String = element(rule).toString()
    fun decode(value: String): AlarmRule = decode(Json.parseToJsonElement(value).jsonObject)
    internal fun element(r: AlarmRule) = buildJsonObject {
        put("version",1);put("id",r.id);put("time",r.time.toString());put("label",r.label)
        put("enabled",r.enabled);put("zone",r.zoneId?.let(::JsonPrimitive) ?: JsonNull)
        put("skip",r.skipDate?.toString()?.let(::JsonPrimitive) ?: JsonNull)
        put("vibrate",r.vibrate);put("ramp",r.rampSeconds);put("snooze",r.snoozeMinutes)
        put("limit",r.snoozeLimit);put("revision",r.revision)
        when(val repeat=r.repeat) {
            is Repeat.Once -> {put("kind","once");put("date",repeat.date.toString())}
            is Repeat.Weekly -> {put("kind","weekly");put("days",mask(repeat.days))}
            is Repeat.EveryDays -> {put("kind","interval");put("date",repeat.anchor.toString());put("interval",repeat.interval)}
            is Repeat.AlternatingWeeks -> {put("kind","alternating");put("days",mask(repeat.days));put("odd",repeat.odd)}
        }
    }
    internal fun decode(j: JsonObject): AlarmRule {
        require(j.number("version")==1L) {"Unsupported alarm version"}
        val kind=j.text("kind")
        val extra=when(kind) {
            "once" -> setOf("date")
            "weekly" -> setOf("days")
            "interval" -> setOf("date","interval")
            "alternating" -> setOf("days","odd")
            else -> error("Unsupported recurrence")
        }
        // Unknown fields cannot be silently discarded during a restore or downgrade.
        require(j.keys.all {it in common || it in extra}) {"Unsupported alarm fields"}
        fun date()=LocalDate.parse(j.text("date")).also {require(it.year in 1900..9999)}
        fun days(): Set<DayOfWeek> {
            val mask=j.number("days");require(mask in 1..127)
            return DayOfWeek.entries.filter {mask and (1L shl (it.value-1))!=0L}.toSet()
        }
        val repeat=when(kind) {
            "once" -> Repeat.Once(date())
            "weekly" -> Repeat.Weekly(days())
            "interval" -> Repeat.EveryDays(date(),j.integer("interval"))
            else -> Repeat.AlternatingWeeks(days(),j.flag("odd"))
        }
        val id=j.text("id");require(id.length<=128 && '\u0000' !in id)
        val skip=j.nullableText("skip")?.let(LocalDate::parse)?.also {require(it.year in 1900..9999)}
        return AlarmRule(id,LocalTime.parse(j.text("time")),repeat,j.text("label"),j.flag("enabled"),
            j.nullableText("zone"),skip,j.flag("vibrate"),j.integer("ramp"),j.integer("snooze"),
            j.integer("limit"),j.number("revision"))
    }
    private fun mask(days: Set<DayOfWeek>)=days.sumOf {1 shl (it.value-1)}
}

internal fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.let {
    require(it.isString);it.content
}
internal fun JsonObject.nullableText(key: String): String? =
    if(this[key]==null || this[key]==JsonNull) null else text(key)
internal fun JsonObject.number(key: String): Long = getValue(key).jsonPrimitive.let {
    require(!it.isString);it.long
}
internal fun JsonObject.integer(key: String): Int = number(key).also {require(it in Int.MIN_VALUE..Int.MAX_VALUE)}.toInt()
internal fun JsonObject.flag(key: String): Boolean = getValue(key).jsonPrimitive.let {
    require(!it.isString);it.boolean
}

data class AlarmArchivePreview(val createdAt: Instant,val alarms: List<AlarmRule>,val fingerprint: String)

/**
 * Bounded UTF-8 JSON, no executable entries, paths, audio, credentials or live tokens.
 * SHA-256 detects accidental corruption; this plaintext format is not authenticated encryption.
 */
object AlarmArchive {
    const val MAX_BYTES=512*1024
    const val MAX_ALARMS=500
    private const val FORMAT="solsleep-alarm-archive"
    private val fields=setOf("format","version","createdAt","alarmCount","alarmsSha256","alarms")
    fun write(alarms: List<AlarmRule>,output: OutputStream,createdAt: Instant=Instant.now()) {
        require(alarms.size<=MAX_ALARMS && alarms.map {it.id}.distinct().size==alarms.size)
        val array=JsonArray(alarms.sortedBy {it.id}.map {AlarmRuleCodec.element(it)})
        // Round-trip validation also applies bounds to records constructed outside the UI.
        array.forEach {AlarmRuleCodec.decode(it.jsonObject)}
        val body=buildJsonObject {
            put("format",FORMAT);put("version",1);put("createdAt",createdAt.toString())
            put("alarmCount",alarms.size);put("alarmsSha256",hash(array.toString().toByteArray(Charsets.UTF_8)))
            put("alarms",array)
        }.toString().toByteArray(Charsets.UTF_8)
        require(body.size<=MAX_BYTES)
        output.write(body);output.flush() // The document-provider caller owns closing and success reporting.
    }
    fun inspect(input: InputStream): AlarmArchivePreview {
        val buffer=ByteArrayOutputStream()
        val chunk=ByteArray(8192)
        while(true) {
            val n=input.read(chunk,0,minOf(chunk.size,MAX_BYTES+1-buffer.size()))
            if(n<0) break
            require(n>0) {"Input made no progress"}
            buffer.write(chunk,0,n)
            require(buffer.size()<=MAX_BYTES) {"Archive is too large"}
        }
        val bytes=buffer.toByteArray()
        require(bytes.size<=MAX_BYTES) {"Archive is too large"}
        val decoder=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text=decoder.decode(ByteBuffer.wrap(bytes)).toString()
        boundedStructure(text)
        val root=Json.parseToJsonElement(text).jsonObject
        require(root.keys==fields && root.text("format")==FORMAT && root.number("version")==1L) {"Unsupported archive"}
        val array=root.getValue("alarms").jsonArray
        require(array.size<=MAX_ALARMS && root.integer("alarmCount")==array.size)
        val digest=hash(array.toString().toByteArray(Charsets.UTF_8))
        require(root.text("alarmsSha256")==digest) {"Archive checksum does not match"}
        val alarms=array.map {AlarmRuleCodec.decode(it.jsonObject)}
        require(alarms.map {it.id}.distinct().size==alarms.size) {"Duplicate alarm identities"}
        return AlarmArchivePreview(Instant.parse(root.text("createdAt")),alarms,hash(bytes))
    }
    private fun hash(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") {"%02x".format(it.toInt() and 255)}
    private fun boundedStructure(text: String) {
        var depth=0;var quoted=false;var escaped=false
        for(c in text) {
            if(quoted) {
                if(escaped) escaped=false else if(c=='\\') escaped=true else if(c=='"') quoted=false
            } else when(c) {
                '"' -> quoted=true
                '{','[' -> {depth++;require(depth<=8) {"Archive nesting is too deep"}}
                '}',']' -> {depth--;require(depth>=0)}
            }
        }
        require(!quoted && depth==0) {"Incomplete archive"}
    }
}
