// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.core

import java.io.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.DigestInputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CancellationException

data class CaptureEvent(val sequence: Long,val sessionId: UUID,val reason: CaptureReason,val wall: Instant,
    val elapsedNanos: Long,val bootId: UUID,val detail: Long?)
data class CaptureStream(val id: UUID,val sessionId: UUID,val bootId: UUID,val wall: Instant,val elapsedNanos: Long,
    val source: CaptureSource,val committedSamples: Long,val rejectedSamples: Long,val gaps: Long,
    val provenance: CaptureProvenance?=null)

data class CaptureRecording(val session: CaptureSession,val streams: List<CaptureStream>,val events: List<CaptureEvent>) {
    fun validate() {
        require(session.state in setOf(CaptureState.COMPLETED,CaptureState.FAILED)) {"Finish recording before exporting"}
        require(streams.size<=MotionArchive.MAX_STREAMS && streams.map {it.id}.toSet().size==streams.size)
        require(events.size in 1..MotionArchive.MAX_EVENTS)
        streams.forEach {
            require(it.sessionId==session.id && it.elapsedNanos>=0 && it.committedSamples>=0 && it.rejectedSamples>=0 && it.gaps>=0)
            time(it.wall)
        }
        var previous=0L
        events.forEach {
            require(it.sessionId==session.id && it.sequence>previous && it.elapsedNanos>=0)
            time(it.wall);previous=it.sequence
        }
    }
    private fun time(value: Instant) {
        require(value.nano%1_000_000==0 && value>=Instant.parse("1900-01-01T00:00:00Z") && value<Instant.parse("+10000-01-01T00:00:00Z"))
    }
}

data class MotionArchivePreview(val recording: CaptureRecording,val chunks: Long,val samples: Long,val rawBytes: Long,val fingerprint: String)
class MotionVerificationException(cause: Exception): IOException("Recording was written but readback did not verify",cause)

/** Plaintext SOLR v1/v2. No paths, compression or arbitrary file entries. One bounded chunk in memory.
 * Callbacks during read are provisional until the footer AND strict EOF have been validated.
 */
object MotionArchive {
    const val MAX_METADATA=2*1024*1024
    const val MAX_STREAMS=512
    const val MAX_EVENTS=10_000
    const val MAX_CHUNKS=32_768
    const val MAX_RAW_BYTES=64*1024*1024L
    const val MAX_BYTES=MAX_RAW_BYTES+MAX_METADATA+4L*MAX_CHUNKS+48
    private const val MAGIC=0x534f4c52

    fun write(recording: CaptureRecording,output: OutputStream,chunks: ((ByteArray)->Unit)->Unit): MotionArchivePreview {
        val metadata=encodeMetadata(recording)
        val digest=MessageDigest.getInstance("SHA-256")
        val hashing=DigestOutputStream(output,digest);val out=DataOutputStream(hashing)
        out.writeInt(MAGIC);out.writeInt(formatVersion(recording));out.writeInt(metadata.size);out.write(metadata)
        val validator=Validator(recording)
        chunks {bytes->validator.accept(bytes);out.writeInt(bytes.size);out.write(bytes)}
        validator.finish();out.writeInt(0);out.flush()
        val hash=digest.digest();hashing.on(false);out.write(hash);out.flush()
        return validator.preview(hash)
    }
    fun read(input: InputStream,visit: (MotionChunk,ByteArray)->Unit={_,_->}): MotionArchivePreview {
        val digest=MessageDigest.getInstance("SHA-256")
        val hashing=DigestInputStream(input,digest);val data=DataInputStream(hashing)
        require(data.readInt()==MAGIC) {"Unsupported recording archive"}
        val version=data.readInt();require(version in 1..2) {"Unsupported recording archive"}
        val length=data.readInt();require(length in 1..MAX_METADATA)
        val metadata=ByteArray(length);data.readFully(metadata)
        val validator=Validator(decodeMetadata(metadata,version))
        while(true) {
            val size=data.readInt();if(size==0) break
            require(size in 136..MotionChunkCodec.MAX_BYTES)
            val bytes=ByteArray(size);data.readFully(bytes)
            val chunk=validator.accept(bytes);visit(chunk,bytes)
        }
        validator.finish()
        val actual=digest.digest();hashing.on(false)
        val expected=ByteArray(32);data.readFully(expected)
        require(MessageDigest.isEqual(expected,actual) && data.read()==-1) {"Recording checksum or trailing data"}
        return validator.preview(actual)
    }
    /** Source is an already frozen private spool; validate before opening/truncating the provider. */
    fun exportVerified(source: ()->InputStream,openOutput: ()->OutputStream,openInput: ()->InputStream): MotionArchivePreview {
        val original=source().use {read(it)}
        openOutput().use {out->source().use {it.copyTo(out,8192)};out.flush()}
        try {
            val returned=openInput().use {read(it)}
            check(returned.fingerprint==original.fingerprint) {"Destination differs from frozen recording"}
            return returned
        } catch(c: CancellationException) {throw c}
        catch(e: Exception) {throw MotionVerificationException(e)}
    }
    private class Validator(val recording: CaptureRecording) {
        private val sources=recording.streams.associateBy {it.id}
        private val counts=mutableMapOf<UUID,Long>()
        private val sequences=mutableMapOf<UUID,Long>()
        private val last=mutableMapOf<UUID,Long>()
        var chunks=0L;var samples=0L;var bytes=0L
        init {recording.validate()}
        fun accept(value: ByteArray): MotionChunk {
            require(chunks<MAX_CHUNKS && value.size<=MAX_RAW_BYTES-bytes)
            val chunk=MotionChunkCodec.decode(value)
            require(chunk.sessionId==recording.session.id)
            val source=requireNotNull(sources[chunk.streamId]) {"Missing source metadata"}
            require(chunk.bootId==source.bootId && chunk.anchorWall==source.wall && chunk.anchorElapsedNanos==source.elapsedNanos)
            require(chunk.sequence==(sequences[chunk.streamId] ?: 0L) && chunk.sequence<Long.MAX_VALUE)
            require(chunk.samples.first().elapsedNanos>=source.elapsedNanos && chunk.samples.first().elapsedNanos>(last[chunk.streamId] ?: -1))
            sequences[chunk.streamId]=chunk.sequence+1;last[chunk.streamId]=chunk.samples.last().elapsedNanos
            counts[chunk.streamId]=(counts[chunk.streamId] ?: 0)+chunk.samples.size
            chunks++;samples+=chunk.samples.size;bytes+=value.size
            return chunk
        }
        fun finish() {recording.streams.forEach {require((counts[it.id] ?: 0)>=it.committedSamples) {"Indexed observations missing"}}}
        fun preview(hash: ByteArray)=MotionArchivePreview(recording,chunks,samples,bytes,hash.joinToString("") {"%02x".format(it)})
    }
    internal fun formatVersion(value: CaptureRecording)=if(value.streams.any {it.provenance!=null}) 2 else 1
    internal fun encodeMetadata(value: CaptureRecording): ByteArray {
        value.validate()
        val version=formatVersion(value)
        val bytes=ByteArrayOutputStream()
        DataOutputStream(bytes).use {out->
            val s=value.session
            out.uuid(s.id);out.uuid(s.requestId);out.uuid(s.ownerId);out.text(s.state.name)
            out.writeLong(s.startedAt.toEpochMilli());out.writeLong(s.updatedAt.toEpochMilli());out.writeLong(s.revision)
            out.writeInt(value.streams.size)
            value.streams.forEach {v->
                out.uuid(v.id);out.uuid(v.bootId);out.writeLong(v.wall.toEpochMilli());out.writeLong(v.elapsedNanos)
                out.text(v.source.name);out.text(v.source.vendor);out.writeInt(v.source.version)
                out.writeFloat(v.source.resolution);out.writeFloat(v.source.maximumRange);out.writeBoolean(v.source.wakeUp)
                out.writeLong(v.committedSamples);out.writeLong(v.rejectedSamples);out.writeLong(v.gaps)
                if(version>=2) {out.writeBoolean(v.provenance!=null);v.provenance?.let {out.text(CaptureProvenanceCodec.encode(it))}}
            }
            out.writeInt(value.events.size)
            value.events.forEach {v->
                out.writeLong(v.sequence);out.text(v.reason.name);out.writeLong(v.wall.toEpochMilli());out.writeLong(v.elapsedNanos)
                out.uuid(v.bootId);out.writeBoolean(v.detail!=null);v.detail?.let(out::writeLong)
            }
        }
        require(bytes.size()<=MAX_METADATA)
        return bytes.toByteArray()
    }
    private fun decodeMetadata(bytes: ByteArray,version: Int): CaptureRecording=DataInputStream(ByteArrayInputStream(bytes)).use {data->
        val id=data.uuid();val request=data.uuid();val owner=data.uuid();val state=CaptureState.valueOf(data.text())
        val session=CaptureSession(id,request,owner,state,Instant.ofEpochMilli(data.readLong()),Instant.ofEpochMilli(data.readLong()),data.readLong())
        val n=data.readInt();require(n in 0..MAX_STREAMS)
        val streams=List(n) {
            val stream=data.uuid();val boot=data.uuid();val wall=Instant.ofEpochMilli(data.readLong());val elapsed=data.readLong()
            val source=CaptureSource(data.text(),data.text(),data.readInt(),data.readFloat(),data.readFloat(),data.flag())
            val committed=data.readLong();val rejected=data.readLong();val gaps=data.readLong()
            val provenance=if(version>=2 && data.flag()) CaptureProvenanceCodec.decode(data.text()) else null
            CaptureStream(stream,id,boot,wall,elapsed,source,committed,rejected,gaps,provenance)
        }
        val count=data.readInt();require(count in 1..MAX_EVENTS)
        val events=List(count) {
            val sequence=data.readLong();val reason=CaptureReason.valueOf(data.text());val wall=Instant.ofEpochMilli(data.readLong());val elapsed=data.readLong()
            val boot=data.uuid();val detail=if(data.flag()) data.readLong() else null
            CaptureEvent(sequence,id,reason,wall,elapsed,boot,detail)
        }
        require(data.read()==-1)
        CaptureRecording(session,streams,events).also {it.validate()}
    }
    private fun DataOutputStream.uuid(id: UUID) {writeLong(id.mostSignificantBits);writeLong(id.leastSignificantBits)}
    private fun DataInputStream.uuid()=UUID(readLong(),readLong())
    private fun DataOutputStream.text(value: String) {
        val encoded=Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(java.nio.CharBuffer.wrap(value))
        require(encoded.remaining()<=2048)
        val bytes=ByteArray(encoded.remaining());encoded.get(bytes);writeInt(bytes.size);write(bytes)
    }
    private fun DataInputStream.text(): String {
        val size=readInt();require(size in 0..2048)
        val bytes=ByteArray(size);readFully(bytes)
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    }
    private fun DataInputStream.flag(): Boolean=readUnsignedByte().also {require(it in 0..1)}==1
}
