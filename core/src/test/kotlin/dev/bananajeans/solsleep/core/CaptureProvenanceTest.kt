// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.core

import org.junit.Test
import org.junit.Assert.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.DateTimeException

class CaptureProvenanceTest {
    private val value=CaptureProvenance("a".repeat(64),"Europe/Tallinn",100_000)
    private fun rejects(block: ()->Unit) {
        try {block();fail("Must reject invalid provenance")} catch(_: IllegalArgumentException) {} catch(_: DateTimeException) {}
    }
    @Test fun exactSnapshotRoundTripDoesNotUseCurrentZoneOrBuild() {
        assertEquals(value,CaptureProvenanceCodec.decode(CaptureProvenanceCodec.encode(value)))
        for(zone in listOf("UTC","Asia/Kathmandu","GMT+03:00","-02:30")) {
            val changed=value.copy(zoneId=zone,requestedPeriodMicros=60_000_000)
            assertEquals(changed,CaptureProvenanceCodec.decode(CaptureProvenanceCodec.encode(changed)))
        }
    }
    @Test fun malformedAndFutureProvenanceIsRejectedWithoutFallbackDefaults() {
        val text=CaptureProvenanceCodec.encode(value)
        for(bad in listOf(text.replace("\"version\":1","\"version\":2"),text.replace("100000","0"),
            text.replace("100000","60000001"),text.replace("100000","4295067296"),text.replace("100000","\"100000\""),
            text.replace("Europe/Tallinn","Imaginary/Place"),text.replace("a".repeat(64),"A".repeat(64)),
            text.replace("a".repeat(64),"a".repeat(63)),text.dropLast(1)+",\"network\":true}",
            text.dropLast(1)+",\"version\":1}"," ".repeat(CaptureProvenanceCodec.MAX_BYTES+1))) {
            rejects {CaptureProvenanceCodec.decode(bad)}
        }
        rejects {value.copy(version=2)};rejects {value.copy(requestedPeriodMicros=-1)}
    }
    @Test fun oldIndependentArchiveRemainsNullAndByteIdentical() {
        val bytes=javaClass.getResourceAsStream("/motion-archive-v1.solmotion")!!.readBytes()
        val raw=mutableListOf<ByteArray>();val read=MotionArchive.read(ByteArrayInputStream(bytes)) {_,b->raw+=b}
        assertNull(read.recording.streams.single().provenance)
        val out=ByteArrayOutputStream();MotionArchive.write(read.recording,out) {write->raw.forEach(write)}
        assertArrayEquals(bytes,out.toByteArray())
    }
    @Test fun independentVersionTwoFixtureHasExactProvenanceAndRewritesIdentically() {
        val bytes=javaClass.getResourceAsStream("/motion-archive-v2.solmotion")!!.readBytes()
        val raw=mutableListOf<ByteArray>();val read=MotionArchive.read(ByteArrayInputStream(bytes)) {_,b->raw+=b}
        assertEquals(CaptureProvenance("0123456789abcdef".repeat(4),"Europe/Tallinn",100_000),read.recording.streams.single().provenance)
        val out=ByteArrayOutputStream();MotionArchive.write(read.recording,out) {write->raw.forEach(write)}
        assertArrayEquals(bytes,out.toByteArray())
    }
    @Test fun mixedLegacyAndNewStreamsPreserveUnknownsAndFrozenFields() {
        val old=MotionArchive.read(javaClass.getResourceAsStream("/motion-archive-v1.solmotion")!!).recording
        val first=old.streams.single();val next=first.copy(id=java.util.UUID.randomUUID(),committedSamples=0,provenance=value)
        val mixed=old.copy(streams=listOf(first.copy(committedSamples=0),next))
        val out=ByteArrayOutputStream();MotionArchive.write(mixed,out) {}
        val restored=MotionArchive.read(ByteArrayInputStream(out.toByteArray())).recording
        assertEquals(mixed,restored);assertNull(restored.streams.first().provenance)
        assertEquals(2,java.nio.ByteBuffer.wrap(out.toByteArray(),4,4).int)
    }
    @Test fun versionTwoMalformedFlagsAndProvenanceRejectEvenWithRecomputedChecksum() {
        val original=javaClass.getResourceAsStream("/motion-archive-v2.solmotion")!!.readBytes()
        fun changed(old: String,new: String): ByteArray {
            val from=old.toByteArray();val to=new.toByteArray();assertEquals(from.size,to.size)
            val index=original.indices.first {i->i+from.size<=original.size && from.indices.all {original[i+it]==from[it]}}
            val bytes=original.copyOf();to.copyInto(bytes,index)
            java.security.MessageDigest.getInstance("SHA-256").digest(bytes.copyOf(bytes.size-32)).copyInto(bytes,bytes.size-32)
            return bytes
        }
        val marker="{\"version\":1".toByteArray()
        val offset=original.indices.first {i->i+marker.size<=original.size && marker.indices.all {original[i+it]==marker[it]}}
        val flag=original.copyOf();flag[offset-5]=2
        java.security.MessageDigest.getInstance("SHA-256").digest(flag.copyOf(flag.size-32)).copyInto(flag,flag.size-32)
        for(bytes in listOf(changed("\"version\":1","\"version\":2"),changed("100000","000000"),flag)) {
            rejects {MotionArchive.read(ByteArrayInputStream(bytes))}
        }
    }
}
