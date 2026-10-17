// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.core

import java.time.*
import java.util.UUID

enum class DeliveryState { SCHEDULED, SNOOZED, RINGING, DONE, MISSED }
data class PendingAlarm(
    val token: String,
    val at: Instant,
    val date: LocalDate,
    val state: DeliveryState = DeliveryState.SCHEDULED,
    val snoozes: Int = 0,
    val armed: Boolean = false,
    val startedAt: Instant? = null,
)
data class AlarmEntry(val rule: AlarmRule, val pending: PendingAlarm?)
data class AlarmRestoreResult(val added: Int,val skippedExisting: Int)
enum class ArmResult { ARMED, ACCESS_REQUIRED, FAILED }

/** Implementations must atomically publish rule+pending and atomically claim/finish. */
interface AlarmPersistence {
    fun entries(): List<AlarmEntry>
    fun put(rule: AlarmRule, pending: PendingAlarm?)
    fun replacePending(id: String, pending: PendingAlarm)
    fun setArmed(token: String, armed: Boolean)
    fun claim(token: String, now: Instant): AlarmEntry?
    fun finish(token: String, missed: Boolean = false): Boolean
    fun delete(id: String)
    /** Insert every provided disabled definition atomically; fail on any conflict. */
    fun insertDisabled(rules: List<AlarmRule>)
}

interface WakeScheduler {
    fun canSchedule(): Boolean
    fun schedule(rule: AlarmRule, pending: PendingAlarm)
    fun cancel(token: String)
}

class AlarmBusyException : IllegalStateException("Dismiss the ringing alarm first")

/**
 * Single serialized owner of the alarm side-effect protocol. OS registrations are
 * outside the DB transaction; durable pending rows and unique tokens repair retries.
 */
class AlarmEngine(
    private val store: AlarmPersistence,
    private val scheduler: WakeScheduler,
    private val clock: Clock = Clock.systemUTC(),
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    companion object {
        val LATE_GRACE: Duration = Duration.ofMinutes(10)
        val RING_LIMIT: Duration = Duration.ofMinutes(30)
    }

    @Synchronized fun entries(): List<AlarmEntry> = store.entries()

    @Synchronized fun restore(archive: AlarmArchivePreview): AlarmRestoreResult {
        require(archive.alarms.size<=AlarmArchive.MAX_ALARMS)
        require(archive.alarms.map {it.id}.distinct().size==archive.alarms.size)
        val existing=store.entries().map {it.rule.id}.toSet()
        val fresh=archive.alarms.filter {it.id !in existing}.map {it.copy(enabled=false)}
        fresh.forEach {AlarmRuleCodec.decode(AlarmRuleCodec.encode(it))}
        store.insertDisabled(fresh)
        // Never recreate tokens, replay old deliveries, or register restored definitions.
        return AlarmRestoreResult(fresh.size,archive.alarms.size-fresh.size)
    }

    @Synchronized fun save(input: AlarmRule): ArmResult {
        val old = store.entries().find { it.rule.id == input.id }
        requireEditable(old)
        val rule = input.copy(revision = (old?.rule?.revision ?: 0) + 1)
        val next = pending(rule)
        store.put(rule, next)
        val result = if (next == null) ArmResult.ARMED else arm(rule, next)
        old?.pending?.let { cancel(it.token) }
        return result
    }

    @Synchronized fun setEnabled(id: String, enabled: Boolean): ArmResult {
        val old = store.entries().first { it.rule.id == id }
        val repeat = if (enabled && old.rule.repeat is Repeat.Once)
            old.rule.repeat.takeIf {Recurrence.resolve(it.date,old.rule.time,effectiveZone(old.rule))>clock.instant()}
                ?: Repeat.Once(Recurrence.nextOneShotDate(old.rule.time, clock.instant(), effectiveZone(old.rule)))
            else old.rule.repeat
        return save(old.rule.copy(enabled = enabled, repeat = repeat, skipDate = null))
    }

    @Synchronized fun skip(id: String, undo: Boolean = false): ArmResult {
        val old = store.entries().first { it.rule.id == id }
        requireEditable(old)
        require(old.rule.repeat !is Repeat.Once) { "One-time alarms can be switched off" }
        return save(old.rule.copy(skipDate = if (undo) null else old.pending?.date))
    }

    @Synchronized fun delete(id: String) {
        val old = store.entries().find { it.rule.id == id } ?: return
        requireEditable(old)
        store.delete(id)
        old.pending?.let { cancel(it.token) }
    }

    @Synchronized fun fire(token: String): AlarmEntry? {
        val entry = store.entries().find { it.pending?.token == token } ?: return null
        val p = entry.pending!!
        if (!entry.rule.enabled || p.state !in setOf(DeliveryState.SCHEDULED, DeliveryState.SNOOZED))
            return null
        val now = clock.instant()
        if (p.at > now) return null
        if (expired(p, now)) {
            store.finish(token, missed = true)
            reconcile()
            return null
        }
        return store.claim(token, now)
    }

    @Synchronized fun ringing(): List<AlarmEntry> {
        if (store.entries().any { it.pending?.let {p ->
            p.state == DeliveryState.RINGING && expired(p, clock.instant())
        } == true }) reconcile()
        return store.entries().filter { it.pending?.state == DeliveryState.RINGING }
            .sortedBy {it.pending!!.let {p->p.startedAt ?: p.at}}
    }

    @Synchronized fun timeout(token: String) {
        val entry = store.entries().find { it.pending?.token == token } ?: return
        if (entry.pending?.state != DeliveryState.RINGING) return
        store.finish(token, missed = true)
        cancel(token)
        reconcile()
    }

    @Synchronized fun dismiss(token: String): Boolean {
        val done = store.finish(token)
        if (done) { cancel(token); reconcile() }
        return done
    }

    @Synchronized fun snooze(token: String): Boolean {
        val entry = store.entries().find { it.pending?.token == token } ?: return false
        val old = entry.pending!!
        if (old.state != DeliveryState.RINGING || old.snoozes >= entry.rule.snoozeLimit) return false
        val next = old.copy(
            token = UUID.randomUUID().toString(),
            at = clock.instant().plusSeconds(entry.rule.snoozeMinutes * 60L),
            state = DeliveryState.SNOOZED, snoozes = old.snoozes + 1, armed = false, startedAt = null,
        )
        // Keep the durable RINGING row until both replacement side effects succeed.
        // A process loss before publication resumes the original alarm; a registered
        // but unpublished token is harmless because fire() cannot claim it.
        if (register(entry.rule,next)!=ArmResult.ARMED) {
            cancel(next.token)
            return false
        }
        try {
            store.replacePending(entry.rule.id,next.copy(armed=true))
        } catch(e: RuntimeException) {
            cancel(next.token)
            throw e
        }
        cancel(old.token)
        return true
    }

    @Synchronized fun reconcile(clockChanged: Boolean = false, recoverRinging: Boolean = false): ArmResult {
        var result = ArmResult.ARMED
        val now = clock.instant()
        for (entry in store.entries()) {
            val rule = entry.rule
            var p = entry.pending
            if (!rule.enabled) { p?.let { cancel(it.token) }; continue }
            if (p?.state == DeliveryState.RINGING) {
                if (!expired(p, now)) {
                    if (!recoverRinging) continue
                    p = p.copy(state = DeliveryState.SCHEDULED, armed = false)
                    store.replacePending(rule.id, p)
                } else {
                    store.finish(p.token, missed = true)
                    p = null
                }
            }
            if (p?.state in setOf(DeliveryState.SCHEDULED, DeliveryState.SNOOZED) && p != null) {
                if (p.at <= now) {
                    if (expired(p, now)) {
                        store.finish(p.token, missed = true)
                        p = null
                    }
                } else if (clockChanged && p.state == DeliveryState.SCHEDULED) {
                    val recalculated = pending(rule)
                    if (recalculated?.at != p.at || recalculated.date != p.date) {
                        cancel(p.token)
                        p = recalculated
                        store.put(rule, p)
                    }
                }
            } else { p = null }
            if (p == null) {
                // Re-read because a missed one-shot was disabled atomically by finish().
                val current = store.entries().first { it.rule.id == rule.id }.rule
                if (!current.enabled) continue
                p = pending(current)
                if (p == null) {
                    store.put(current.copy(enabled = false), null)
                    continue
                }
                store.put(current, p)
            }
            val armed = arm(rule, p)
            if (armed != ArmResult.ARMED) result = armed
        }
        return result
    }

    private fun pending(rule: AlarmRule): PendingAlarm? =
        Recurrence.next(rule, clock.instant(), zone())?.let {
            PendingAlarm(UUID.randomUUID().toString(), it.at, it.date)
        }

    private fun arm(rule: AlarmRule,p: PendingAlarm): ArmResult {
        val result=register(rule,p)
        // A storage acknowledgement failure is a storage failure, not a scheduler failure.
        store.setArmed(p.token,result==ArmResult.ARMED)
        return result
    }

    private fun register(rule: AlarmRule,p: PendingAlarm): ArmResult = try {
        if(!scheduler.canSchedule()) ArmResult.ACCESS_REQUIRED
        else {scheduler.schedule(rule,p);ArmResult.ARMED}
    } catch(_: SecurityException) {ArmResult.ACCESS_REQUIRED}
      catch(_: RuntimeException) {ArmResult.FAILED}

    private fun expired(p: PendingAlarm, now: Instant): Boolean =
        Duration.between(p.startedAt ?: p.at, now) > if (p.startedAt != null) RING_LIMIT else LATE_GRACE

    private fun cancel(token: String) { runCatching { scheduler.cancel(token) } }
    private fun requireEditable(entry: AlarmEntry?) {
        if (entry?.pending?.state == DeliveryState.RINGING) throw AlarmBusyException()
    }
    private fun effectiveZone(rule: AlarmRule) = rule.zoneId?.let(ZoneId::of) ?: zone()
}
