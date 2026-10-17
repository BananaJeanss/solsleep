// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.util.UUID

/** Test APK only: synthetic documents, no access to target app private files. */
class JournalDocumentProvider : ContentProvider() {
    override fun onCreate()=true
    private fun file(uri: Uri): File {
        val parts=uri.pathSegments
        require(parts.size==2 && parts[0] in setOf("normal","deny_write","deny_read","truncated","replaced"))
        require(UUID.fromString(parts[1]).toString()==parts[1])
        val root=File(requireNotNull(context).filesDir,"journal-provider-fixtures").apply {check(isDirectory || mkdirs())}
        return File(root,parts[1]+".json")
    }
    override fun openFile(uri: Uri,mode: String): ParcelFileDescriptor {
        val file=file(uri);val scenario=uri.pathSegments[0]
        if(mode=="r") {
            if(scenario=="deny_read") throw FileNotFoundException("Injected unavailable read")
            val readable=when(scenario) {
                "truncated"->File(file.path+".read").also {it.writeBytes(file.readBytes().let {bytes->bytes.copyOf(bytes.size/2)})}
                "replaced"->File(file.path+".read").also {
                    val payload="{\"records\":[],\"deletedIds\":[]}"
                    val hash=MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
                        .joinToString("") {b->"%02x".format(b.toInt() and 255)}
                    it.writeText("{\"format\":\"solsleep-journal\",\"version\":2,\"createdAt\":\"2026-10-17T08:00:00Z\",\"sha256\":\"$hash\",\"records\":[],\"deletedIds\":[]}")
                }
                else->file
            }
            return ParcelFileDescriptor.open(readable,ParcelFileDescriptor.MODE_READ_ONLY)
        }
        require(mode=="wt")
        if(scenario=="deny_write") throw FileNotFoundException("Injected unavailable write")
        return ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE)
    }
    override fun getType(uri: Uri)="application/json"
    override fun delete(uri: Uri,selection: String?,selectionArgs: Array<out String>?): Int {
        val file=file(uri);File(file.path+".read").delete();return if(file.delete()) 1 else 0
    }
    override fun query(uri: Uri,projection: Array<out String>?,selection: String?,selectionArgs: Array<out String>?,sortOrder: String?): Cursor? =
        throw UnsupportedOperationException("Fixture supports file transfer only")
    override fun insert(uri: Uri,values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun update(uri: Uri,values: ContentValues?,selection: String?,selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}
