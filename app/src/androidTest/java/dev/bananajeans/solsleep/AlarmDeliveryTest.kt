// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep

import android.app.AlarmManager
import android.content.*
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.bananajeans.solsleep.alarm.*
import dev.bananajeans.solsleep.core.*
import dev.bananajeans.solsleep.data.AlarmStore
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.*
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AlarmDeliveryTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private val id="delivery-test-"+UUID.randomUUID()
    private fun shell(command: String) {instrumentation.uiAutomation.executeShellCommand(command).use {
        java.io.FileInputStream(it.fileDescriptor).use {s->s.readBytes()}
    }}
    @After fun cleanup() {
        val engine=AlarmGraph.engine(context)
        engine.entries().filter {it.rule.id==id}.forEach {e->
            e.pending?.takeIf {it.state==DeliveryState.RINGING}?.let {engine.dismiss(it.token)}
            engine.delete(e.rule.id)
        }
        context.stopService(Intent(context,RingingService::class.java))
    }
    @Test fun exactAlarmStartsTheRealReceiverAndServiceThenSnoozesDurably() {
        shell("appops set "+context.packageName+" SCHEDULE_EXACT_ALARM allow")
        shell("pm grant "+context.packageName+" android.permission.POST_NOTIFICATIONS")
        val now=Instant.now()
        val rule=AlarmRule(id,LocalTime.of(7,0),Repeat.Once(LocalDate.now()),label="Delivery verification",rampSeconds=0)
        val pending=PendingAlarm(UUID.randomUUID().toString(),now.plusMillis(2000),LocalDate.now())
        AlarmStore(context).use {it.put(rule,pending)}
        val scheduler=AndroidWakeScheduler(context)
        assertTrue("Test device must grant exact-alarm access",scheduler.canSchedule())
        scheduler.schedule(rule,pending)
        await {AlarmGraph.engine(context).entries().find {it.rule.id==id}?.pending?.state==DeliveryState.RINGING && RingingService.running}
        val fired=AlarmGraph.engine(context).entries().first {it.rule.id==id}.pending!!
        assertNotNull(fired.startedAt)
        assertTrue(fired.startedAt!!>=pending.at)
        assertTrue(AlarmGraph.engine(context).snooze(fired.token))
        RingingService.refresh(context)
        val snoozed=AlarmGraph.engine(context).entries().first {it.rule.id==id}.pending!!
        assertEquals(DeliveryState.SNOOZED,snoozed.state)
        assertEquals(1,snoozed.snoozes)
        assertNotEquals(fired.token,snoozed.token)
        assertEquals(snoozed.at.toEpochMilli(),context.getSystemService(AlarmManager::class.java).nextAlarmClock!!.triggerTime)
        await {!RingingService.running}
        AlarmStore(context).use {assertEquals(snoozed,it.entries().first {e->e.rule.id==id}.pending)}
    }
    @Test fun controlComponentsAreNotExportedToOtherApps() {
        val pm=context.packageManager
        @Suppress("DEPRECATION")
        assertFalse(pm.getReceiverInfo(ComponentName(context,AlarmReceiver::class.java),0).exported)
        @Suppress("DEPRECATION")
        assertFalse(pm.getServiceInfo(ComponentName(context,RingingService::class.java),0).exported)
        @Suppress("DEPRECATION")
        assertFalse(pm.getActivityInfo(ComponentName(context,RingingActivity::class.java),0).exported)
    }
    private fun await(condition: ()->Boolean) {
        val deadline=SystemClock.elapsedRealtime()+30000
        while(!condition() && SystemClock.elapsedRealtime()<deadline) SystemClock.sleep(100)
        assertTrue("Timed out waiting for alarm lifecycle",condition())
    }
}
