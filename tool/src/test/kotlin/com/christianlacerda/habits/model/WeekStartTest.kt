package com.christianlacerda.habits.model

import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class WeekStartTest {

    /** Sunday Nov 15 2026 through Saturday Nov 21. */
    private val week = (15..21).map { LocalDate.of(2026, 11, it) }

    @Test
    fun everyDaySnapsBackToSunday() {
        for (day in week) {
            assertEquals(LocalDate.of(2026, 11, 15), day.snappedToWeekStart(WeekStart.SUNDAY), "$day")
        }
    }

    @Test
    fun everyDaySnapsBackToMonday() {
        assertEquals(LocalDate.of(2026, 11, 9), week.first().snappedToWeekStart(WeekStart.MONDAY))
        for (day in week.drop(1)) {
            assertEquals(LocalDate.of(2026, 11, 16), day.snappedToWeekStart(WeekStart.MONDAY), "$day")
        }
    }

    @Test
    fun snappingLandsOnTheConfiguredDay() {
        for (start in WeekStart.entries) {
            for (day in week) assertEquals(start.dayOfWeek, day.snappedToWeekStart(start).dayOfWeek)
        }
        assertEquals(DayOfWeek.SUNDAY, WeekStart.SUNDAY.dayOfWeek)
    }
}
