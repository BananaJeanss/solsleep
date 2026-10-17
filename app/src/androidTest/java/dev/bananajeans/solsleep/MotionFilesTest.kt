// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.bananajeans.solsleep.core.*
import dev.bananajeans.solsleep.data.MotionFiles
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.IOException
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MotionFilesTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val id=UUID.randomUUID()
    private val chunk=MotionChunk(id,UUID.randomUUID(),UUID.randomUUID(),0,Instant.ofEpochMilli(1000),2000,
        listOf(MotionSample(2100,1.5f,0f,9.8f),MotionSample(2200,2f,1f,9.7f)))
    private val directory get()=context.applicationContext.noBackupFilesDir.resolve("motion/v1/$id")
    @After fun cleanup() {directory.deleteRecursively()}
    @Test fun realAndroidFileAndDirectoryFsyncPublishAndReopenExactSamples() {
        MotionFiles.forSession(context,id).publish(chunk)
        assertEquals(listOf(chunk),MotionFiles.forSession(context,id).recover().chunks)
        assertFalse(context.createDeviceProtectedStorageContext().noBackupFilesDir.resolve("motion/v1/$id").exists())
    }
    @Test fun realFilesystemRecoveryAtEveryPublicationBoundary() {
        ChunkCheckpoint.entries.forEachIndexed {index,point->
            val next=chunk.copy(sequence=index.toLong(),samples=chunk.samples.map {it.copy(elapsedNanos=it.elapsedNanos+index*1000L)})
            try {MotionFiles.forSession(context,id) {if(it==point) throw IOException("injected")}.publish(next);fail("Expected interruption")}
            catch(_: IOException) { }
            val result=MotionFiles.forSession(context,id).recover()
            assertEquals(index+1,result.chunks.size);assertTrue(result.issues.isEmpty())
            assertEquals(next,result.chunks.last())
        }
    }
}
