// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.core

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class ChallengeEngineTest {
    private class Time(var now: Instant=Instant.parse("2026-10-18T07:00:00Z")): Clock() {
        override fun getZone()=ZoneOffset.UTC
        override fun withZone(zone: ZoneId)=this
        override fun instant()=now
    }
    private class Store: AlarmPersistence {
        var row: AlarmEntry?=null;var fail=false
        override fun entries()=listOfNotNull(row)
        override fun put(rule: AlarmRule,pending: PendingAlarm?) {row=AlarmEntry(rule,pending)}
        override fun replacePending(id: String,pending: PendingAlarm) {check(!fail);row=row!!.copy(pending=pending)}
        override fun setArmed(token: String,armed: Boolean) {row=row!!.copy(pending=row!!.pending!!.copy(armed=armed))}
        override fun claim(token: String,now: Instant): AlarmEntry? {
            val p=row?.pending ?: return null
            if(p.token!=token || p.state !in setOf(DeliveryState.SCHEDULED,DeliveryState.SNOOZED) || p.at>now) return null
            row=row!!.copy(pending=p.copy(state=DeliveryState.RINGING,startedAt=p.startedAt ?: now));return row
        }
        override fun finish(token: String,missed: Boolean): Boolean {
            val e=row ?: return false;val p=e.pending ?: return false
            if(token!=p.token || p.state!=DeliveryState.RINGING) return false
            row=e.copy(rule=e.rule.copy(enabled=false),pending=p.copy(state=if(missed) DeliveryState.MISSED else DeliveryState.DONE));return true
        }
        override fun updateChallenge(token: String,expected: WakeChallenge?,next: WakeChallenge): Boolean {
            check(!fail);val p=row!!.pending!!
            if(p.token!=token || p.state!=DeliveryState.RINGING || p.challenge!=expected) return false
            row=row!!.copy(pending=p.copy(challenge=next));return true
        }
        override fun delete(id: String) {row=null}
        override fun insertDisabled(rules: List<AlarmRule>) {error("Unused")}
    }
    private class Scheduler: WakeScheduler {
        var allowed=true;var fail=false;val installed=mutableSetOf<String>()
        override fun canSchedule()=allowed
        override fun schedule(rule: AlarmRule,pending: PendingAlarm) {installed+=pending.token;check(!fail)}
        override fun cancel(token: String) {installed-=token}
    }
    private val clock=Time();private val store=Store();private val platform=Scheduler()
    private var engine=AlarmEngine(store,platform,clock) {ZoneOffset.UTC}
    private val policy=ChallengePolicy(setOf(ChallengeKind.COPY_CODE),rounds=1)
    private fun ring(): String {
        engine.save(AlarmRule("morning",LocalTime.of(7,1),Repeat.Once(LocalDate.of(2026,10,18)),challenge=policy))
        val p=store.row!!.pending!!;clock.now=p.at;engine.fire(p.token);return p.token
    }
    private fun solve(token: String,action: ChallengeAction): WakeChallenge {
        val s=engine.beginChallenge(token,action)!!;val q=WakeChallenges.question(policy,s)
        return engine.answerChallenge(token,s.id,s.revision,ChallengeResponse.Text(q.expected))!!
    }
    @Test fun bothOrdinaryActionsAreGatedAndCannotSilenceAnIncompleteChallenge() {
        val token=ring();assertFalse(engine.snooze(token));assertFalse(engine.dismiss(token))
        assertEquals(DeliveryState.RINGING,store.row!!.pending!!.state);assertTrue(platform.installed.contains(token))
        assertEquals(ChallengeAction.DISMISS,store.row!!.pending!!.challenge!!.action)
    }
    @Test fun completionAllowsOnlyItsChosenActionAndStaleAnswersAreIgnored() {
        val token=ring();val s=engine.beginChallenge(token,ChallengeAction.DISMISS)!!
        val wrong=engine.answerChallenge(token,s.id,s.revision,ChallengeResponse.Text("bad"))!!
        assertNull(engine.answerChallenge(token,s.id,s.revision,ChallengeResponse.Text(WakeChallenges.question(policy,s).expected)))
        assertNull(engine.answerChallenge("old-token",s.id,wrong.revision,ChallengeResponse.Fallback))
        assertNull(engine.answerChallenge(token,"old-id",wrong.revision,ChallengeResponse.Fallback))
        solve(token,ChallengeAction.DISMISS);assertTrue(engine.dismiss(token));assertFalse(engine.dismiss(token))
    }
    @Test fun processAndRingingRecoveryPreserveQuestionProgressMissesAndOriginalStart() {
        val token=ring();val s=engine.beginChallenge(token,ChallengeAction.SNOOZE)!!
        engine.answerChallenge(token,s.id,s.revision,ChallengeResponse.Text("wrong"))
        val before=store.row!!.pending!!;clock.now=clock.now.plusSeconds(30)
        engine=AlarmEngine(store,platform,clock) {ZoneOffset.UTC};engine.reconcile(recoverRinging=true)
        engine.fire(token);val after=store.row!!.pending!!
        assertEquals(before.challenge,after.challenge);assertEquals(before.startedAt,after.startedAt);assertEquals(before.at,after.at)
        assertEquals(before.challenge,engine.beginChallenge(token,ChallengeAction.SNOOZE))
    }
    @Test fun schedulingFailureRetainsCompletedProofAndOriginalRingingUntilDurableSuccess() {
        val token=ring();val proof=solve(token,ChallengeAction.SNOOZE);val before=store.row!!
        platform.fail=true;assertFalse(engine.snooze(token));assertEquals(before,store.row)
        assertEquals(setOf(token),platform.installed)
        platform.fail=false;assertTrue(engine.snooze(token));val next=store.row!!.pending!!
        assertEquals(DeliveryState.SNOOZED,next.state);assertNull(next.challenge);assertNotEquals(token,next.token)
        assertEquals(setOf(next.token),platform.installed);assertTrue(proof.satisfied(policy))
    }
    @Test fun publicationFailureRollsBackReplacementRegistrationAndRetainsReadyProof() {
        val token=ring();solve(token,ChallengeAction.SNOOZE);val before=store.row!!;store.fail=true
        try {engine.snooze(token);fail("Must propagate durable write failure")} catch(_: IllegalStateException) {}
        assertEquals(before,store.row);assertEquals(setOf(token),platform.installed)
        store.fail=false;assertTrue(engine.snooze(token))
    }
    @Test fun changingSnoozeToDismissAfterSchedulingFailureKeepsProofWithoutRestarting() {
        val token=ring();val proof=solve(token,ChallengeAction.SNOOZE);platform.allowed=false
        assertFalse(engine.snooze(token));val dismiss=engine.beginChallenge(token,ChallengeAction.DISMISS)!!
        assertEquals(proof.id,dismiss.id);assertEquals(proof.startedAt,dismiss.startedAt);assertEquals(proof.stage,dismiss.stage)
        assertTrue(engine.dismiss(token));assertTrue(platform.installed.isEmpty())
    }
    @Test fun failedChallengePersistenceCannotPublishTransientQuietOrProgress() {
        val token=ring();store.fail=true
        try {engine.beginChallenge(token,ChallengeAction.DISMISS);fail("Must propagate") } catch(_: IllegalStateException) {}
        assertNull(store.row!!.pending!!.challenge);assertEquals(DeliveryState.RINGING,store.row!!.pending!!.state)
    }
    @Test fun challengeDoesNotExtendThirtyMinuteRingingLimitOrPermitEditing() {
        val token=ring();engine.beginChallenge(token,ChallengeAction.DISMISS)
        try {engine.delete("morning");fail("Must protect active alarm")} catch(_: AlarmBusyException) {}
        clock.now=clock.now.plus(AlarmEngine.RING_LIMIT).plusMillis(1)
        assertNull(engine.beginChallenge(token,ChallengeAction.DISMISS));assertFalse(engine.dismiss(token))
        assertEquals(DeliveryState.MISSED,store.row!!.pending!!.state)
    }
    @Test fun eachSnoozedOccurrenceRequiresANewTaskIdentity() {
        val token=ring();val first=solve(token,ChallengeAction.SNOOZE);assertTrue(engine.snooze(token))
        val p=store.row!!.pending!!;clock.now=p.at;engine.fire(p.token)
        assertFalse(engine.dismiss(p.token));val second=store.row!!.pending!!.challenge!!
        assertNotEquals(first.id,second.id);assertEquals(0,second.stage);assertFalse(second.satisfied(policy))
    }
}
