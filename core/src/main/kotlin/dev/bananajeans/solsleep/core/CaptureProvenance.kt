// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.core

import java.time.ZoneId
import kotlinx.serialization.json.*

/** Snapshot of this acquisition, not inferred from today's settings after import/migration.
 * The hash identifies the installed base APK, not a device or a complete split-APK set.
 */
data class CaptureProvenance(val baseApkSha256: String,val zoneId: String,
    val requestedPeriodMicros: Int,val version: Int=1) {
    init {
        require(version==1 && baseApkSha256.matches(Regex("[0-9a-f]{64}")))
        require(zoneId.length in 1..128 && zoneId.none {it.isISOControl()})
        ZoneId.of(zoneId)
        require(requestedPeriodMicros in 1..60_000_000)
    }
}

object CaptureProvenanceCodec {
    const val MAX_BYTES=1024
    fun encode(value: CaptureProvenance): String=buildJsonObject {
        put("version",value.version);put("baseApkSha256",value.baseApkSha256)
        put("zoneId",value.zoneId);put("requestedPeriodMicros",value.requestedPeriodMicros)
    }.toString().also {require(it.toByteArray().size<=MAX_BYTES)}
    fun decode(text: String): CaptureProvenance {
        require(text.length<=MAX_BYTES && text.toByteArray().size<=MAX_BYTES)
        val root=strictJson(text,1)
        require(root.keys==setOf("version","baseApkSha256","zoneId","requestedPeriodMicros"))
        require(root.number("version")==1L)
        val period=root.number("requestedPeriodMicros");require(period in 1..60_000_000L)
        return CaptureProvenance(root.text("baseApkSha256"),root.text("zoneId"),period.toInt())
    }
}
