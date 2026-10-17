// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.ui

import android.app.Application
import android.content.Intent
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

data class CaptureDetails(val sessionId: UUID,val summary: MotionSummary,val streams: List<CaptureStream>,val events: List<CaptureEvent>)
data class CaptureUiState(val sessions: List<CaptureSession> = emptyList(),val active: CaptureSession?=null,
    val page: Int=0,val older: Boolean=false,val busy: Boolean=true,val unavailable: Boolean=false,
    val error: Boolean=false,val sensorAvailable: Boolean=false,val notifications: Boolean=false,val details: CaptureDetails?=null)

class CaptureViewModel(app: Application): AndroidViewModel(app) {
    private val mutable=MutableStateFlow(CaptureUiState())
    val state=mutable.asStateFlow()
    private val mutex=Mutex()
    private val context get()=getApplication<Application>()
    private fun operation(block: suspend ()->Unit) {viewModelScope.launch {mutex.withLock {
        mutable.update {it.copy(busy=true,error=false)}
        try {block()} catch(c: CancellationException) {throw c}
        catch(_: Exception) {mutable.update {it.copy(error=true)}}
        finally {mutable.update {it.copy(busy=false)}}
    }}}
    private suspend fun read(page: Int=mutable.value.page)=withContext(Dispatchers.IO) {
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
            CaptureStore(context).use {CaptureDetails(session.id,MotionFiles.inspectSession(context,it,session.id),it.streams(session.id),it.events(session.id))}
        }
        mutable.update {it.copy(details=details)}
    }
    fun closeDetails() {mutable.update {it.copy(details=null)}}
    fun delete(session: CaptureSession)=operation {
        withContext(Dispatchers.IO) {
            CaptureStore(context).use {store->
                if(session.state!=CaptureState.DELETING) store.transition(session.id,session.revision,CaptureState.DELETING,CaptureReason.DELETE,
                    CaptureRuntime.ownerId,Instant.ofEpochMilli(System.currentTimeMillis()),SystemClock.elapsedRealtimeNanos(),CaptureRuntime.bootId(context))
                else check(store.get(session.id)?.state==CaptureState.DELETING)
                MotionFiles.forSession(context,session.id).eraseOwned();store.finishDelete(session.id)
            }
        }
        mutable.update {it.copy(details=null)};read()
    }
}
