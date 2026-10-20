// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import dev.bananajeans.solsleep.R
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.bananajeans.solsleep.capture.*
import dev.bananajeans.solsleep.core.*
import dev.bananajeans.solsleep.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.UUID

data class CaptureDetails(val sessionId: UUID,val summary: MotionSummary,val streams: List<CaptureStream>,val events: List<CaptureEvent>,
    val coverage: MotionCoverageReport?=null,val coverageUnavailable: Boolean=false)
data class CaptureUiState(val sessions: List<CaptureSession> = emptyList(),val active: CaptureSession?=null,
    val page: Int=0,val older: Boolean=false,val busy: Boolean=true,val unavailable: Boolean=false,
    val message: Int?=null,val pending: PendingMotion?=null,val recoveryPresent: Boolean=false,val recoveryError: Boolean=false,
    val error: Boolean=false,val sensorAvailable: Boolean=false,val notifications: Boolean=false,val details: CaptureDetails?=null,
    val deletionPreview: RecordingDeletionPreview?=null,val deletionMergePreview: RecordingDeletionMerge?=null,
    val deletionResult: RecordingDeletionMerge?=null,
    val exportHistorySession: UUID?=null,val exportHistory: MotionExportSummary?=null,val exportHistoryUnavailable: Boolean=false)

class CaptureViewModel(app: Application): AndroidViewModel(app) {
    private val mutable=MutableStateFlow(CaptureUiState())
    val state=mutable.asStateFlow()
    private val mutex=Mutex()
    private var recoveryLoaded=false
    private val context get()=getApplication<Application>()
    private fun operation(block: suspend ()->Unit) {viewModelScope.launch {mutex.withLock {
        mutable.update {it.copy(busy=true,error=false)}
        try {block()} catch(c: CancellationException) {throw c}
        catch(e: Exception) {mutable.update {it.copy(error=true,message=if(e is MotionSpaceException) R.string.motion_archive_space else it.message)}}
        finally {mutable.update {it.copy(busy=false)}}
    }}}
    private suspend fun read(page: Int=mutable.value.page)=withContext(Dispatchers.IO) {
        if(!recoveryLoaded) {loadPending();recoveryLoaded=true}
        val boot=CaptureRuntime.bootId(context)
        CaptureStore(context).use {store->
            store.recoverOrphans(CaptureRuntime.liveOwner ?: CaptureRuntime.ownerId,Instant.ofEpochMilli(System.currentTimeMillis()),SystemClock.elapsedRealtimeNanos(),boot)
            val sessions=store.sessions(21,Math.multiplyExact(page,20))
            mutable.update {it.copy(sessions=sessions.take(20),active=store.active(),page=page,older=sessions.size>20,unavailable=false,
                sensorAvailable=context.getSystemService(SensorManager::class.java).getDefaultSensor(Sensor.TYPE_ACCELEROMETER)!=null,
                notifications=NotificationManagerCompat.from(context).areNotificationsEnabled() &&
                    context.getSystemService(android.app.NotificationManager::class.java).getNotificationChannel(MotionCaptureService.CHANNEL)?.importance!=android.app.NotificationManager.IMPORTANCE_NONE)}
        }
    }
    fun reload()=operation {
        try {read()} catch(c: CancellationException) {throw c}
        catch(error: Exception) {mutable.update {it.copy(unavailable=true)};throw error}
    }
    fun page(delta: Int)=operation {read((mutable.value.page+delta).coerceAtLeast(0))}
    fun start()=command(MotionCaptureService.START,UUID.randomUUID(),-1)
    fun resume(session: CaptureSession)=command(MotionCaptureService.RESUME,session.id,session.revision)
    fun pause(session: CaptureSession)=command(MotionCaptureService.PAUSE,session.id,session.revision)
    fun stop(session: CaptureSession)=command(MotionCaptureService.STOP,session.id,session.revision)
    private fun command(action: String,id: UUID,revision: Long)=operation {
        val intent=Intent(context,MotionCaptureService::class.java).setAction(action)
            .putExtra(MotionCaptureService.ID,id.toString()).putExtra(MotionCaptureService.REVISION,revision)
        CaptureRuntime.clearError()
        ContextCompat.startForegroundService(context,intent)
        delay(300);read(0) // Only durable state is displayed; polling observes later checkpoints.
    }
    fun inspect(session: CaptureSession)=operation {
        check(session.state in setOf(CaptureState.PAUSED,CaptureState.INTERRUPTED,CaptureState.COMPLETED,CaptureState.FAILED))
        val details=withContext(Dispatchers.IO) {
            CaptureStore(context).use {store->
                val summary=MotionFiles.inspectSession(context,store,session.id)
                var unavailable=false
                val coverage=if(session.state in setOf(CaptureState.COMPLETED,CaptureState.FAILED)) try {
                    MotionFiles.coverage(context,store,session.id)
                } catch(c: CancellationException) {throw c} catch(_: Exception) {unavailable=true;null} else null
                CaptureDetails(session.id,summary,store.streams(session.id),store.events(session.id),coverage,unavailable)
            }
        }
        mutable.update {it.copy(details=details)}
    }
    private suspend fun loadPending()=withContext(Dispatchers.IO) {
        val documents=MotionDocuments(context)
        try {
            val pending=documents.inspectPending()
            mutable.update {it.copy(pending=pending,recoveryPresent=pending!=null,recoveryError=false)}
        } catch(c: CancellationException) {throw c}
        catch(_: Exception) {mutable.update {it.copy(pending=null,recoveryPresent=true,recoveryError=true)}}
    }
    fun export(sessionId: UUID,uri: Uri)=operation {
        try {
            val result=withContext(Dispatchers.IO) {MotionDocuments(context).exportAudited(sessionId,uri)}
            when(result) {
                is AuditedMotionResult.Verified->mutable.update {it.copy(message=if(result.auditRecorded) R.string.motion_export_verified else R.string.motion_receipt_not_recorded)}
                is AuditedMotionResult.Failed->throw result.error
            }
        } catch(c: CancellationException) {throw c}
        catch(_: MotionSpaceException) {mutable.update {it.copy(message=R.string.motion_archive_space)}}
        catch(_: MotionVerificationException) {mutable.update {it.copy(message=R.string.motion_export_unverified)}}
        catch(_: Exception) {mutable.update {it.copy(message=R.string.motion_export_failed)}}
    }
    fun showExportHistory(sessionId: UUID)=operation {
        mutable.update {it.copy(exportHistorySession=sessionId,exportHistory=null,exportHistoryUnavailable=false)}
        val summary=withContext(Dispatchers.IO) {
            try {MotionExportHistory(context).use {it.summary(sessionId)}}
            catch(c: CancellationException) {throw c} catch(_: Exception) {null}
        }
        mutable.update {if(it.exportHistorySession==sessionId) it.copy(exportHistory=summary,exportHistoryUnavailable=summary==null) else it}
    }
    fun closeExportHistory() {mutable.update {it.copy(exportHistorySession=null,exportHistory=null,exportHistoryUnavailable=false)}}
    fun stage(uri: Uri)=operation {
        try {
            val pending=withContext(Dispatchers.IO) {MotionDocuments(context).stage(uri)}
            mutable.update {it.copy(pending=pending,recoveryPresent=true,recoveryError=false,message=null)}
        } finally {loadPending()}
    }
    fun restore()=operation {
        val fingerprint=requireNotNull(mutable.value.pending).preview.fingerprint
        try {
            val restored=withContext(Dispatchers.IO) {MotionDocuments(context).restore(fingerprint)}
            mutable.update {it.copy(message=if(restored) R.string.motion_restore_success else R.string.motion_restore_conflict)}
            read(0)
        } catch(c: CancellationException) {throw c}
        catch(_: MotionSpaceException) {mutable.update {it.copy(message=R.string.motion_archive_space)}}
        catch(_: Exception) {mutable.update {it.copy(message=R.string.motion_restore_failed)}}
        finally {loadPending()}
    }
    fun discardPending()=operation {
        withContext(Dispatchers.IO) {MotionDocuments(context).discard()}
        loadPending()
    }
    fun exportDeletionHistory(uri: Uri)=operation {
        try {
            withContext(Dispatchers.IO) {RecordingDeletionDocuments(context).export(uri)}
            mutable.update {it.copy(message=R.string.recording_deletions_exported)}
        } catch(c: CancellationException) {throw c}
        catch(_: RecordingDeletionVerificationException) {mutable.update {it.copy(message=R.string.recording_deletions_unverified)}}
        catch(_: Exception) {mutable.update {it.copy(message=R.string.recording_deletions_export_failed)}}
    }
    fun inspectDeletionHistory(uri: Uri)=operation {
        mutable.update {it.copy(deletionPreview=null,deletionMergePreview=null,deletionResult=null)}
        try {
            val (preview,result)=withContext(Dispatchers.IO) {
                val preview=RecordingDeletionDocuments(context).inspect(uri)
                preview to CaptureStore(context).use {it.mergeDeletionHistory(preview.snapshot,previewOnly=true)}
            }
            mutable.update {it.copy(deletionPreview=preview,deletionMergePreview=result)}
        } catch(c: CancellationException) {throw c}
        catch(_: Exception) {mutable.update {it.copy(message=R.string.recording_deletions_import_failed)}}
    }
    fun importDeletionHistory()=operation {
        val preview=requireNotNull(mutable.value.deletionPreview)
        val result=withContext(Dispatchers.IO) {CaptureStore(context).use {it.mergeDeletionHistory(preview.snapshot)}}
        mutable.update {it.copy(deletionPreview=null,deletionMergePreview=null,deletionResult=result,
            message=R.string.recording_deletions_imported)}
        // A previously staged raw recording may now conflict with an imported marker.
        loadPending();read()
    }
    fun closeDeletionPreview() {
        if(!mutable.value.busy) mutable.update {it.copy(deletionPreview=null,deletionMergePreview=null)}
    }
    fun closeDetails() {mutable.update {it.copy(details=null)}}
    fun delete(session: CaptureSession)=operation {
        withContext(Dispatchers.IO) {
            CaptureStore(context).use {store->
                if(session.state!=CaptureState.DELETING) store.transition(session.id,session.revision,CaptureState.DELETING,CaptureReason.DELETE,
                    CaptureRuntime.ownerId,Instant.ofEpochMilli(System.currentTimeMillis()),SystemClock.elapsedRealtimeNanos(),CaptureRuntime.bootId(context))
                else check(store.get(session.id)?.state==CaptureState.DELETING)
                // Commit deletion intent before removing any associated metadata.
                // A damaged receipt database cannot veto raw-record deletion.
                try {MotionExportHistory(context).use {it.deleteForSession(session.id)}}
                catch(c: CancellationException) {throw c}
                catch(_: Exception) {mutable.update {it.copy(message=R.string.motion_receipt_delete_unavailable)}}
                MotionFiles.forSession(context,session.id).eraseOwned();store.finishDelete(session.id)
            }
        }
        mutable.update {it.copy(details=null)};read()
    }
}
