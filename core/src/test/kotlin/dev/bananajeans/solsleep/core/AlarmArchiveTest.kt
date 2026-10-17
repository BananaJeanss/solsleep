// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.security.MessageDigest
import java.time.*

class AlarmArchiveTest {
    private val created=Instant.parse("2026-10-17T01:00:00Z")
    private val rules=listOf(
        AlarmRule("one",LocalTime.of(6,30),Repeat.Once(LocalDate.of(2026,12,31)),"Café 🌙 \"quote\"\nline",zoneId="Europe/Tallinn",skipDate=LocalDate.of(2026,11,1)),
        AlarmRule("weekly",LocalTime.NOON,Repeat.Weekly(setOf(DayOfWeek.MONDAY,DayOfWeek.SUNDAY)),enabled=false,rampSeconds=0,snoozeLimit=0),
        AlarmRule("interval",LocalTime.MIDNIGHT,Repeat.EveryDays(LocalDate.of(2024,2,29),366),vibrate=false,snoozeMinutes=60),
        AlarmRule("alternating",LocalTime.of(23,59),Repeat.AlternatingWeeks(DayOfWeek.entries.toSet(),false),revision=Long.MAX_VALUE,rampSeconds=120),
    )
    private fun archive(values: List<AlarmRule> = rules)=ByteArrayOutputStream().also {AlarmArchive.write(values,it,created)}.toByteArray()
    private fun inspect(bytes: ByteArray)=AlarmArchive.inspect(ByteArrayInputStream(bytes))
    private fun rejected(bytes: ByteArray) {
        try {inspect(bytes);fail("Invalid archive was accepted")} catch(_: IllegalArgumentException) {} catch(_: IllegalStateException) {} catch(_: java.nio.charset.CharacterCodingException) {} catch(_: DateTimeException) {}
    }
    private fun changed(edit: (MutableMap<String,JsonElement>)->Unit): ByteArray {
        val root=Json.parseToJsonElement(archive().decodeToString()).jsonObject.toMutableMap()
        edit(root)
        return JsonObject(root).toString().toByteArray()
    }
    private fun rehash(root: MutableMap<String,JsonElement>) {
        val digest=MessageDigest.getInstance("SHA-256").digest(root.getValue("alarms").toString().toByteArray())
            .joinToString("") {"%02x".format(it.toInt() and 255)}
        root["alarmsSha256"]=JsonPrimitive(digest)
    }
    @Test fun allRecurrencesSettingsUnicodeAndAbsenceRoundTripWithStableOutput() {
        val bytes=archive();val preview=inspect(bytes)
        assertEquals(rules.sortedBy {it.id},preview.alarms)
        assertEquals(created,preview.createdAt)
        assertEquals(64,preview.fingerprint.length)
        assertArrayEquals(bytes,archive(rules.reversed()))
        assertEquals(4,preview.alarms.size)
    }
    @Test fun emptyArchiveIsValid() {assertTrue(inspect(archive(emptyList())).alarms.isEmpty())}
    @Test fun sharedStorageCodecReadsV1NullableFieldsOmittedByOldWriter() {
        val original=rules[1]
        val json=Json.parseToJsonElement(AlarmRuleCodec.encode(original)).jsonObject.toMutableMap()
        json.remove("zone");json.remove("skip")
        assertEquals(original,AlarmRuleCodec.decode(JsonObject(json).toString()))
    }
    @Test fun checksumRejectsModifiedLabelsEvenIfJsonIsValid() {
        rejected(changed {root->root["alarms"]=JsonArray(listOf(AlarmRuleCodec.element(rules[0].copy(label="Tampered"))))})
    }
    @Test fun unknownContainerVersionAndFieldsAreRefused() {
        rejected(changed {it["version"]=JsonPrimitive(2)})
        rejected(changed {it["recordings"]=JsonArray(emptyList())})
    }
    @Test fun countMismatchIsRefused() {rejected(changed {it["alarmCount"]=JsonPrimitive(100)})}
    @Test fun unsupportedRecordVersionFieldAndWrongTypesAreRefused() {
        for((field,value) in listOf("version" to JsonPrimitive(2),"futureFeature" to JsonPrimitive(true),"enabled" to JsonPrimitive("true"),"ramp" to JsonPrimitive(Long.MAX_VALUE),"kind" to JsonPrimitive("future"))) {
            rejected(changed {root->
                val record=AlarmRuleCodec.element(rules[0]).toMutableMap();record[field]=value
                root["alarms"]=JsonArray(listOf(JsonObject(record)));root["alarmCount"]=JsonPrimitive(1);rehash(root)
            })
        }
    }
    @Test fun duplicateIdentitiesAreRefusedAfterValidChecksum() {
        rejected(changed {root->root["alarms"]=JsonArray(List(2) {AlarmRuleCodec.element(rules[0])});root["alarmCount"]=JsonPrimitive(2);rehash(root)})
    }
    @Test fun unknownZoneInvalidDayMaskAndOutOfRangeValuesAreRefused() {
        for((field,value) in listOf("zone" to JsonPrimitive("Not/AZone"),"days" to JsonPrimitive(128),"snooze" to JsonPrimitive(0),"limit" to JsonPrimitive(11),"revision" to JsonPrimitive(0))) {
            rejected(changed {root->val r=AlarmRuleCodec.element(rules[1]).toMutableMap();r[field]=value
                root["alarms"]=JsonArray(listOf(JsonObject(r)));root["alarmCount"]=JsonPrimitive(1);rehash(root)})
        }
    }
    @Test fun everyTruncationOfArchiveIsRefused() {
        val bytes=archive()
        for(end in bytes.indices) rejected(bytes.copyOf(end))
    }
    @Test fun byteAndNestingLimitsAreEnforcedBeforeParsing() {
        rejected(ByteArray(AlarmArchive.MAX_BYTES+1) {32})
        rejected(("[".repeat(10000)+"]".repeat(10000)).toByteArray())
    }
    @Test fun malformedUtf8AndTrailingGarbageAreRejected() {
        rejected(byteArrayOf(0xc3.toByte(),0x28))
        rejected(archive()+"null".toByteArray())
    }
    @Test fun readAndWriteFailuresPropagateAndNeverReturnSuccess() {
        try {AlarmArchive.write(rules,object: OutputStream() {override fun write(b: Int) {throw IOException("full")}},created);fail()} catch(_: IOException) {}
        try {AlarmArchive.inspect(object: InputStream() {override fun read(): Int {throw IOException("unavailable")}});fail()} catch(_: IOException) {}
    }
    @Test(expected=IllegalArgumentException::class) fun recordLimitAppliesToExport() {
        archive(List(501) {rules[0].copy(id="id-$it")})
    }
}
