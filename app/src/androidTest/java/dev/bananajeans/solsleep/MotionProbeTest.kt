// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep

import android.content.Intent
import android.app.AlarmManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import dev.bananajeans.solsleep.alarm.AlarmGraph
import dev.bananajeans.solsleep.core.*
import java.time.*
import android.os.SystemClock
import android.util.AtomicFile
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.bananajeans.solsleep.diagnostics.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File

@RunWith(AndroidJUnit4::class)
class MotionProbeTest {
    private val alarmId="motion-probe-hard-alarm"
    @get:Rule val compose=createAndroidComposeRule<MotionProbeActivity>()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun snapshot(): JSONObject?=runCatching {JSONObject(AtomicFile(File(context.filesDir,MotionProbeService.FILE)).readFully().decodeToString())}.getOrNull()
    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use {fd->
            java.io.FileInputStream(fd.fileDescriptor).use {it.readBytes()}
        }
    }
    @Before fun clear() {AtomicFile(File(context.filesDir,MotionProbeService.FILE)).delete()}
    @After fun cleanup() {
        shell("input keyevent KEYCODE_WAKEUP")
        context.stopService(Intent(context,MotionProbeService::class.java))
        AlarmGraph.engine(context).delete(alarmId)
    }
    @Test fun visibleStartCanAcquireMotionWhileScreenIsOffAndStopDurably() {
        shell("appops set "+context.packageName+" SCHEDULE_EXACT_ALARM allow")
        val engine=AlarmGraph.engine(context)
        assertEquals(ArmResult.ARMED,engine.save(AlarmRule(alarmId,LocalTime.of(7,0),Repeat.Once(LocalDate.now().plusDays(1)))))
        val hard=engine.entries().single {it.rule.id==alarmId}.pending!!
        compose.onNodeWithText("Start motion probe").performClick()
        await {snapshot()?.optLong("callbacks",0)?.let {it>=2}==true}
        shell("input keyevent KEYCODE_SLEEP")
        try {
            val power=context.getSystemService(android.os.PowerManager::class.java)
            await {!power.isInteractive}
            val offAt=SystemClock.elapsedRealtime()
            val before=snapshot()!!.getLong("callbacks")
            await {snapshot()?.let {it.optLong("callbacks",0)>=before+5 && it.optLong("checkpointElapsedMs",0)>=offAt+1500}==true}
            assertFalse(power.isInteractive)
            val captured=snapshot()!!
            assertEquals("RUNNING",captured.getString("state"))
            assertTrue(captured.getBoolean("diagnosticOnly"))
            assertTrue(captured.getLong("lastSensorNs")>captured.getLong("firstSensorNs"))
            assertEquals(0L,captured.getLong("nonMonotonic"))
        } finally {shell("input keyevent KEYCODE_WAKEUP")}
        context.stopService(Intent(context,MotionProbeService::class.java))
        await {snapshot()?.optString("state")=="STOPPED"}
        val stopped=snapshot()!!.getLong("callbacks")
        SystemClock.sleep(1500)
        assertEquals(stopped,snapshot()!!.getLong("callbacks"))
        assertEquals(hard,engine.entries().single {it.rule.id==alarmId}.pending)
        assertEquals(hard.at.toEpochMilli(),context.getSystemService(AlarmManager::class.java).nextAlarmClock!!.triggerTime)
    }
    private fun await(condition: ()->Boolean) {
        val end=SystemClock.elapsedRealtime()+20000
        while(!condition() && SystemClock.elapsedRealtime()<end) SystemClock.sleep(100)
        assertTrue("Motion probe did not reach the expected checkpoint",condition())
    }
}
