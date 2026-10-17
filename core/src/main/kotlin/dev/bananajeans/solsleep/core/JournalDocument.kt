// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.core

import java.io.*
import java.time.Instant
import java.util.concurrent.CancellationException

/** The document was written and closed, but its destination readback did not verify. */
class JournalVerificationException(cause: Exception) : IOException("Journal document could not be verified",cause)

/** Bounded plaintext transfer. Readback is not a promise of future provider durability. */
object JournalDocument {
    fun exportVerified(
        snapshot: JournalSnapshot,
        openOutput: ()->OutputStream,
        openInput: ()->InputStream,
        createdAt: Instant=Instant.now(),
    ): JournalPreview {
        // Validate and freeze exact bytes before opening/truncating the destination.
        val bytes=ByteArrayOutputStream().also {JournalArchive.write(snapshot,it,createdAt)}.toByteArray()
        openOutput().use {out->out.write(bytes);out.flush()}
        try {
            val preview=openInput().use(JournalArchive::inspect)
            check(preview.fingerprint==sha256(bytes)) {"Destination differs from exported snapshot"}
            return preview
        } catch(c: CancellationException) {
            throw c
        } catch(e: Exception) {
            throw JournalVerificationException(e)
        }
    }
}
