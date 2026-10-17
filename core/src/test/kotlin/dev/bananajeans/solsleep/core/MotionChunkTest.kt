// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.core

import org.junit.*
import org.junit.Assert.*
import java.nio.file.*
import java.io.IOException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.*

class MotionChunkTest {
    private val directory=Files.createTempDirectory("solsleep-chunks")
    private val session=UUID.fromString("4bd58c41-0803-4344-bf22-75b65e929280")
    private val stream=UUID.fromString("a6b7f9e4-23a0-4d6d-9949-6a2f86716a9f")
    private val chunk=MotionChunk(session,stream,UUID.fromString("c78ee3f6-8b46-4fd3-a6a4-c6659c2a647f"),0,
        Instant.parse("2026-10-17T08:00:00Z"),1000,listOf(MotionSample(1100,1.25f,-2.5f,9.81f)))
    @After fun cleanup() {Files.walk(directory).use {s->s.sorted(Comparator.reverseOrder()).forEach {Files.delete(it)}}}
    private fun reject(block: ()->Unit) {try {block();fail("Must reject")} catch(_: Exception) {}}
    private fun store()=MotionChunkStore(directory,session)
    private fun target(seq: Long=0)=directory.resolve("$stream-$seq.motion")
    @Test fun binaryRoundTripPreservesExactFloatBitsAndMonotonicAnchors() {
        assertEquals(chunk,MotionChunkCodec.decode(MotionChunkCodec.encode(chunk)))
        val data=javaClass.getResourceAsStream("/chunks/motion-v1.bin")!!.readBytes()
        assertArrayEquals(data,MotionChunkCodec.encode(chunk))
        assertEquals(chunk,MotionChunkCodec.decode(data))
    }
    @Test fun allTruncationsAndSingleByteCorruptionAreDetected() {
        val bytes=MotionChunkCodec.encode(chunk)
        for(i in bytes.indices) {
            reject {MotionChunkCodec.decode(bytes.copyOf(i))}
            val corrupt=bytes.clone();corrupt[i]=(corrupt[i].toInt() xor 1).toByte()
            reject {MotionChunkCodec.decode(corrupt)}
        }
    }
    @Test fun sampleOrderFiniteValuesAndChunkBoundsAreEnforced() {
        reject {chunk.copy(samples=emptyList())};reject {chunk.copy(sequence=-1)}
        reject {chunk.copy(samples=listOf(chunk.samples.single(),chunk.samples.single()))}
        reject {chunk.copy(samples=listOf(MotionSample(1,Float.NaN,0f,0f)))}
        reject {MotionChunkCodec.decode(ByteArray(MotionChunkCodec.MAX_BYTES+1))}
    }
    @Test fun everyPublicationCrashPointRecoversCompleteChunkWithoutDuplicates() {
        ChunkCheckpoint.entries.forEach {point->
            val dir=Files.createDirectory(directory.resolve(point.name))
            try {MotionChunkStore(dir,session,checkpoint={if(it==point) throw IOException("simulated crash")}).publish(chunk);fail("Crash expected")}
            catch(_: IOException) { }
            val reopened=MotionChunkStore(dir,session)
            assertEquals(listOf(chunk),reopened.recover().chunks)
            reopened.publish(chunk)
            assertEquals(listOf(chunk),reopened.recover().chunks)
            assertTrue(reopened.recover().issues.isEmpty())
        }
    }
    @Test fun partialNextChunkNeverDestroysPreviousPublishedSamples() {
        store().publish(chunk)
        val pending=directory.resolve("$stream-1.motion.pending")
        Files.write(pending,MotionChunkCodec.encode(chunk.copy(sequence=1)).copyOf(60))
        val result=store().recover()
        assertEquals(listOf(chunk),result.chunks);assertEquals(1,result.issues.size)
        assertEquals(60L,Files.size(pending));assertTrue(Files.exists(target()))
    }
    @Test fun committedCorruptionIsRetainedAndSequenceGapIsReported() {
        store().publish(chunk);store().publish(chunk.copy(sequence=2))
        val before=Files.readAllBytes(target())
        Files.write(target(),before.copyOf(40))
        val result=store().recover()
        assertEquals(listOf(2L),result.chunks.map {it.sequence})
        assertEquals(2,result.issues.size);assertEquals(40L,Files.size(target()))
    }
    @Test fun conflictingRetryCannotReplaceAlreadyCommittedBytes() {
        store().publish(chunk)
        reject {store().publish(chunk.copy(samples=listOf(MotionSample(1200,0f,0f,0f))))}
        assertEquals(listOf(chunk),store().recover().chunks)
    }
    @Test fun wrongSessionAndFilenameNeverBecomeAcceptedSamples() {
        reject {store().publish(chunk.copy(sessionId=UUID.randomUUID()))}
        Files.write(target(1),MotionChunkCodec.encode(chunk))
        assertTrue(store().recover().chunks.isEmpty());assertEquals(1,store().recover().issues.size)
    }
    @Test fun syncFailureCannotProduceSuccessAndPublishedBytesRemainRecoverable() {
        try {MotionChunkStore(directory,session,syncDirectory={throw IOException("full disk")}).publish(chunk);fail("Must surface sync failure")}
        catch(_: IOException) { }
        assertEquals(listOf(chunk),store().recover().chunks)
    }
    @Test fun recoveredFinalFileIsNotAcknowledgedWhenDirectorySyncFails() {
        store().publish(chunk)
        try {MotionChunkStore(directory,session,syncDirectory={throw IOException("sync failed")}).recover();fail("Cannot acknowledge without directory flush")}
        catch(_: IOException) { }
        assertEquals(listOf(chunk),store().recover().chunks)
    }
    @Test fun twoHelpersSerializeIdenticalPublicationsAndNeverOverwrite() {
        val executor=Executors.newFixedThreadPool(2);val gate=CountDownLatch(1)
        try {
            val futures=(1..2).map {executor.submit {gate.await();store().publish(chunk)}}
            gate.countDown();futures.forEach {it.get(10,TimeUnit.SECONDS)}
            assertEquals(listOf(chunk),store().recover().chunks)
        } finally {executor.shutdownNow()}
    }
    @Test fun recoveryIgnoresUnrelatedFilesAndRejectsSymlinkPayload() {
        val other=directory.resolve("keep.txt");Files.writeString(other,"keep")
        Files.createSymbolicLink(target(),other)
        assertEquals(1,store().recover().issues.size)
        assertEquals("keep",Files.readString(other))
    }
}
