// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.data

import android.content.Context
import android.os.UserManager
import android.system.Os
import android.system.OsConstants
import dev.bananajeans.solsleep.core.*
import java.util.UUID

/** Raw-data foundation only; no production recorder or physiological analysis is connected yet. */
object MotionFiles {
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
