// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.core

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class RecurrenceTest {
    private val tallinn = ZoneId.of("Europe/Tallinn")
    private fun rule(time: String = "07:00", repeat: Repeat = Repeat.Weekly(DayOfWeek.entries.toSet())) =
        AlarmRule("test", LocalTime.parse(time), repeat)

    @Test fun tallinnGapShiftsThirtyPastThreeToThirtyPastFour() {
        assertEquals(Instant.parse("2026-03-29T01:30:00Z"),
            Recurrence.resolve(LocalDate.parse("2026-03-29"), LocalTime.of(3,30), tallinn))
    }
    @Test fun tallinnOverlapUsesEarlierOffset() {
        assertEquals(Instant.parse("2026-10-25T00:30:00Z"),
            Recurrence.resolve(LocalDate.parse("2026-10-25"), LocalTime.of(3,30), tallinn))
    }
    @Test fun overlapDoesNotRingAgainDuringSecondOccurrenceOfSameClockTime() {
        assertEquals(Instant.parse("2026-10-26T01:30:00Z"),
            Recurrence.next(rule("03:30"), Instant.parse("2026-10-25T00:45:00Z"), tallinn)!!.at)
    }
    @Test fun gapCanBeThirtyMinutesRatherThanAnHour() {
        assertEquals(Instant.parse("2026-10-03T15:45:00Z"), Recurrence.resolve(
            LocalDate.parse("2026-10-04"), LocalTime.of(2,15), ZoneId.of("Australia/Lord_Howe")))
    }
    @Test fun quarterHourZoneDoesNotRoundOffsets() {
        assertEquals(Instant.parse("2026-10-17T01:15:00Z"),
            Recurrence.next(rule(), Instant.parse("2026-10-16T22:00:00Z"), ZoneId.of("Asia/Kathmandu"))!!.at)
    }
    @Test fun oneShotDoesNotBecomeDailyAfterItIsMissed() {
        assertNull(Recurrence.next(rule(repeat=Repeat.Once(LocalDate.parse("2026-10-16"))),
            Instant.parse("2026-10-17T00:00:00Z"), tallinn))
    }
    @Test fun skipAppliesToOneCivilOccurrence() {
        val r = rule().copy(skipDate=LocalDate.parse("2026-10-17"))
        assertEquals(LocalDate.parse("2026-10-18"),
            Recurrence.next(r, Instant.parse("2026-10-16T22:00:00Z"), tallinn)!!.date)
    }
    @Test fun weekdaysCrossWeekendAndYear() {
        val r=rule(repeat=Repeat.Weekly(setOf(DayOfWeek.MONDAY)))
        assertEquals(LocalDate.parse("2027-01-04"),
            Recurrence.next(r, Instant.parse("2026-12-31T00:00:00Z"), tallinn)!!.date)
    }
    @Test fun leapDayIsNotLost() {
        val r=rule(repeat=Repeat.Once(LocalDate.parse("2028-02-29")))
        assertEquals(LocalDate.parse("2028-02-29"),
            Recurrence.next(r, Instant.parse("2028-02-28T12:00:00Z"), tallinn)!!.date)
    }
    @Test fun intervalUsesAnAnchorRatherThanWeekday() {
        val r=rule(repeat=Repeat.EveryDays(LocalDate.parse("2026-10-01"),3))
        assertEquals(LocalDate.parse("2026-10-19"),
            Recurrence.next(r, Instant.parse("2026-10-17T00:00:00Z"), tallinn)!!.date)
    }
    @Test fun longestIntervalCanSkipOneOccurrence() {
        val r=rule(repeat=Repeat.EveryDays(LocalDate.parse("2026-01-01"),366))
            .copy(skipDate=LocalDate.parse("2027-01-02"))
        assertEquals(LocalDate.parse("2028-01-03"),
            Recurrence.next(r, Instant.parse("2026-01-01T06:00:01Z"), tallinn)!!.date)
    }
    @Test fun oddIsoWeeksCrossWeekBasedYear() {
        val r=rule(repeat=Repeat.AlternatingWeeks(setOf(DayOfWeek.MONDAY),true))
        assertEquals(LocalDate.parse("2027-01-04"),
            Recurrence.next(r, Instant.parse("2026-12-29T00:00:00Z"), tallinn)!!.date)
    }
    @Test fun pinnedZoneIgnoresPhoneTravel() {
        val r=rule().copy(zoneId="Europe/Tallinn")
        assertEquals(
            Recurrence.next(r, Instant.parse("2026-10-17T00:00:00Z"), tallinn),
            Recurrence.next(r, Instant.parse("2026-10-17T00:00:00Z"), ZoneId.of("Pacific/Auckland")))
    }
    @Test fun exactBoundaryMovesToNextOccurrence() {
        assertEquals(LocalDate.parse("2026-10-18"),
            Recurrence.next(rule(), Instant.parse("2026-10-17T04:00:00Z"), tallinn)!!.date)
    }
    @Test fun disabledRulesHaveNoOccurrence() {
        assertNull(Recurrence.next(rule().copy(enabled=false), Instant.EPOCH, tallinn))
    }
    @Test fun occurrenceIdentityIncludesRevisionAndDate() {
        val now=Instant.parse("2026-10-17T00:00:00Z")
        assertNotEquals(Recurrence.next(rule(),now,tallinn)!!.key,
            Recurrence.next(rule().copy(revision=2),now,tallinn)!!.key)
    }
    @Test fun manySchedulesAreStrictlyFutureAndMatchAnIndependentUtcOracle() {
        val zone=ZoneOffset.UTC
        for (day in 0..366) for (hour in listOf(0,7,23)) {
            val now=Instant.parse("2028-01-01T00:00:00Z").plusSeconds(day*86400L+123)
            val r=rule().copy(time=LocalTime.of(hour,0))
            val actual=Recurrence.next(r,now,zone)!!
            val midnight=now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()
            val today=midnight.plusSeconds(hour*3600L)
            assertEquals(if(today>now) today else today.plusSeconds(86400),actual.at)
            assertTrue(actual.at>now)
        }
    }
    @Test(expected=IllegalArgumentException::class) fun emptyWeekIsInvalid() {
        Repeat.Weekly(emptySet())
    }
    @Test(expected=IllegalArgumentException::class) fun unboundedIntervalIsRejected() {
        Repeat.EveryDays(LocalDate.now(),0)
    }
}
