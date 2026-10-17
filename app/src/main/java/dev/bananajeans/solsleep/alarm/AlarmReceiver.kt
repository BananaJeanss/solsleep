// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.alarm

import android.content.*
import androidx.core.content.ContextCompat

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        val token=intent.getStringExtra(AlarmIntents.TOKEN) ?: return
        if(intent.action !in setOf(AlarmIntents.FIRE,AlarmIntents.DISMISS,AlarmIntents.SNOOZE)) return
        val pending=goAsync()
        AlarmGraph.io.execute {
            try {
                val engine=AlarmGraph.engine(context)
                when(intent.action) {
                    AlarmIntents.FIRE -> {
                        if(engine.fire(token)!=null) RingingService.refresh(context)
                        else engine.reconcile()
                    }
                    AlarmIntents.DISMISS -> if(engine.dismiss(token)) RingingService.refresh(context)
                    AlarmIntents.SNOOZE -> if(engine.snooze(token)) RingingService.refresh(context)
                }
            } catch(_: Exception) {
                android.util.Log.e("SolSleepAlarm","Alarm operation failed; stored state was retained")
            } finally { pending.finish() }
        }
    }
}

class RecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        val supported=setOf(Intent.ACTION_LOCKED_BOOT_COMPLETED,Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,Intent.ACTION_TIME_CHANGED,Intent.ACTION_TIMEZONE_CHANGED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED")
        if(intent.action !in supported) return
        val pending=goAsync()
        AlarmGraph.io.execute {
            try {
                AlarmGraph.engine(context).reconcile(
                    clockChanged=intent.action in setOf(Intent.ACTION_TIME_CHANGED,Intent.ACTION_TIMEZONE_CHANGED),
                    recoverRinging=!RingingService.running,
                )
            } catch(_: Exception) {
                android.util.Log.e("SolSleepAlarm","Alarm operation failed; stored state was retained")
            } finally { pending.finish() }
        }
    }
}
