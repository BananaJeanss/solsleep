// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.diagnostics

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream

/** Same-process diagnostic store. AtomicFile requires callers to serialize reads AND writes. */
object MotionProbeSnapshots {
    private val lock=Any()
    private fun file(context: Context)=AtomicFile(File(context.filesDir,MotionProbeService.FILE))
    fun read(context: Context): ByteArray = synchronized(lock) {file(context).readFully()}
    fun clear(context: Context) = synchronized(lock) {file(context).delete()}
    fun write(context: Context,data: ByteArray) = synchronized(lock) {
        val atomic=file(context)
        atomic.baseFile.parentFile!!.mkdirs()
        var stream: FileOutputStream?=null
        try {
            stream=atomic.startWrite();stream.write(data);stream.fd.sync();atomic.finishWrite(stream)
        } catch(e: Exception) {atomic.failWrite(stream);throw e}
    }
}
