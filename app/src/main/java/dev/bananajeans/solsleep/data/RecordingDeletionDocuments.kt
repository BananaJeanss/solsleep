// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.data

import android.content.Context
import android.net.Uri
import dev.bananajeans.solsleep.core.*

/** User-chosen, single-document SAF access. No retained URI grant and no broad storage access. */
class RecordingDeletionDocuments(context: Context,private val databaseName: String="motion-capture.db") {
    private val context=context.applicationContext
    fun export(uri: Uri): RecordingDeletionPreview {
        val snapshot=CaptureStore(context,databaseName).use {it.deletionSnapshot()}
        val resolver=context.contentResolver
        return RecordingDeletionArchive.exportVerified(snapshot,
            {requireNotNull(resolver.openOutputStream(uri,"wt"))},
            {requireNotNull(resolver.openInputStream(uri))})
    }
    fun inspect(uri: Uri): RecordingDeletionPreview =
        requireNotNull(context.contentResolver.openInputStream(uri)).use(RecordingDeletionArchive::inspect)
}
