// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.core

import java.io.*
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CancellationException

/** Identity only: no invented deletion time, sensor payload or URI permission. */
data class RecordingDeletion(val sessionId: UUID,val requestId: UUID)

/** Defensive immutable copy; both identities must be unique and pair consistently. */
class RecordingDeletionSnapshot(markers: Collection<RecordingDeletion>) {
    val markers: List<RecordingDeletion>
    init {
        require(markers.size<=RecordingDeletionArchive.MAX_MARKERS) {"Deletion history exceeds export capacity; retained"}
        val copy=markers.toList()
        require(copy.size<=RecordingDeletionArchive.MAX_MARKERS)
        require(copy.map {it.sessionId}.distinct().size==copy.size)
        require(copy.map {it.requestId}.distinct().size==copy.size)
        this.markers=Collections.unmodifiableList(copy.sortedBy {it.sessionId.toString()})
    }
}

data class RecordingDeletionPreview(val snapshot: RecordingDeletionSnapshot,val createdAt: Instant,val fingerprint: String) {
    init {require(fingerprint.matches(Regex("[0-9a-f]{64}")))}
}
data class RecordingDeletionMerge(val added: Int,val alreadyKnown: Int,val liveConflicts: Int,val identityConflicts: Int)
class RecordingDeletionVerificationException(cause: Exception): IOException("Deletion history destination did not verify",cause)

/** SOLD v1: fixed-width, bounded, canonical big-endian identity pairs plus SHA-256.
 * Checksums detect changed bytes; this plaintext format does not authenticate its author.
 */
object RecordingDeletionArchive {
    const val MAX_MARKERS=10_000
    const val HEADER_BYTES=20
    const val MAX_BYTES=HEADER_BYTES+32*MAX_MARKERS+32
    private const val MAGIC=0x534f4c44
    fun write(snapshot: RecordingDeletionSnapshot,out: OutputStream,createdAt: Instant=Instant.ofEpochMilli(System.currentTimeMillis())) {
        // Snapshot validation and exact serialization happen before opening/truncating a provider.
        val millis=createdAt.toEpochMilli()
        require(Instant.ofEpochMilli(millis)==createdAt) {"Document time requires milliseconds"}
        val buffer=ByteBuffer.allocate(HEADER_BYTES+32*snapshot.markers.size)
        buffer.putInt(MAGIC).putInt(1).putLong(millis).putInt(snapshot.markers.size)
        snapshot.markers.forEach {
            buffer.putLong(it.sessionId.mostSignificantBits).putLong(it.sessionId.leastSignificantBits)
            buffer.putLong(it.requestId.mostSignificantBits).putLong(it.requestId.leastSignificantBits)
        }
        val bytes=buffer.array()
        out.write(bytes);out.write(MessageDigest.getInstance("SHA-256").digest(bytes));out.flush()
    }
    fun inspect(input: InputStream): RecordingDeletionPreview {
        val out=ByteArrayOutputStream()
        val block=ByteArray(8192)
        while(true) {
            val n=input.read(block,0,minOf(block.size,MAX_BYTES+1-out.size()))
            if(n<0) break
            require(n>0) {"Input made no progress"}
            out.write(block,0,n);require(out.size()<=MAX_BYTES) {"Deletion history exceeds byte limit"}
        }
        val bytes=out.toByteArray()
        require(bytes.size>=HEADER_BYTES+32) {"Truncated deletion history"}
        val body=bytes.copyOf(bytes.size-32)
        require(MessageDigest.isEqual(bytes.copyOfRange(body.size,bytes.size),
            MessageDigest.getInstance("SHA-256").digest(body))) {"Deletion history checksum mismatch"}
        val b=ByteBuffer.wrap(body)
        require(b.int==MAGIC && b.int==1) {"Unsupported deletion history"}
        val createdAt=Instant.ofEpochMilli(b.long)
        val count=b.int
        require(count in 0..MAX_MARKERS && body.size.toLong()==HEADER_BYTES+32L*count) {"Invalid deletion history count"}
        val markers=List(count) {RecordingDeletion(UUID(b.long,b.long),UUID(b.long,b.long))}
        val snapshot=RecordingDeletionSnapshot(markers)
        require(snapshot.markers==markers) {"Noncanonical deletion history order"}
        return RecordingDeletionPreview(snapshot,createdAt,sha256(bytes))
    }
    fun exportVerified(snapshot: RecordingDeletionSnapshot,openOutput: ()->OutputStream,
        openInput: ()->InputStream,createdAt: Instant=Instant.ofEpochMilli(System.currentTimeMillis())): RecordingDeletionPreview {
        val bytes=ByteArrayOutputStream().also {write(snapshot,it,createdAt)}.toByteArray()
        openOutput().use {it.write(bytes);it.flush()}
        try {
            val actual=openInput().use(::inspect)
            check(actual.fingerprint==sha256(bytes)) {"Destination differs from frozen deletion history"}
            return actual
        } catch(c: CancellationException) {throw c}
        catch(e: Exception) {throw RecordingDeletionVerificationException(e)}
    }
}
