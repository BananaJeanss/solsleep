// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.capture

import android.content.Context
import dev.bananajeans.solsleep.core.CaptureProvenance
import dev.bananajeans.solsleep.core.MotionCaptureBuffer
import java.io.File
import java.security.MessageDigest
import java.time.ZoneId

/** Called on the capture worker before establishing the clock anchor/listener.
 * Hash only our installed base APK; no device ID, personal file or signing key.
 */
internal object CaptureAcquisition {
    fun snapshot(context: Context): CaptureProvenance {
        val digest=MessageDigest.getInstance("SHA-256")
        File(context.applicationInfo.sourceDir).inputStream().use {input->
            val block=ByteArray(16384)
            while(true) {val count=input.read(block);if(count<0) break;check(count>0);digest.update(block,0,count)}
        }
        return CaptureProvenance(digest.digest().joinToString("") {"%02x".format(it)},
            ZoneId.systemDefault().id,MotionCaptureBuffer.PERIOD_MICROS)
    }
}
