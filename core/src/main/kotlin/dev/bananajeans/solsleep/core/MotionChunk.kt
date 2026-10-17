// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.core

import java.io.*
import java.nio.channels.FileChannel
import java.nio.file.*
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/** SI accelerometer readings. Sensor monotonic time and the original wall-clock anchor are separate. */
data class MotionSample(val elapsedNanos: Long,val x: Float,val y: Float,val z: Float)
data class MotionChunk(val sessionId: UUID,val streamId: UUID,val bootId: UUID,val sequence: Long,
    val anchorWall: Instant,val anchorElapsedNanos: Long,val samples: List<MotionSample>) {
    init {
        require(sequence>=0 && anchorElapsedNanos>=0 && anchorWall.nano%1_000_000==0)
        require(samples.size in 1..4096)
        var previous=-1L
        samples.forEach {s->
            require(s.elapsedNanos>=0 && s.elapsedNanos>previous)
            require(s.x.isFinite() && s.y.isFinite() && s.z.isFinite())
            previous=s.elapsedNanos
        }
    }
}

/** Fixed big-endian v1, bounded before allocation, SHA-256 over the complete header and payload. */
object MotionChunkCodec {
    const val MAX_BYTES=128*1024
    fun encode(chunk: MotionChunk): ByteArray {
        val buffer=ByteArrayOutputStream()
        DataOutputStream(buffer).use {out->
            out.writeInt(0x534f4c4d);out.writeInt(1)
            listOf(chunk.sessionId,chunk.streamId,chunk.bootId).forEach {out.writeLong(it.mostSignificantBits);out.writeLong(it.leastSignificantBits)}
            out.writeLong(chunk.sequence);out.writeLong(chunk.anchorWall.toEpochMilli());out.writeLong(chunk.anchorElapsedNanos)
            out.writeInt(chunk.samples.size)
            chunk.samples.forEach {s->out.writeLong(s.elapsedNanos);out.writeFloat(s.x);out.writeFloat(s.y);out.writeFloat(s.z)}
        }
        val payload=buffer.toByteArray()
        return payload+MessageDigest.getInstance("SHA-256").digest(payload)
    }
    fun decode(bytes: ByteArray): MotionChunk {
        require(bytes.size in 136..MAX_BYTES)
        val payload=bytes.copyOfRange(0,bytes.size-32)
        require(MessageDigest.isEqual(bytes.copyOfRange(bytes.size-32,bytes.size),MessageDigest.getInstance("SHA-256").digest(payload)))
        DataInputStream(ByteArrayInputStream(payload)).use {input->
            require(input.readInt()==0x534f4c4d && input.readInt()==1)
            fun uuid()=UUID(input.readLong(),input.readLong())
            val session=uuid();val stream=uuid();val boot=uuid()
            val sequence=input.readLong();val wall=Instant.ofEpochMilli(input.readLong());val elapsed=input.readLong()
            val count=input.readInt();require(count in 1..4096 && input.available()==count*20)
            return MotionChunk(session,stream,boot,sequence,wall,elapsed,
                List(count) {MotionSample(input.readLong(),input.readFloat(),input.readFloat(),input.readFloat())})
        }
    }
}

enum class ChunkCheckpoint { FILE_SYNCED, RENAMED, DIRECTORY_SYNCED }
data class ChunkIssue(val filename: String,val reason: String)
data class ChunkRecovery(val chunks: List<MotionChunk>,val issues: List<ChunkIssue>)
data class MotionStreamAnchor(val bootId: UUID,val wall: Instant,val elapsedNanos: Long)
data class MotionSummary(val chunks: Long,val samples: Long,val bytes: Long,val issues: List<ChunkIssue>,
    val streamSamples: Map<UUID,Long> = emptyMap(),val streamAnchors: Map<UUID,MotionStreamAnchor> = emptyMap())

/**
 * Self-describing files are authoritative; a future SQL index must be rebuildable from these files.
 * No file+database atomicity is assumed. Use only one dedicated credential-protected session directory.
 * Writers/recovery share an OS file lock. Staging and final files reside on the same filesystem.
 */
class MotionChunkStore(private val directory: Path,private val sessionId: UUID,
    private val syncDirectory: (Path)->Unit={path->FileChannel.open(path,StandardOpenOption.READ).use {it.force(true)}},
    private val checkpoint: (ChunkCheckpoint)->Unit={}) {
    private val filename=Regex("[0-9a-f-]{36}-[0-9]{1,19}\\.motion(?:\\.pending)?")
    @Synchronized fun publish(chunk: MotionChunk) = locked {
        require(chunk.sessionId==sessionId)
        // Revalidate mutable collections received from external callers.
        val bytes=MotionChunkCodec.encode(chunk);MotionChunkCodec.decode(bytes)
        val target=directory.resolve("${chunk.streamId}-${chunk.sequence}.motion")
        val staging=directory.resolve(target.fileName.toString()+".pending")
        if(Files.exists(target)) {
            require(read(target).contentEquals(bytes)) {"Conflicting committed chunk"}
            syncDirectory(directory)
            return@locked
        }
        if(Files.exists(staging)) require(read(staging).contentEquals(bytes)) {"Conflicting pending chunk"}
        else FileOutputStream(staging.toFile()).use {out->out.write(bytes);out.fd.sync()}
        // A retry after an interrupted write must sync the existing complete staging file too.
        FileChannel.open(staging,StandardOpenOption.WRITE).use {it.force(true)}
        checkpoint(ChunkCheckpoint.FILE_SYNCED)
        Files.move(staging,target,StandardCopyOption.ATOMIC_MOVE)
        checkpoint(ChunkCheckpoint.RENAMED)
        syncDirectory(directory);checkpoint(ChunkCheckpoint.DIRECTORY_SYNCED)
    }
    @Synchronized fun recover(): ChunkRecovery {
        val chunks=mutableListOf<MotionChunk>()
        val issues=scan {chunk,_->chunks+=chunk}
        return ChunkRecovery(chunks.sortedWith(compareBy<MotionChunk> {it.streamId}.thenBy {it.sequence}),issues)
    }
    /** Validates one bounded file at a time; never materializes an entire recorded night. */
    @Synchronized fun summary(): MotionSummary {
        var chunks=0L;var samples=0L;var bytes=0L
        val counts=mutableMapOf<UUID,Long>()
        val anchors=mutableMapOf<UUID,MotionStreamAnchor>()
        val anchorIssues=mutableListOf<ChunkIssue>()
        val issues=scan {chunk,size->chunks++;samples+=chunk.samples.size;bytes+=size
            counts[chunk.streamId]=(counts[chunk.streamId] ?: 0)+chunk.samples.size
            val anchor=MotionStreamAnchor(chunk.bootId,chunk.anchorWall,chunk.anchorElapsedNanos)
            if(anchors.putIfAbsent(chunk.streamId,anchor)?.let {it!=anchor}==true)
                anchorIssues+=ChunkIssue("${chunk.streamId}-${chunk.sequence}.motion","Stream clock anchor changed")
        }
        return MotionSummary(chunks,samples,bytes,issues+anchorIssues,counts.toMap(),anchors.toMap())
    }
    /** Only after a durable DELETING intent. Keep the lock inode; never unlink a held lock. */
    @Synchronized fun eraseOwned() = locked {
        val paths=Files.list(directory).use {it.iterator().asSequence().toList()}
        require(paths.all {path->
            (path.fileName.toString()==".writer.lock" || filename.matches(path.fileName.toString())) &&
                !Files.isSymbolicLink(path) && Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)
        }) {"Unknown files retained; deletion needs review"}
        try {paths.filter {it.fileName.toString()!=".writer.lock"}.forEach {Files.delete(it)}}
        finally {syncDirectory(directory)}
    }
    private fun scan(visit: (MotionChunk,Int)->Unit): List<ChunkIssue> = locked {
        val issues=mutableListOf<ChunkIssue>()
        val sequences=mutableMapOf<UUID,MutableList<Triple<Long,Long,Long>>>()
        val paths=Files.list(directory).use {s->s.filter {filename.matches(it.fileName.toString())}.sorted().iterator().asSequence().toList()}
        paths.filter {it.fileName.toString().endsWith(".pending")}.forEach {pending->
            try {
                val bytes=read(pending);val chunk=verified(pending,bytes,true)
                val target=directory.resolve("${chunk.streamId}-${chunk.sequence}.motion")
                if(Files.exists(target)) require(read(target).contentEquals(bytes)) {"Conflicting chunk"}
                else {
                    FileChannel.open(pending,StandardOpenOption.WRITE).use {it.force(true)}
                    Files.move(pending,target,StandardCopyOption.ATOMIC_MOVE)
                }
                syncDirectory(directory)
            } catch(_: Exception) {issues+=ChunkIssue(pending.fileName.toString(),"Pending chunk could not be recovered; retained")}
        }
        Files.list(directory).use {stream->stream.filter {filename.matches(it.fileName.toString()) && !it.fileName.toString().endsWith(".pending")}
            .sorted().forEach {path->
                try {
                    val bytes=read(path);val chunk=verified(path,bytes,false)
                    sequences.getOrPut(chunk.streamId) {mutableListOf()}.add(Triple(chunk.sequence,chunk.samples.first().elapsedNanos,chunk.samples.last().elapsedNanos))
                    visit(chunk,bytes.size)
                }
                catch(_: Exception) {issues+=ChunkIssue(path.fileName.toString(),"Invalid committed chunk; retained")}
            }}
        sequences.forEach {(stream,sequences)->
            var expected=0L
            var previousTime=-1L
            sequences.sortedBy {it.first}.forEach {(sequence,first,last)->
                if(sequence!=expected) issues+=ChunkIssue("$stream-$sequence.motion","Sequence gap before chunk")
                if(first<=previousTime) issues+=ChunkIssue("$stream-$sequence.motion","Overlapping or regressed sensor timestamps")
                expected=if(sequence==Long.MAX_VALUE) Long.MAX_VALUE else sequence+1
                previousTime=last
            }
        }
        syncDirectory(directory)
        issues
    }
    private fun verified(path: Path,bytes: ByteArray,pending: Boolean): MotionChunk {
        val chunk=MotionChunkCodec.decode(bytes)
        require(chunk.sessionId==sessionId && path.fileName.toString()=="${chunk.streamId}-${chunk.sequence}.motion"+(if(pending) ".pending" else ""))
        return chunk
    }
    private fun read(path: Path): ByteArray {
        require(!Files.isSymbolicLink(path) && Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))
        Files.newInputStream(path).use {input->
            val buffer=ByteArrayOutputStream();val block=ByteArray(8192)
            while(true) {
                val n=input.read(block,0,minOf(block.size,MotionChunkCodec.MAX_BYTES+1-buffer.size()))
                if(n<0) break
                require(n>0);buffer.write(block,0,n);require(buffer.size()<=MotionChunkCodec.MAX_BYTES)
            }
            return buffer.toByteArray()
        }
    }
    companion object {private val locks=Array(64) {Any()}}
    private fun <T> locked(block: ()->T): T = synchronized(locks[(directory.toAbsolutePath().normalize().hashCode() and Int.MAX_VALUE)%locks.size]) {
        val missing=generateSequence(directory.toAbsolutePath().normalize()) {it.parent}.takeWhile {!Files.exists(it)}.toList()
        missing.asReversed().forEach {path->
            try {Files.createDirectory(path)} catch(_: FileAlreadyExistsException) {require(Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS))}
            syncDirectory(path.parent)
        }
        require(!Files.isSymbolicLink(directory))
        FileChannel.open(directory.resolve(".writer.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS).use {channel->
            channel.lock().use {return@synchronized block()}
        }
    }
}
