// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.data

import android.content.Context
import android.os.UserManager
import android.system.Os
import android.system.OsConstants
import dev.bananajeans.solsleep.core.*
import java.util.UUID

/** Credential-protected raw observations, never interpreted as physiological sleep measurements. */
object MotionFiles {
    /** An index may lag an interrupted publication; it must never silently claim missing files. */
    fun inspectSession(context: Context,store: CaptureStore,id: UUID): MotionSummary {
        val summary=forSession(context,id).summary()
        val streams=store.streams(id)
        val issues=summary.issues.toMutableList()
        streams.filter {(summary.streamSamples[it.id] ?: 0)<it.committedSamples}.forEach {
            issues+=ChunkIssue(it.id.toString(),"Saved progress has missing or invalid observations")
        }
        streams.forEach {stream->
            summary.streamAnchors[stream.id]?.let {anchor->
                if(anchor!=MotionStreamAnchor(stream.bootId,stream.wall,stream.elapsedNanos))
                    issues+=ChunkIssue(stream.id.toString(),"Source clock anchor does not match observations")
            }
        }
        if(summary.streamSamples.keys.any {id->streams.none {it.id==id}})
            issues+=ChunkIssue("source-metadata","Observations have no retained source metadata")
        return summary.copy(issues=issues)
    }
    fun forSession(context: Context,id: UUID,checkpoint: (ChunkCheckpoint)->Unit={}): MotionChunkStore {
        val credential=context.applicationContext
        check(!credential.isDeviceProtectedStorage) {"Credential storage required"}
        check(credential.getSystemService(UserManager::class.java).isUserUnlocked) {"Unlock required"}
        val path=credential.noBackupFilesDir.resolve("motion/v1/$id").toPath()
        return MotionChunkStore(path,id,syncDirectory={directory->
            val fd=Os.open(directory.toString(),OsConstants.O_RDONLY,0)
            try {check(OsConstants.S_ISDIR(Os.fstat(fd).st_mode));Os.fsync(fd)} finally {Os.close(fd)}
        },checkpoint=checkpoint)
    }
}
