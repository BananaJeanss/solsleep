// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.core

import java.time.*
import java.time.temporal.ChronoUnit
import java.time.temporal.IsoFields

sealed interface Repeat {
    data class Once(val date: LocalDate) : Repeat
    data class Weekly(val days: Set<DayOfWeek>) : Repeat {
        init { require(days.isNotEmpty()) }
    }
    data class EveryDays(val anchor: LocalDate, val interval: Int) : Repeat {
        init { require(interval in 1..366) }
    }
    data class AlternatingWeeks(val days: Set<DayOfWeek>, val odd: Boolean) : Repeat {
        init { require(days.isNotEmpty()) }
    }
}

data class AlarmRule(
    val id: String,
    val time: LocalTime,
    val repeat: Repeat,
    val label: String = "",
    val enabled: Boolean = true,
    val zoneId: String? = null,
    val skipDate: LocalDate? = null,
    val vibrate: Boolean = true,
    val rampSeconds: Int = 20,
    val snoozeMinutes: Int = 5,
    val snoozeLimit: Int = 3,
    val revision: Long = 1,
) {
    init {
        require(id.isNotBlank())
        require(time.second == 0 && time.nano == 0)
        require(label.length <= 120)
        zoneId?.let { ZoneId.of(it) }
        require(rampSeconds in 0..120)
        require(snoozeMinutes in 1..60)
        require(snoozeLimit in 0..10)
        require(revision > 0)
    }
}

data class Occurrence(val date: LocalDate, val at: Instant, val key: String)

object Recurrence {
    /** Civil time: shift gaps by their length, choose the earlier offset in overlaps. */
    fun resolve(date: LocalDate, time: LocalTime, zone: ZoneId): Instant =
        date.atTime(time).atZone(zone).withEarlierOffsetAtOverlap().toInstant()

    fun next(rule: AlarmRule, after: Instant, currentZone: ZoneId): Occurrence? {
        if (!rule.enabled) return null
        val zone = rule.zoneId?.let(ZoneId::of) ?: currentZone
        val first = after.atZone(zone).toLocalDate()
        // At most one skipped date; the largest supported repeating interval is 366 days.
        val dates = if (rule.repeat is Repeat.Once) sequenceOf(rule.repeat.date)
            else generateSequence(if (rule.repeat is Repeat.EveryDays && rule.repeat.anchor > first) rule.repeat.anchor else first) { it.plusDays(1) }.take(734)
        return dates.firstNotNullOfOrNull { date ->
            if (date == rule.skipDate || !matches(rule.repeat, date)) return@firstNotNullOfOrNull null
            val at = resolve(date, rule.time, zone)
            if (at <= after) null else Occurrence(date, at, rule.id + "/" + rule.revision + "/" + date)
        }
    }

    fun nextOneShotDate(time: LocalTime, after: Instant, zone: ZoneId): LocalDate {
        val today = after.atZone(zone).toLocalDate()
        return if (resolve(today, time, zone) > after) today else today.plusDays(1)
    }

    private fun matches(repeat: Repeat, date: LocalDate): Boolean = when (repeat) {
        is Repeat.Once -> date == repeat.date
        is Repeat.Weekly -> date.dayOfWeek in repeat.days
        is Repeat.EveryDays -> ChronoUnit.DAYS.between(repeat.anchor, date).let {
            it >= 0 && it % repeat.interval == 0L
        }
        is Repeat.AlternatingWeeks -> date.dayOfWeek in repeat.days &&
            (date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) % 2 == 1) == repeat.odd
    }
}
