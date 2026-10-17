// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.diagnostics

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.hardware.*
import android.os.*
import android.util.AtomicFile
import androidx.core.app.NotificationCompat
import dev.bananajeans.solsleep.R
import org.json.JSONObject
import java.io.File

/** Disposable callback statistics only. Absent from release source and manifest. */
class MotionProbeService : Service(),SensorEventListener {
    companion object {
        const val STOP="dev.bananajeans.solsleep.diagnostics.STOP"
        const val FILE="diagnostics/motion-probe.json"
        private const val CHANNEL="debug_motion_probe"
        private const val MAX_MS=5*60*1000L
    }
    private lateinit var worker: HandlerThread
    private lateinit var handler: Handler
    private lateinit var sensors: SensorManager
    private var wakeLock: PowerManager.WakeLock?=null
    private var count=0L
    private var first=0L
    private var last=0L
    private var regressions=0L
    private var active=false
    private var state="STARTING"
    private var expiry=0L
    private val checkpoint=object: Runnable {
        override fun run() {
            if(!active) return
            if(SystemClock.elapsedRealtime()>=expiry) {stopSelf();return}
            publish()
            handler.postDelayed(this,1000)
        }
    }
    override fun onCreate() {
        super.onCreate()
        worker=HandlerThread("solsleep-motion-probe").apply {start()};handler=Handler(worker.looper)
        sensors=getSystemService(SensorManager::class.java)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL,getString(R.string.probe_channel),NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        if(intent?.action==STOP) {stopSelf();return START_NOT_STICKY}
        val stop=PendingIntent.getService(this,0,Intent(this,MotionProbeService::class.java).setAction(STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification=NotificationCompat.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_moon)
            .setContentTitle(getString(R.string.probe_running)).setContentText(getString(R.string.probe_body))
            .setOngoing(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(0,getString(R.string.probe_stop),stop).build()
        startForeground(301,notification,if(Build.VERSION.SDK_INT>=34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        handler.post {
            if(active) return@post
            count=0;first=0;last=0;regressions=0
            val sensor=sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            active=sensor!=null && sensors.registerListener(this,sensor,100_000,handler)
            state=if(active) "RUNNING" else "UNAVAILABLE"
            if(active) {
                expiry=SystemClock.elapsedRealtime()+MAX_MS
                wakeLock=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"solsleep:motion-probe").apply {acquire(MAX_MS)}
                handler.post(checkpoint)
            } else {publish();stopSelf()}
        }
        return START_NOT_STICKY
    }
    override fun onSensorChanged(event: SensorEvent) {
        if(!active) return
        if(count==0L) first=event.timestamp else if(event.timestamp<=last) regressions++
        last=event.timestamp;count++
    }
    override fun onAccuracyChanged(sensor: Sensor?,accuracy: Int) = Unit
    private fun publish() {
        val file=File(filesDir,FILE).apply {parentFile!!.mkdirs()}
        val data=JSONObject().put("version",1).put("diagnosticOnly",true).put("state",state)
            .put("callbacks",count).put("firstSensorNs",first).put("lastSensorNs",last)
            .put("nonMonotonic",regressions).put("checkpointElapsedMs",SystemClock.elapsedRealtime()).toString().toByteArray(Charsets.UTF_8)
        val atomic=AtomicFile(file)
        var stream: java.io.FileOutputStream?=null
        try {stream=atomic.startWrite();stream.write(data);stream.fd.sync();atomic.finishWrite(stream)}
        catch(_: Exception) {atomic.failWrite(stream);stopSelf()}
    }
    override fun onDestroy() {
        handler.post {
            sensors.unregisterListener(this)
            active=false;handler.removeCallbacks(checkpoint)
            if(state=="RUNNING") state="STOPPED"
            publish()
            wakeLock?.let {if(it.isHeld) it.release()};wakeLock=null
            worker.quitSafely()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?) = null
}
