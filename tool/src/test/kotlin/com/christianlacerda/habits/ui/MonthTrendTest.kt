package com.christianlacerda.habits.ui

import com.christianlacerda.habits.model.Habit
import com.christianlacerda.habits.model.HabitState
import com.christianlacerda.habits.model.WeekStart
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val TODAY: LocalDate = LocalDate.of(2026, 11, 19)
private val NOW: YearMonth = YearMonth.from(TODAY)

/** Created Saturday June 20 2026. Its Sunday-start week begins June 14. */
private val HABIT = Habit(id = "h", name = "h", createdAt = LocalDate.of(2026, 6, 20).toEpochDay(), order = 0)

private fun days(vararg dates: LocalDate) = dates.map { it.toEpochDay() }.toSet()

class MonthTrendTest {

    @BeforeTest
    fun englishMonths() {
        Locale.setDefault(Locale.US)
    }

    private fun bars(state: HabitState, offset: Int = 0) = monthBars(HABIT, state, monthWindow(NOW, offset), TODAY)

    // --- monthBars ----------------------------------------------------------------------------

    @Test
    fun theCreationMonthIsMeasuredFromTheCreationWeekNotTheFirst() {
        // June 14–30 is 17 trackable days. Completing all of them is a full bar.
        val all = (14..30).map { LocalDate.of(2026, 6, it).toEpochDay() }.toSet()
        val june = bars(HabitState(habits = listOf(HABIT), completions = mapOf("h" to all))).first()
        assertEquals(YearMonth.of(2026, 6), june.month)
        assertTrue(june.trackable)
        assertEquals(1f, june.fraction)
    }

    @Test
    fun aMondayWeekStartShortensTheCreationMonth() {
        // Monday start: June 15–30, 16 days. 4 completions is a quarter.
        val state = HabitState(
            habits = listOf(HABIT),
            completions = mapOf("h" to days(*(15..18).map { LocalDate.of(2026, 6, it) }.toTypedArray())),
            weekStart = WeekStart.MONDAY,
        )
        assertEquals(0.25f, bars(state).first().fraction)
    }

    @Test
    fun aFinishedMonthIsMeasuredAgainstAllItsDays() {
        // September has 30 days; 15 completions is half.
        val sept = (1..15).map { LocalDate.of(2026, 9, it).toEpochDay() }.toSet()
        val bar = bars(HabitState(habits = listOf(HABIT), completions = mapOf("h" to sept)))
            .single { it.month == YearMonth.of(2026, 9) }
        assertEquals(0.5f, bar.fraction)
        assertFalse(bar.inProgress)
    }

    @Test
    fun theRunningMonthIsMeasuredAgainstTheWholeMonth() {
        // Nov 1–19 all done: the fill is 19 of November's 30 days, on the same scale as every
        // other month, and it meets the outline at 19/30, the most possible so far.
        val nov = (1..19).map { LocalDate.of(2026, 11, it).toEpochDay() }.toSet()
        val bar = bars(HabitState(habits = listOf(HABIT), completions = mapOf("h" to nov))).last()
        assertEquals(NOW, bar.month)
        assertTrue(bar.inProgress)
        assertEquals(19f / 30, bar.fraction)
        assertEquals(19f / 30, bar.elapsedFraction)
    }

    @Test
    fun aFewEarlyCheckInsNeverOutgrowAFullerFinishedMonth() {
        // The report as seen on October 6: 15 check-ins in September, 4 so far in October.
        val oct6 = LocalDate.of(2026, 10, 6)
        val done = (1..15).map { LocalDate.of(2026, 9, it).toEpochDay() } +
            (1..4).map { LocalDate.of(2026, 10, it).toEpochDay() }
        val bars = monthBars(HABIT, HabitState(habits = listOf(HABIT), completions = mapOf("h" to done.toSet())),
            monthWindow(YearMonth.from(oct6), 0), oct6)
        val sept = bars.single { it.month == YearMonth.of(2026, 9) }
        val oct = bars.single { it.month == YearMonth.of(2026, 10) }
        assertEquals(0.5f, sept.fraction)
        assertEquals(4f / 31, oct.fraction)
        assertEquals(6f / 31, oct.elapsedFraction)
        assertTrue(oct.fraction < sept.fraction)
    }

    @Test
    fun finishedMonthsHaveNoOutline() {
        val sept = bars(HabitState(habits = listOf(HABIT))).single { it.month == YearMonth.of(2026, 9) }
        assertFalse(sept.inProgress)
        assertEquals(1f, sept.elapsedFraction)
    }

    @Test
    fun aHabitCreatedThisMonthIsMeasuredFromItsCreationWeekToTheMonthsEnd() {
        // Created Wednesday Nov 18; its week starts Sunday the 15th. Nov 15–30 is 16 days,
        // of which Nov 15–19 have arrived.
        val fresh = Habit(id = "f", name = "f", createdAt = LocalDate.of(2026, 11, 18).toEpochDay(), order = 0)
        val state = HabitState(habits = listOf(fresh), completions = mapOf("f" to setOf(LocalDate.of(2026, 11, 16).toEpochDay())))
        val nov = monthBars(fresh, state, monthWindow(NOW, 0), TODAY).last()
        assertEquals(1f / 16, nov.fraction)
        assertEquals(5f / 16, nov.elapsedFraction)
    }

    @Test
    fun monthsBeforeTheHabitExistedAreNotTrackable() {
        val earlier = bars(HabitState(habits = listOf(HABIT)), offset = -1)
        assertTrue(earlier.none { it.trackable })
    }

    @Test
    fun aHabitWithNoCompletionsReadsZeroNotUntrackable() {
        val sept = bars(HabitState(habits = listOf(HABIT))).single { it.month == YearMonth.of(2026, 9) }
        assertTrue(sept.trackable)
        assertEquals(0f, sept.fraction)
    }

    @Test
    fun completionsOfOtherMonthsDoNotLeakIn() {
        val oct31 = days(LocalDate.of(2026, 10, 31))
        val bars = bars(HabitState(habits = listOf(HABIT), completions = mapOf("h" to oct31)))
        assertEquals(0f, bars.single { it.month == YearMonth.of(2026, 11) }.fraction)
        assertEquals(1f / 31, bars.single { it.month == YearMonth.of(2026, 10) }.fraction)
    }

    // --- windows ----------------------------------------------------------------------------

    @Test
    fun theCurrentWindowEndsThisMonth() {
        assertEquals((6..11).map { YearMonth.of(2026, it) }, monthWindow(NOW, 0))
    }

    @Test
    fun earlierWindowsStepBackSixMonths() {
        assertEquals(
            listOf(YearMonth.of(2025, 12)) + (1..5).map { YearMonth.of(2026, it) },
            monthWindow(NOW, -1),
        )
    }

    @Test
    fun theOffsetFloorStopsAtTheFirstRecordedMonth() {
        assertEquals(0, minWindowOffset(NOW, NOW))
        assertEquals(0, minWindowOffset(NOW.minusMonths(5), NOW))
        assertEquals(-1, minWindowOffset(NOW.minusMonths(6), NOW))
        assertEquals(-1, minWindowOffset(NOW.minusMonths(11), NOW))
        assertEquals(-2, minWindowOffset(NOW.minusMonths(12), NOW))
    }

    @Test
    fun theFloorWindowAlwaysContainsTheEarliestMonth() {
        for (back in 0L..40L) {
            val earliest = NOW.minusMonths(back)
            assertTrue(earliest in monthWindow(NOW, minWindowOffset(earliest, NOW)), "back=$back")
        }
    }

    @Test
    fun theEarliestMonthIgnoresNothingAndFallsBack() {
        assertEquals(NOW, earliestHabitMonth(emptyList(), NOW))
        assertEquals(YearMonth.of(2026, 6), earliestHabitMonth(listOf(HABIT), NOW))
    }

    @Test
    fun aWindowWithinOneYearNamesTheYearOnce() {
        assertEquals("JUN – NOV 2026", windowLabel(monthWindow(NOW, 0)))
    }

    @Test
    fun aWindowAcrossTwoYearsNamesBoth() {
        assertEquals("DEC 2025 – MAY 2026", windowLabel(monthWindow(NOW, -1)))
    }

    // --- descriptions read by the emulator eval ----------------------------------------------

    @Test
    fun monthColumnsDescribeTheirHeight() {
        val sept = (1..15).map { LocalDate.of(2026, 9, it).toEpochDay() }.toSet()
        val bars = bars(HabitState(habits = listOf(HABIT), completions = mapOf("h" to sept)))
        assertEquals("Read, 2026-09, 50%, closed", monthBarDescription("Read", bars.single { it.month.monthValue == 9 }))
        assertEquals("Read, 2026-11, 0%, in progress, 63% elapsed", monthBarDescription("Read", bars.last()))
        val untracked = bars(HabitState(habits = listOf(HABIT)), offset = -1).first()
        assertEquals("Read, 2025-12, not tracked", monthBarDescription("Read", untracked))
    }

    @Test
    fun dayCellsDescribeTheirState() {
        val day = LocalDate.of(2026, 11, 19).toEpochDay()
        assertEquals("Read, 2026-11-19, done", dayCellDescription("Read", day, DayCellState.TRACKABLE, filled = true))
        assertEquals("Read, 2026-11-19, not done", dayCellDescription("Read", day, DayCellState.TRACKABLE, filled = false))
        assertEquals("Read, 2026-11-19, future", dayCellDescription("Read", day, DayCellState.FUTURE, filled = false))
        assertEquals("Read, 2026-11-19, before habit", dayCellDescription("Read", day, DayCellState.BEFORE_HABIT, filled = false))
    }
}
