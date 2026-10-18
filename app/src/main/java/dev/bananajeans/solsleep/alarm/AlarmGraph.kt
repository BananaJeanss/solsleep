// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.alarm

import android.app.*
import android.content.*
import android.media.AudioManager
import androidx.core.net.toUri
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import dev.bananajeans.solsleep.MainActivity
import dev.bananajeans.solsleep.core.*
import dev.bananajeans.solsleep.data.AlarmStore
import java.util.concurrent.Executors

object AlarmGraph {
    val io = Executors.newSingleThreadExecutor()
    @Volatile private var engine: AlarmEngine? = null
    fun engine(context: Context): AlarmEngine = engine ?: synchronized(this) {
        engine ?: AlarmEngine(AlarmStore(context.applicationContext),
            AndroidWakeScheduler(context.applicationContext)).also { engine=it }
    }
}

object AlarmIntents {
    const val FIRE="dev.bananajeans.solsleep.FIRE"
    const val SNOOZE="dev.bananajeans.solsleep.SNOOZE"
    const val DISMISS="dev.bananajeans.solsleep.DISMISS"
    const val TOKEN="token"
    fun broadcast(context: Context, token: String, action: String, flags: Int = PendingIntent.FLAG_UPDATE_CURRENT): PendingIntent? =
        PendingIntent.getBroadcast(context,0,
            Intent(context,AlarmReceiver::class.java).setAction(action)
                .setData(("solsleep://alarm/"+token+"/"+action).toUri()).putExtra(TOKEN,token),
            flags or PendingIntent.FLAG_IMMUTABLE)

    fun open(context: Context, token: String, action: String?=null): PendingIntent = PendingIntent.getActivity(context,0,
        Intent(context,RingingActivity::class.java).setData(("solsleep://ring/"+token+"/"+(action ?: "open")).toUri()).putExtra(TOKEN,token).setAction(action)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}

class AndroidWakeScheduler(private val context: Context) : WakeScheduler {
    private val manager=context.getSystemService(AlarmManager::class.java)
    override fun canSchedule() = Build.VERSION.SDK_INT<31 || manager.canScheduleExactAlarms()
    override fun schedule(rule: AlarmRule,pending: PendingAlarm) {
        if(!canSchedule()) throw SecurityException("Exact alarms are unavailable")
        val show=PendingIntent.getActivity(context,0,
            Intent(context,MainActivity::class.java).setAction("android.intent.action.SHOW_ALARMS"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        // Keep the original deadline in storage. Recovery may dispatch it just after now.
        val trigger=maxOf(pending.at.toEpochMilli(),System.currentTimeMillis()+500)
        manager.setAlarmClock(AlarmManager.AlarmClockInfo(trigger,show),
            requireNotNull(AlarmIntents.broadcast(context,pending.token,AlarmIntents.FIRE)))
    }
    override fun cancel(token: String) {
        AlarmIntents.broadcast(context,token,AlarmIntents.FIRE,PendingIntent.FLAG_NO_CREATE)?.let {
            manager.cancel(it);it.cancel()
        }
    }
}

data class AlarmReadiness(val exact: Boolean,val notifications: Boolean,val fullScreen: Boolean,val audible: Boolean) {
    val ready get()=exact && notifications && fullScreen && audible
}
fun readiness(context: Context): AlarmReadiness {
    val nm=context.getSystemService(NotificationManager::class.java)
    val channel=nm.getNotificationChannel(RingingService.CHANNEL)
    return AlarmReadiness(
        AndroidWakeScheduler(context).canSchedule(),
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            channel?.importance != NotificationManager.IMPORTANCE_NONE,
        Build.VERSION.SDK_INT<34 || nm.canUseFullScreenIntent(),
        context.getSystemService(AudioManager::class.java).getStreamVolume(AudioManager.STREAM_ALARM)>0,
    )
}
