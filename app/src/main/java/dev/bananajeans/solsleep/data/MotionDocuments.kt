// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.data

import android.content.Context
import android.net.Uri
import android.os.UserManager
import android.os.StatFs
import android.system.Os
import android.system.OsConstants
import dev.bananajeans.solsleep.core.*
import java.io.*
import java.nio.channels.FileChannel
import java.nio.file.*
import java.util.UUID
import java.security.MessageDigest

class MotionSpaceException: IOException("Insufficient free storage for a recording recovery copy")

data class PendingMotion(val preview: MotionArchivePreview,val conflict: Boolean)

/** One durable recovery slot. Provider streams never enter live storage before full validation.
 * A failed restore retains its exact archive and already-published chunks for an identical retry.
 */
class MotionDocuments(context: Context,private val databaseName: String="motion-capture.db",
    private val directory: File=context.noBackupFilesDir.resolve("motion-import"),
    // Enforce an actual available-space floor; reclaimable cache is not a reserved safety margin.
    private val availableBytes: (File)->Long={StatFs(it.path).availableBytes},
    private val checkpoint: ()->Unit={}) {
    private val context=context.applicationContext
    companion object {private val lock=Any()}
    private fun exists(file: File)=Files.exists(file.toPath(),LinkOption.NOFOLLOW_LINKS)
    private fun space(file: File,required: Long) {if(availableBytes(file)<required) throw MotionSpaceException()}
    private val pending get()=directory.resolve("pending.solmotion")
    private val preparing get()=directory.resolve("preparing.solmotion")
    private fun sync(path: Path) {
        val fd=Os.open(path.toString(),OsConstants.O_RDONLY,0)
        try {check(OsConstants.S_ISDIR(Os.fstat(fd).st_mode));Os.fsync(fd)} finally {Os.close(fd)}
    }
    private fun <T> locked(block: ()->T): T=synchronized(lock) {
        check(!context.isDeviceProtectedStorage && context.getSystemService(UserManager::class.java).isUserUnlocked)
        if(!directory.exists()) {check(directory.mkdir());sync(directory.parentFile!!.toPath())}
        require(!Files.isSymbolicLink(directory.toPath()))
        FileChannel.open(directory.resolve(".lock").toPath(),StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS).use {channel->
            channel.lock().use {block()}
        }
    }
    private fun read(file: File): MotionArchivePreview {
        require(!Files.isSymbolicLink(file.toPath()) && Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS))
        return file.inputStream().buffered().use {MotionArchive.read(it)}
    }
    private fun pendingPreview(): PendingMotion? {
        if(!exists(pending) && exists(preparing)) {
            read(preparing) // A complete interrupted copy can be recovered; partial bytes stay retained.
            FileChannel.open(preparing.toPath(),StandardOpenOption.WRITE).use {it.force(true)}
            Files.move(preparing.toPath(),pending.toPath(),StandardCopyOption.ATOMIC_MOVE);sync(directory.toPath())
        }
        if(!exists(pending)) return null
        val preview=read(pending)
        return CaptureStore(context,databaseName).use {PendingMotion(preview,it.restoreConflict(preview.recording))}
    }
    fun inspectPending(): PendingMotion?=locked {pendingPreview()}
    fun hasPending(): Boolean=locked {exists(pending) || exists(preparing)}
    fun stage(uri: Uri): PendingMotion=stage {requireNotNull(context.contentResolver.openInputStream(uri))}
    fun stage(open: ()->InputStream): PendingMotion=locked {
        check(!exists(pending) && !exists(preparing)) {"Review or discard the pending recovery copy first"}
        space(directory,MotionArchive.MAX_BYTES+MotionCapturePolicy.RESERVE_BYTES)
        open().use {input->FileOutputStream(preparing).use {out->
            var total=0L;val block=ByteArray(8192)
            while(true) {
                val n=input.read(block,0,minOf(block.size.toLong(),MotionArchive.MAX_BYTES+1-total).toInt())
                if(n<0) break
                require(n>0);total+=n;require(total<=MotionArchive.MAX_BYTES) {"Recording archive too large"}
                space(directory,MotionCapturePolicy.RESERVE_BYTES)
                out.write(block,0,n)
            }
            out.flush();out.fd.sync()
        }}
        requireNotNull(pendingPreview())
    }
    fun discard()=locked {
        listOf(pending,preparing).forEach {file->
            require(!Files.isSymbolicLink(file.toPath()))
            if(file.exists()) {check(file.isFile);Files.delete(file.toPath())}
        }
        sync(directory.toPath())
    }
    fun restore(expectedFingerprint: String): Boolean=locked {
        val preview=requireNotNull(pendingPreview()).preview
        check(preview.fingerprint==expectedFingerprint) {"Recovery copy changed"}
        space(directory,preview.rawBytes+MotionCapturePolicy.RESERVE_BYTES)
        val restored=CaptureStore(context,databaseName).use {store->
            store.restore(preview.recording) {
                val target=MotionFiles.forSession(context,preview.recording.session.id)
                val expected=mutableMapOf<Pair<UUID,Long>,ByteArray>()
                pending.inputStream().buffered().use {input->MotionArchive.read(input) {chunk,bytes->
                    expected[chunk.streamId to chunk.sequence]=MessageDigest.getInstance("SHA-256").digest(bytes)
                }}
                check(target.summary().issues.isEmpty()) {"Unresolved local raw files retained"}
                target.exportBytes {bytes->
                    val chunk=MotionChunkCodec.decode(bytes)
                    val hash=expected[chunk.streamId to chunk.sequence]
                    check(hash!=null && MessageDigest.isEqual(hash,MessageDigest.getInstance("SHA-256").digest(bytes))) {
                        "Unrelated or conflicting local raw files retained"
                    }
                }
                val published=pending.inputStream().buffered().use {input->MotionArchive.read(input) {chunk,_->
                    space(directory,MotionCapturePolicy.RESERVE_BYTES)
                    target.publish(chunk);checkpoint()
                }}
                check(published==preview) {"Recovery copy changed during publication"}
            }
        }
        // Retain until the caller can report success. A cleanup failure yields a retryable duplicate.
        Files.delete(pending.toPath());sync(directory.toPath());restored
    }
    fun export(id: UUID,uri: Uri): MotionArchivePreview=export(id,
        {requireNotNull(context.contentResolver.openOutputStream(uri,"wt"))},
        {requireNotNull(context.contentResolver.openInputStream(uri))})
    fun export(id: UUID,output: ()->OutputStream,input: ()->InputStream): MotionArchivePreview {
        // Separate disposable spool; it is never mistaken for a recoverable import.
        val spool=File.createTempFile("motion-export-",".solmotion",context.cacheDir)
        try {
            val recording=CaptureStore(context,databaseName).use {it.recording(id)}
            space(spool.parentFile!!,MotionArchive.MAX_BYTES+MotionCapturePolicy.RESERVE_BYTES)
            FileOutputStream(spool).use {out->
                MotionArchive.write(recording,out) {visit->MotionFiles.forSession(context,id).exportBytes(visit)}
                out.flush();out.fd.sync()
            }
            CaptureStore(context,databaseName).use {check(it.recording(id)==recording) {"Recording changed during snapshot"}}
            return MotionArchive.exportVerified({spool.inputStream().buffered()},output,input)
        } finally {spool.delete()}
    }
}
