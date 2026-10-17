// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.capture

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.*
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import dev.bananajeans.solsleep.R
import dev.bananajeans.solsleep.core.*
import dev.bananajeans.solsleep.data.*
import java.time.Instant
import java.util.UUID

/** Explicit user-started phone accelerometer observations. No automatic boot/restart, microphone,
 * sleep/stage inference, or modifications to the independent hard alarm.
 */
class MotionCaptureService: Service(),SensorEventListener {
    companion object {
        const val START="dev.bananajeans.solsleep.capture.START"
        const val RESUME="dev.bananajeans.solsleep.capture.RESUME"
        const val PAUSE="dev.bananajeans.solsleep.capture.PAUSE"
        const val STOP="dev.bananajeans.solsleep.capture.STOP"
        const val ID="id"
        const val REVISION="revision"
        const val CHANNEL="motion_capture"
        const val NOTIFICATION=302
    }
    private val ownerId=UUID.randomUUID()
    private lateinit var handler: Handler
    private lateinit var sensors: SensorManager
    private lateinit var store: CaptureStore
    private var current: CaptureSession?=null
    private var stream: CaptureStream?=null
    private var buffer: MotionCaptureBuffer?=null
    private var wakeLock: PowerManager.WakeLock?=null
    private var accepting=false
    private var rawBytes=0L
    private var lastFlush=0L
    private var accuracy: Int?=null
    private var latestCommand=0
    private class CaptureStopped(val reason: CaptureReason): Exception()
    private fun wall()=Instant.ofEpochMilli(System.currentTimeMillis())
    private fun elapsed()=SystemClock.elapsedRealtimeNanos()
    private fun boot()=CaptureRuntime.bootId(this)
    private val tick=object: Runnable {
        override fun run() {
            if(!accepting) return
            try {
                val source=requireNotNull(stream);val now=elapsed()
                checkNotifications()
                MotionCapturePolicy.interruption(source.wall.toEpochMilli(),source.elapsedNanos,buffer?.lastElapsedNanos,System.currentTimeMillis(),now)?.let {throw CaptureStopped(it)}
                if(now-lastFlush>=MotionCaptureBuffer.CHECKPOINT_NANOS) {flush();lastFlush=now}
                checkSpace()
                handler.postDelayed(this,1000)
            } catch(error: Exception) {interrupt(error)}
        }
    }
    override fun onCreate() {
        super.onCreate()
        handler=CaptureRuntime.handler;CaptureRuntime.opened(ownerId)
        sensors=getSystemService(SensorManager::class.java);store=CaptureStore(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL,getString(R.string.capture_channel),NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        val action=intent?.action
        if(action !in setOf(START,RESUME,PAUSE,STOP)) {stopSelfResult(startId);return START_NOT_STICKY}
        try {
            // Keep the platform deadline independent of any database/file work on the worker.
            startForeground(NOTIFICATION,notification(null),if(Build.VERSION.SDK_INT>=34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        } catch(_: Exception) {CaptureRuntime.failed();stopSelfResult(startId);return START_NOT_STICKY}
        handler.post {
            latestCommand=startId
            try {
                val id=UUID.fromString(requireNotNull(intent).getStringExtra(ID))
                val revision=intent.getLongExtra(REVISION,-1)
                when(action) {
                    START->start(id)
                    RESUME->resume(id,revision)
                    PAUSE,STOP-> {
                        val saved=store.get(id)
                        if(saved!=null && CaptureTransitions.acceptsControl(saved,revision) && !saved.terminal) {
                            if(current?.id==id) finish(if(action==PAUSE) CaptureState.PAUSED else CaptureState.COMPLETED,
                                if(action==PAUSE) CaptureReason.PAUSE else CaptureReason.STOP)
                            else if(action==STOP && saved.state in setOf(CaptureState.PAUSED,CaptureState.INTERRUPTED))
                                store.transition(id,revision,CaptureState.COMPLETED,CaptureReason.STOP,ownerId,wall(),elapsed(),boot())
                        }
                    }
                }
                if(current==null) {stopForeground(STOP_FOREGROUND_REMOVE);stopSelfResult(startId)} else updateNotification()
            } catch(error: Exception) {CaptureRuntime.failed();interrupt(error)}
        }
        return START_NOT_STICKY
    }
    private fun start(request: UUID) {
        if(current!=null) return // A repeated tap cannot create another sensor registration.
        store.recoverOrphans(ownerId,wall(),elapsed(),boot())
        val saved=store.start(request,ownerId,wall(),elapsed(),boot())
        if(saved.terminal || saved.ownerId!=ownerId || saved.state!=CaptureState.PREPARING) return
        current=saved;prepare()
    }
    private fun resume(id: UUID,revision: Long) {
        if(current!=null) return
        store.recoverOrphans(ownerId,wall(),elapsed(),boot())
        val saved=store.get(id) ?: return
        if(saved.revision!=revision || saved.state !in setOf(CaptureState.PAUSED,CaptureState.INTERRUPTED)) return
        current=store.transition(id,revision,CaptureState.PREPARING,CaptureReason.RESUME,ownerId,wall(),elapsed(),boot())
        prepare()
    }
    private fun prepare() {
        checkNotifications()
        val session=requireNotNull(current)
        val sensor=sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: throw CaptureStopped(CaptureReason.NO_SENSOR)
        val files=MotionFiles.forSession(this,session.id)
        val summary=MotionFiles.inspectSession(this,store,session.id);check(summary.issues.isEmpty()) {"Damaged observations retained"};rawBytes=summary.bytes
        checkSpace()
        val source=CaptureStream(UUID.randomUUID(),session.id,boot(),wall(),elapsed(),
            CaptureSource(sensor.name,sensor.vendor,sensor.version,sensor.resolution,sensor.maximumRange,sensor.isWakeUpSensor),0,0,0)
        store.openStream(source,ownerId);stream=source
        buffer=MotionCaptureBuffer(session.id,source.id,source.bootId,source.wall,source.elapsedNanos) {chunk->
            val size=MotionChunkCodec.encode(chunk).size
            checkSpace(size);files.publish(chunk);rawBytes+=size
        }
        accuracy=null;lastFlush=source.elapsedNanos
        accepting=sensors.registerListener(this,sensor,MotionCaptureBuffer.PERIOD_MICROS,handler)
        if(!accepting) throw CaptureStopped(CaptureReason.NO_SENSOR)
        wakeLock=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"solsleep:phone-motion").apply {
            setReferenceCounted(false)
            acquire(MotionCapturePolicy.MAX_NANOS/1_000_000+60_000)
        }
        CaptureRuntime.clearError();handler.postDelayed(tick,1000)
    }
    private fun checkSpace(nextBytes: Int=0) {
        if(!MotionCapturePolicy.hasSpace(StatFs(noBackupFilesDir.absolutePath).availableBytes,rawBytes,nextBytes))
            throw CaptureStopped(CaptureReason.LOW_SPACE)
    }
    private fun checkNotifications() {
        if(!NotificationManagerCompat.from(this).areNotificationsEnabled() ||
            getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL).importance==NotificationManager.IMPORTANCE_NONE)
            throw CaptureStopped(CaptureReason.SETUP)
    }
    override fun onSensorChanged(event: SensorEvent) {
        if(!accepting || event.sensor.type!=Sensor.TYPE_ACCELEROMETER) return
        try {
            val value=requireNotNull(buffer)
            val previous=value.lastElapsedNanos
            val sample=MotionSample(event.timestamp,event.values[0],event.values[1],event.values[2])
            if(event.timestamp>elapsed()+1_000_000_000L) throw CaptureStopped(CaptureReason.INVALID_SAMPLE)
            val reason=value.append(sample)
            if(reason==CaptureReason.SENSOR_GAP) store.note(requireNotNull(current).id,reason,wall(),elapsed(),boot(),sample.elapsedNanos-requireNotNull(previous))
            if(accuracy!=event.accuracy) {
                accuracy=event.accuracy;store.note(requireNotNull(current).id,CaptureReason.ACCURACY,wall(),elapsed(),boot(),accuracy?.toLong())
            }
            acknowledge()
        } catch(error: Exception) {interrupt(error)}
    }
    override fun onAccuracyChanged(sensor: Sensor?,accuracy: Int)=Unit // SensorEvent.accuracy is recorded on actual observations.
    private fun flush() {buffer?.flush();acknowledge()}
    private var acknowledgedSamples=-1L
    private var acknowledgedRejected=-1L
    private fun acknowledge() {
        val value=buffer ?: return
        if(value.committedSamples==acknowledgedSamples && value.rejectedSamples==acknowledgedRejected) return
        store.checkpoint(requireNotNull(stream).id,value.committedSamples,value.rejectedSamples,value.gapCount)
        acknowledgedSamples=value.committedSamples;acknowledgedRejected=value.rejectedSamples
        val saved=requireNotNull(current)
        if(saved.state==CaptureState.PREPARING && value.committedSamples>0) {
            current=store.transition(saved.id,saved.revision,CaptureState.RECORDING,CaptureReason.START,ownerId,wall(),elapsed(),boot())
            if(accepting) updateNotification()
        }
    }
    private fun finish(to: CaptureState,reason: CaptureReason) {
        accepting=false;handler.removeCallbacks(tick)
        try {sensors.unregisterListener(this)} catch(_: Exception) {CaptureRuntime.failed()}
        var target=to;var actualReason=reason
        try {flush()} catch(_: Exception) {
            target=CaptureState.INTERRUPTED
            if(reason in setOf(CaptureReason.STOP,CaptureReason.PAUSE)) actualReason=CaptureReason.STORAGE
            CaptureRuntime.failed()
        }
        try {
            current?.let {saved->
                current=store.transition(saved.id,saved.revision,target,actualReason,ownerId,wall(),elapsed(),boot())
            }
        } finally {
            buffer=null;stream=null;current=null;acknowledgedSamples=-1;acknowledgedRejected=-1
            wakeLock?.let {if(it.isHeld) it.release()};wakeLock=null
        }
    }
    private fun interrupt(error: Exception) {
        CaptureRuntime.failed()
        try {
            val reason=(error as? CaptureStopped)?.reason ?: CaptureReason.STORAGE
            finish(if(current?.state==CaptureState.PREPARING && reason==CaptureReason.NO_SENSOR) CaptureState.FAILED else CaptureState.INTERRUPTED,reason)
        } catch(_: Exception) { /* Preserve inaccessible database and raw files; UI exposes unavailable state. */ }
        stopForeground(STOP_FOREGROUND_REMOVE);stopSelfResult(latestCommand)
    }
    private fun notification(session: CaptureSession?): Notification {
        val open=PendingIntent.getActivity(this,0,Intent(this,MotionCaptureActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder=NotificationCompat.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_moon)
            .setContentTitle(getString(if(session?.state==CaptureState.RECORDING) R.string.capture_recording else R.string.capture_preparing))
            .setContentText(getString(R.string.capture_notification_body)).setContentIntent(open)
            .setOngoing(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setOnlyAlertOnce(true)
        if(session!=null) {
            val stop=PendingIntent.getService(this,0,Intent(this,MotionCaptureService::class.java).setAction(STOP)
                .setData("solsleep://capture/${session.id}/${session.revision}/stop".toUri())
                .putExtra(ID,session.id.toString()).putExtra(REVISION,session.revision),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            builder.addAction(0,getString(R.string.capture_stop),stop)
        }
        return builder.build()
    }
    private fun updateNotification() {
        // startForeground updates an existing notification without requiring a suppressed notify call.
        startForeground(NOTIFICATION,notification(current),if(Build.VERSION.SDK_INT>=34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
    }
    override fun onDestroy() {
        handler.post {
            try {if(current!=null) finish(CaptureState.INTERRUPTED,CaptureReason.SERVICE_STOP)}
            catch(_: Exception) {CaptureRuntime.failed()}
            finally {try {store.close()} finally {CaptureRuntime.closed(ownerId)}}
        }
        super.onDestroy()
    }
    override fun onBind(intent: Intent?)=null
}
