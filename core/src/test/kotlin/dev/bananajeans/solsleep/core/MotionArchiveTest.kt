// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.core

import org.junit.*
import org.junit.Assert.*
import java.io.*
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CancellationException

class MotionArchiveTest {
    private val id=UUID.randomUUID();private val boot=UUID.randomUUID();private val stream=UUID.randomUUID()
    private val wall=Instant.parse("2026-10-17T23:00:00Z")
    private val session=CaptureSession(id,UUID.randomUUID(),UUID.randomUUID(),CaptureState.COMPLETED,wall,wall.minusSeconds(3),7)
    private val source=CaptureStream(stream,id,boot,wall,100,CaptureSource("加速度 🌙","Vendor",2,0.01f,39.24f,true),1,2,1)
    private val recording=CaptureRecording(session,listOf(source),listOf(
        CaptureEvent(77,id,CaptureReason.START,wall,100,boot,null),
        CaptureEvent(99,id,CaptureReason.ACCURACY,wall.minusSeconds(3),101,boot,-1)))
    private fun chunk(sequence: Long=0)=MotionChunk(id,stream,boot,sequence,wall,100,
        listOf(MotionSample(101+sequence*2,-0.0f,Float.MIN_VALUE,-9.81f),MotionSample(102+sequence*2,1f,2f,3f)))
    private fun archive(r: CaptureRecording=recording,values: List<MotionChunk> = listOf(chunk()))=ByteArrayOutputStream().also {out->
        MotionArchive.write(r,out) {write->values.forEach {write(MotionChunkCodec.encode(it))}}
    }.toByteArray()
    private fun rejected(block: ()->Unit) {try {block();fail("Must reject")} catch(_: IllegalArgumentException) { } catch(_: IOException) { }}
    @Test fun roundTripRetainsEveryMetadataFieldEventSequenceFloatBitAndRawByte() {
        val bytes=archive();val vectors=mutableListOf<ByteArray>()
        val preview=MotionArchive.read(ByteArrayInputStream(bytes)) {_,raw->vectors+=raw}
        assertEquals(recording,preview.recording);assertEquals(1L,preview.chunks);assertEquals(2L,preview.samples)
        assertArrayEquals(MotionChunkCodec.encode(chunk()),vectors.single())
        assertEquals(preview,MotionArchive.read(ByteArrayInputStream(bytes)))
        assertTrue(preview.recording.session.updatedAt<preview.recording.session.startedAt)
    }
    @Test fun independentPythonAuthoredFixtureHasExpectedAnchorsBitsAndProvenance() {
        val bytes=javaClass.getResourceAsStream("/motion-archive-v1.solmotion")!!.use {it.readBytes()}
        val values=mutableListOf<MotionChunk>();val preview=MotionArchive.read(ByteArrayInputStream(bytes)) {c,_->values+=c}
        assertEquals(UUID.fromString("00000000-0000-0000-0000-000000000001"),preview.recording.session.id)
        assertEquals("Independent 🌙",preview.recording.streams.single().source.name)
        assertEquals(42L,preview.recording.events.single().sequence)
        assertEquals(-1,preview.recording.events.single().detail!!.toInt())
        assertEquals(java.lang.Float.floatToRawIntBits(-0.0f),java.lang.Float.floatToRawIntBits(values.single().samples.single().x))
        assertEquals(1L,preview.samples)
    }
    @Test fun truncationTamperingTrailingDataAndUnsupportedVersionsAreRejected() {
        val bytes=archive()
        listOf(0,7,12,50,bytes.size-33,bytes.size-1).forEach {n->rejected {MotionArchive.read(ByteArrayInputStream(bytes.copyOf(n)))}}
        listOf(4,50,bytes.size-40,bytes.size-1).forEach {n->
            val bad=bytes.copyOf();bad[n]=(bad[n].toInt() xor 1).toByte();rejected {MotionArchive.read(ByteArrayInputStream(bad))}
        }
        rejected {MotionArchive.read(ByteArrayInputStream(bytes+0))}
    }
    @Test fun metadataAndChunkLengthBoundsRejectBeforeLargeAllocation() {
        listOf(-1,MotionArchive.MAX_METADATA+1,Int.MAX_VALUE).forEach {length->
            val out=ByteArrayOutputStream();DataOutputStream(out).apply {writeInt(0x534f4c52);writeInt(1);writeInt(length)}
            rejected {MotionArchive.read(ByteArrayInputStream(out.toByteArray()))}
        }
        val bytes=archive();val metadata=java.nio.ByteBuffer.wrap(bytes,8,4).int
        val bad=bytes.copyOf();java.nio.ByteBuffer.wrap(bad,12+metadata,4).putInt(Int.MAX_VALUE)
        rejected {MotionArchive.read(ByteArrayInputStream(bad))}
    }
    @Test fun activeDeletingDuplicateStreamsAndInvalidEventOrderCannotBeWritten() {
        listOf(CaptureState.RECORDING,CaptureState.PAUSED,CaptureState.INTERRUPTED,CaptureState.DELETING).forEach {state->
            rejected {archive(recording.copy(session=session.copy(state=state)))}
        }
        rejected {archive(recording.copy(streams=listOf(source,source)))}
        rejected {archive(recording.copy(events=recording.events.reversed()))}
        rejected {archive(recording.copy(streams=listOf(source.copy(source=source.source.copy(name="bad\uD800")))))}
    }
    @Test fun unknownSourcesWrongAnchorsSequenceGapsOverlapsAndMissingIndexedSamplesAreRejected() {
        listOf(chunk().copy(streamId=UUID.randomUUID()),chunk().copy(bootId=UUID.randomUUID()),
            chunk().copy(anchorWall=wall.plusMillis(1)),chunk().copy(sequence=1),
            chunk().copy(sessionId=UUID.randomUUID())).forEach {bad->rejected {archive(values=listOf(bad))}}
        rejected {archive(values=listOf(chunk(),chunk(2)))}
        rejected {archive(values=listOf(chunk(),chunk(1).copy(samples=chunk().samples)))}
        rejected {archive(recording.copy(streams=listOf(source.copy(committedSamples=3))))}
        rejected {archive(values=emptyList())}
        assertEquals(4L,MotionArchive.read(ByteArrayInputStream(archive(values=listOf(chunk(),chunk(1))))).samples)
    }
    @Test fun emptyFailedRecordingWithNoSamplesAndZeroIndexedSourcesIsPortable() {
        val r=recording.copy(session=session.copy(state=CaptureState.FAILED),streams=listOf(source.copy(committedSamples=0)))
        val preview=MotionArchive.read(ByteArrayInputStream(archive(r,emptyList())))
        assertEquals(r,preview.recording);assertEquals(0L,preview.rawBytes)
    }
    @Test fun destinationMustMatchExactFrozenArchiveAndCloseBeforeReadback() {
        val bytes=archive();val output=ByteArrayOutputStream();var closed=false
        val result=MotionArchive.exportVerified({ByteArrayInputStream(bytes)},
            {object: FilterOutputStream(output) {override fun close() {super.close();closed=true}}},
            {check(closed);ByteArrayInputStream(output.toByteArray())})
        assertEquals(recording,result.recording);assertArrayEquals(bytes,output.toByteArray())
        val replacement=archive(recording.copy(session=session.copy(revision=8)))
        try {MotionArchive.exportVerified({ByteArrayInputStream(bytes)},{ByteArrayOutputStream()},{ByteArrayInputStream(replacement)});fail("Different valid archive")}
        catch(_: MotionVerificationException) { }
    }
    @Test fun malformedSourceNeverOpensDestinationAndReadbackDenialDiffersFromWriteFailure() {
        var opened=false
        rejected {MotionArchive.exportVerified({ByteArrayInputStream(byteArrayOf())},{opened=true;ByteArrayOutputStream()},{ByteArrayInputStream(byteArrayOf())})}
        assertFalse(opened)
        try {MotionArchive.exportVerified({ByteArrayInputStream(archive())},{throw IOException("write")},{throw AssertionError()});fail("Write failure")}
        catch(e: IOException) {assertFalse(e is MotionVerificationException)}
        try {MotionArchive.exportVerified({ByteArrayInputStream(archive())},{ByteArrayOutputStream()},{throw IOException("read")});fail("Read failure")}
        catch(_: MotionVerificationException) { }
        try {MotionArchive.exportVerified({ByteArrayInputStream(archive())},{ByteArrayOutputStream()},{throw CancellationException()});fail("Cancel")}
        catch(_: CancellationException) { }
    }
    @Test fun sourceSinkFailuresPropagateAndNeverMutateOrEraseRawFiles() {
        val directory=Files.createTempDirectory("motion-export")
        try {
            val store=MotionChunkStore(directory,id);store.publish(chunk())
            val before=Files.readAllBytes(directory.resolve("$stream-0.motion"))
            try {store.exportBytes {throw IOException("full destination")};fail("Sink failure must propagate")} catch(_: IOException) { }
            assertArrayEquals(before,Files.readAllBytes(directory.resolve("$stream-0.motion")))
            Files.write(directory.resolve("unknown"),byteArrayOf(1))
            rejected {store.exportBytes {fail("Unknown file must not be omitted")}}
            assertArrayEquals(before,Files.readAllBytes(directory.resolve("$stream-0.motion")))
        } finally {Files.walk(directory).sorted(Comparator.reverseOrder()).forEach(Files::delete)}
    }
    @Test fun thousandsOfChunksStreamWithBoundedReadsAndExactCounts() {
        val bytes=archive(values=(0L until 3000).map {chunk(it)})
        val input=object: ByteArrayInputStream(bytes) {override fun read(b: ByteArray,off: Int,len: Int): Int {
            assertTrue(len<=MotionChunkCodec.MAX_BYTES);return super.read(b,off,len)
        }}
        val preview=MotionArchive.read(input);assertEquals(3000L,preview.chunks);assertEquals(6000L,preview.samples)
    }
}
