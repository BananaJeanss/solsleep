// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.core

import org.junit.Assert.*
import org.junit.Test
import java.time.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

class AlarmEngineTest {
    private class Time(var now: Instant=Instant.parse("2026-10-17T00:00:00Z")) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = now
    }
    private class Store : AlarmPersistence {
        val data=linkedMapOf<String,AlarmEntry>()
        override fun entries()=data.values.toList()
        override fun put(rule: AlarmRule,pending: PendingAlarm?) {data[rule.id]=AlarmEntry(rule,pending)}
        override fun replacePending(id: String,pending: PendingAlarm) {data[id]=data.getValue(id).copy(pending=pending)}
        override fun setArmed(token: String,armed: Boolean) {
            data.values.find {it.pending?.token==token}?.let {replacePending(it.rule.id,it.pending!!.copy(armed=armed))}
        }
        override fun claim(token: String,now: Instant): AlarmEntry? {
            val e=data.values.find {it.pending?.token==token} ?: return null
            val p=e.pending!!
            if(!e.rule.enabled || p.at>now || p.state !in setOf(DeliveryState.SCHEDULED,DeliveryState.SNOOZED)) return null
            val next=e.copy(pending=p.copy(state=DeliveryState.RINGING,startedAt=p.startedAt ?: now))
            data[e.rule.id]=next
            return next
        }
        override fun finish(token: String,missed: Boolean): Boolean {
            val e=data.values.find {it.pending?.token==token} ?: return false
            val p=e.pending!!
            if(p.state in setOf(DeliveryState.DONE,DeliveryState.MISSED) || (!missed && p.state!=DeliveryState.RINGING)) return false
            val rule=if(e.rule.repeat is Repeat.Once) e.rule.copy(enabled=false) else e.rule
            data[e.rule.id]=AlarmEntry(rule,p.copy(state=if(missed) DeliveryState.MISSED else DeliveryState.DONE,armed=false))
            return true
        }
        override fun delete(id: String) {data.remove(id)}
    }
    private class Platform : WakeScheduler {
        var allowed=true
        var fail=false
        val registrations=mutableMapOf<String,Instant>()
        override fun canSchedule()=allowed
        override fun schedule(rule: AlarmRule,pending: PendingAlarm) {
            if(fail) throw IllegalStateException("Injected scheduler failure")
            registrations[pending.token]=pending.at
        }
        override fun cancel(token: String) {registrations.remove(token)}
    }
    private val time=Time()
    private val store=Store()
    private val platform=Platform()
    private var zone=ZoneId.of("Europe/Tallinn")
    private val engine=AlarmEngine(store,platform,time) {zone}
    private fun rule(repeat: Repeat=Repeat.Once(LocalDate.parse("2026-10-17"))) =
        AlarmRule("morning",LocalTime.of(7,0),repeat)
    private fun current()=engine.entries().single()
    private fun ring(): String {
        engine.save(rule())
        val token=current().pending!!.token
        time.now=current().pending!!.at
        assertNotNull(engine.fire(token))
        return token
    }

    @Test fun savedAlarmIsDurableBeforeRegistrationAndHasOneUniqueDeadline() {
        assertEquals(ArmResult.ARMED,engine.save(rule()))
        assertEquals(1,platform.registrations.size)
        assertEquals(Instant.parse("2026-10-17T04:00:00Z"),current().pending!!.at)
        assertTrue(current().pending!!.armed)
    }
    @Test fun missingAccessPreservesAnUnarmedRecordAndCanRecoverLater() {
        platform.allowed=false
        assertEquals(ArmResult.ACCESS_REQUIRED,engine.save(rule()))
        assertFalse(current().pending!!.armed)
        platform.allowed=true
        assertEquals(ArmResult.ARMED,engine.reconcile())
        assertTrue(current().pending!!.armed)
    }
    @Test fun schedulerFailureDoesNotLoseTheDefinitionOrPretendItIsArmed() {
        platform.fail=true
        assertEquals(ArmResult.FAILED,engine.save(rule()))
        assertFalse(current().pending!!.armed)
        assertEquals("morning",current().rule.id)
    }
    @Test fun crashBetweenPersistenceAndRegistrationIsRepairable() {
        val r=rule()
        val p=PendingAlarm("unacknowledged",Instant.parse("2026-10-17T04:00:00Z"),LocalDate.parse("2026-10-17"))
        store.put(r,p)
        val restarted=AlarmEngine(store,platform,time) {zone}
        restarted.reconcile()
        assertEquals(setOf("unacknowledged"),platform.registrations.keys)
    }
    @Test fun replayingRegistrationKeepsTheSameOccurrenceIdentity() {
        engine.save(rule())
        val token=current().pending!!.token
        repeat(5) {engine.reconcile()}
        assertEquals(token,current().pending!!.token)
        assertEquals(1,platform.registrations.size)
    }
    @Test fun oldCallbackAfterEditCannotRingTheNewAlarm() {
        engine.save(rule());val old=current().pending!!.token
        engine.save(rule().copy(time=LocalTime.of(8,0)))
        time.now=Instant.parse("2026-10-17T04:00:00Z")
        assertNull(engine.fire(old))
        assertNotEquals(old,current().pending!!.token)
    }
    @Test fun earlyCallbackCannotRingBeforeDeadline() {
        engine.save(rule())
        assertNull(engine.fire(current().pending!!.token))
        assertEquals(DeliveryState.SCHEDULED,current().pending!!.state)
    }
    @Test fun duplicateCallbacksHaveOneDelivery() {
        val token=ring()
        assertNull(engine.fire(token))
        assertEquals(DeliveryState.RINGING,current().pending!!.state)
    }
    @Test fun concurrentCallbacksHaveOneDelivery() {
        engine.save(rule());val token=current().pending!!.token;time.now=current().pending!!.at
        val executor=Executors.newFixedThreadPool(8)
        val start=CountDownLatch(1)
        val count=AtomicInteger()
        val futures=(1..24).map {executor.submit {start.await();if(engine.fire(token)!=null) count.incrementAndGet()}}
        start.countDown();futures.forEach {it.get(5,TimeUnit.SECONDS)};executor.shutdown()
        assertEquals(1,count.get())
    }
    @Test fun dismissedOneShotDoesNotReappearTomorrow() {
        val token=ring()
        assertTrue(engine.dismiss(token))
        assertFalse(engine.dismiss(token))
        assertFalse(current().rule.enabled)
        assertTrue(platform.registrations.isEmpty())
    }
    @Test fun dismissedWeeklyAlarmAdvancesExactlyOnce() {
        engine.save(rule(Repeat.Weekly(DayOfWeek.entries.toSet())))
        val token=current().pending!!.token;time.now=current().pending!!.at
        engine.fire(token);engine.dismiss(token);engine.dismiss(token)
        assertEquals(Instant.parse("2026-10-18T04:00:00Z"),current().pending!!.at)
        assertEquals(1,platform.registrations.size)
    }
    @Test fun snoozeArmsANewIdentityAndRejectsOldActions() {
        val token=ring()
        assertTrue(engine.snooze(token))
        assertEquals(Instant.parse("2026-10-17T04:05:00Z"),current().pending!!.at)
        assertEquals(1,current().pending!!.snoozes)
        assertNotEquals(token,current().pending!!.token)
        assertFalse(engine.dismiss(token))
    }
    @Test fun failedSnoozeKeepsTheOriginalAlarmRinging() {
        val token=ring();platform.fail=true
        assertFalse(engine.snooze(token))
        assertEquals(token,current().pending!!.token)
        assertEquals(DeliveryState.RINGING,current().pending!!.state)
    }
    @Test fun permissionLossDuringSnoozeDoesNotSilenceTheAlarm() {
        val token=ring();platform.allowed=false
        assertFalse(engine.snooze(token))
        assertEquals(DeliveryState.RINGING,current().pending!!.state)
    }
    @Test fun snoozeLimitIsEnforcedAcrossRings() {
        var token=ring()
        repeat(3) {
            assertTrue(engine.snooze(token));token=current().pending!!.token
            time.now=current().pending!!.at;engine.fire(token)
        }
        assertFalse(engine.snooze(token))
        assertEquals(DeliveryState.RINGING,current().pending!!.state)
    }
    @Test fun lateOneShotIsMissedRatherThanMovedToTomorrow() {
        engine.save(rule());val token=current().pending!!.token
        time.now=current().pending!!.at.plusSeconds(601)
        assertNull(engine.fire(token))
        assertFalse(current().rule.enabled)
        assertEquals(DeliveryState.MISSED,current().pending!!.state)
    }
    @Test fun repeatedRecoveryCannotExtendTheOriginalGraceWindow() {
        engine.save(rule());val original=current().pending!!.at
        time.now=original.plusSeconds(10);engine.reconcile()
        assertEquals(original,current().pending!!.at)
        time.now=original.plusSeconds(601);engine.reconcile()
        assertFalse(current().rule.enabled)
    }
    @Test fun rebootRingingRecoveryRetainsOriginalStartAndTimeLimit() {
        val token=ring();val start=current().pending!!.startedAt
        time.now=time.now.plusSeconds(900)
        engine.reconcile(recoverRinging=true)
        assertEquals(token,current().pending!!.token)
        assertNotNull(engine.fire(token))
        assertEquals(start,current().pending!!.startedAt)
        time.now=start!!.plusSeconds(1801)
        engine.reconcile(recoverRinging=true)
        assertFalse(current().rule.enabled)
    }
    @Test(expected=AlarmBusyException::class) fun deletingRingingAlarmRequiresDismissal() {
        ring();engine.delete("morning")
    }
    @Test fun disabledAlarmCannotFireEvenIfAnOsCallbackIsQueued() {
        engine.save(rule());val token=current().pending!!.token
        engine.setEnabled("morning",false)
        time.now=Instant.parse("2026-10-17T04:00:00Z")
        assertNull(engine.fire(token))
    }
    @Test fun travelRecalculatesCivilTimeButKeepsSnoozeAsAnInstant() {
        engine.save(rule(Repeat.Weekly(DayOfWeek.entries.toSet())))
        zone=ZoneId.of("Asia/Tokyo");engine.reconcile(clockChanged=true)
        assertEquals(Instant.parse("2026-10-17T22:00:00Z"),current().pending!!.at)
        time.now=current().pending!!.at;engine.fire(current().pending!!.token)
        engine.snooze(current().pending!!.token)
        val snooze=current().pending!!.at
        zone=ZoneId.of("America/New_York");engine.reconcile(clockChanged=true)
        assertEquals(snooze,current().pending!!.at)
    }
}
