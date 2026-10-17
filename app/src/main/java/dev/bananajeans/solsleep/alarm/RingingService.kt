// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.alarm

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.bananajeans.solsleep.R
import dev.bananajeans.solsleep.core.*
import java.time.*

class RingingService : Service() {
    companion object {
        const val CHANNEL="wake_alarms_v1"
        private const val NOTIFICATION=101
        @Volatile var running=false;private set
        fun refresh(context: Context) {
            ContextCompat.startForegroundService(context,Intent(context,RingingService::class.java))
        }
        fun createChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL,context.getString(R.string.alarms_channel),NotificationManager.IMPORTANCE_HIGH).apply {
                    description=context.getString(R.string.alarms_channel_description)
                    setSound(null,null);enableVibration(false);lockscreenVisibility=Notification.VISIBILITY_PRIVATE
                })
        }
    }
    private val handler=Handler(Looper.getMainLooper())
    private var audio: AlarmAudio?=null
    private var token: String?=null
    private var wakeLock: PowerManager.WakeLock?=null
    private var destroyed=false
    private var generation=0
    private var elapsedDeadline=Long.MAX_VALUE
    private var latestStartId=0
    private val poll=Runnable {refreshActive()}

    override fun onCreate() {
        super.onCreate();createChannel(this)
        promote()
        // Observers must not treat a merely constructed service as foreground-ready.
        running=true
    }
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        latestStartId=startId
        promote()
        refreshActive()
        return START_STICKY
    }
    private fun promote()=startForeground(NOTIFICATION,notification(null),ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    private fun stopCurrent(startId: Int) {
        // A newer start may already be in ActivityManager but not delivered to this main thread.
        // Unconditional stopSelf can then crash the process for an unacknowledged foreground start.
        if(stopSelfResult(startId)) stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun refreshActive() {
        handler.removeCallbacks(poll)
        val request=++generation
        val startId=latestStartId
        val timedOut=token?.takeIf {SystemClock.elapsedRealtime()>=elapsedDeadline}
        AlarmGraph.io.execute {
            val outcome=runCatching {
                val engine=AlarmGraph.engine(this)
                timedOut?.let {engine.timeout(it)}
                engine.ringing().minByOrNull {it.pending!!.let {p->p.startedAt ?: p.at}}
            }
            handler.post {
                if(destroyed || request!=generation) return@post
                if(outcome.isFailure) {
                    // A transient storage read failure must not silence already validated output.
                    if(audio!=null && SystemClock.elapsedRealtime()<elapsedDeadline)
                        handler.postDelayed(poll,5000)
                    else stopCurrent(startId)
                    return@post
                }
                val active=outcome.getOrNull()
                if(active==null) {
                    stopCurrent(startId);return@post
                }
                val p=active.pending!!
                if(token!=p.token) {
                    stopOutput()
                    token=p.token
                    val remaining=(AlarmEngine.RING_LIMIT.toMillis() -
                        Duration.between(p.startedAt ?: p.at,Instant.now()).toMillis()).coerceIn(1000,AlarmEngine.RING_LIMIT.toMillis())
                    elapsedDeadline=SystemClock.elapsedRealtime()+remaining
                    wakeLock=getSystemService(PowerManager::class.java)
                        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"SolSleep:alarm").apply {acquire(remaining+5000)}
                    audio=AlarmAudio(this,active.rule.rampSeconds).also {it.start()}
                    if(active.rule.vibrate) vibrate()
                }
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION,notification(active))
                handler.postDelayed(poll,5000)
            }
        }
    }

    private fun vibrate() {
        val v=if(Build.VERSION.SDK_INT>=31) getSystemService(VibratorManager::class.java).defaultVibrator
            else { @Suppress("DEPRECATION") (getSystemService(VIBRATOR_SERVICE) as Vibrator) }
        val effect=VibrationEffect.createWaveform(longArrayOf(0,500,300,500,1800),0)
        if(Build.VERSION.SDK_INT>=33) v.vibrate(effect,
            VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_ALARM).build())
        else {
            @Suppress("DEPRECATION")
            v.vibrate(effect,AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        }
    }

    private fun notification(entry: AlarmEntry?): Notification {
        val p=entry?.pending
        val builder=NotificationCompat.Builder(this,CHANNEL)
            .setSmallIcon(R.drawable.ic_alarm).setContentTitle(entry?.rule?.label?.ifBlank {getString(R.string.alarm)} ?: getString(R.string.alarm))
            .setContentText(getString(R.string.wake_up)).setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOngoing(true).setSilent(true)
        if(p!=null) {
            val open=AlarmIntents.open(this,p.token)
            builder.setContentIntent(open).setFullScreenIntent(open,true)
                .setPublicVersion(NotificationCompat.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_alarm)
                    .setContentTitle(getString(R.string.alarm)).build())
            if(p.snoozes<entry.rule.snoozeLimit) builder.addAction(0,
                getString(R.string.snooze_minutes,entry.rule.snoozeMinutes),
                AlarmIntents.broadcast(this,p.token,AlarmIntents.SNOOZE))
            builder.addAction(0,getString(R.string.dismiss),AlarmIntents.broadcast(this,p.token,AlarmIntents.DISMISS))
        }
        return builder.build()
    }
    private fun stopOutput() {
        audio?.close();audio=null
        if(Build.VERSION.SDK_INT>=31) getSystemService(VibratorManager::class.java).cancel()
        else { @Suppress("DEPRECATION") (getSystemService(VIBRATOR_SERVICE) as Vibrator).cancel() }
        wakeLock?.let {if(it.isHeld) it.release()};wakeLock=null
    }
    override fun onDestroy() {
        destroyed=true;running=false;handler.removeCallbacksAndMessages(null);stopOutput()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?) : IBinder?=null
}
