package com.christianlacerda.habits.ui

import com.christianlacerda.habits.model.Habit
import com.christianlacerda.habits.model.HabitState
import com.christianlacerda.habits.model.snappedToWeekStart
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * The report's arithmetic, with no Compose in it.
 *
 * Which six months are on screen, how far back paging may go, and what share of a month's
 * trackable days were completed — plain functions over [Habit] and [HabitState], kept apart
 * from the drawing so the arithmetic can be checked on its own.
 */

/** Months shown at once. Six keeps a bar [DAY_CELL_UNITS] wide; twelve drops it to ~1.9u
 *  and the bars stop echoing the day cells. */
private const val REPORT_MONTHS = 6

internal val MONTH_LABEL_FORMAT = DateTimeFormatter.ofPattern("MMM")

/**
 * One month's column for one habit.
 *
 * [fraction] is the share of *trackable* days completed, not calendar days — a habit created
 * on the 20th is only answerable for the days it existed.
 */
internal data class MonthBar(
    val month: YearMonth,
    val fraction: Float,
    /** False when the habit did not exist at all that month — an empty column, not a zero. */
    val trackable: Boolean,
    /** The month still running. Drawn hollow, and scaled by days elapsed (see [monthBars]). */
    val inProgress: Boolean,
)

internal fun monthWindow(currentMonth: YearMonth, offset: Int): List<YearMonth> {
    val anchor = currentMonth.plusMonths(offset.toLong() * REPORT_MONTHS)
    return (REPORT_MONTHS - 1 downTo 0).map { anchor.minusMonths(it.toLong()) }
}

internal fun earliestHabitMonth(habits: List<Habit>, fallback: YearMonth): YearMonth =
    habits.minOfOrNull { it.createdAt }
        ?.let { YearMonth.from(LocalDate.ofEpochDay(it)) }
        ?: fallback

/**
 * Most negative window offset that still shows the first recorded month.
 *
 * Derived from where the record starts, so `‹` stops at real data rather than walking back
 * through empty windows. Solving `windowStart <= earliest` gives the floorDiv below.
 */
internal fun minWindowOffset(earliest: YearMonth, current: YearMonth): Int {
    val span = ChronoUnit.MONTHS.between(earliest, current).toInt()
    return Math.floorDiv(REPORT_MONTHS - 1 - span, REPORT_MONTHS).coerceAtMost(0)
}

internal fun monthBars(
    habit: Habit,
    state: HabitState,
    window: List<YearMonth>,
    today: LocalDate,
): List<MonthBar> {
    val completions = state.completions[habit.id].orEmpty()
    val currentMonth = YearMonth.from(today)

    // Snapped to the week, matching how the grid gates tracking: completions may predate
    // createdAt within the creation week, and must stay inside the denominator.
    val firstTrackable = LocalDate.ofEpochDay(habit.createdAt).snappedToWeekStart(state.weekStart)

    return window.map { month ->
        val from = maxOf(month.atDay(1), firstTrackable)
        // The running month is measured against days elapsed, so it compares fairly with
        // a finished one rather than reading as a collapse.
        val to = minOf(month.atEndOfMonth(), today)

        if (to < from) {
            MonthBar(month, fraction = 0f, trackable = false, inProgress = month == currentMonth)
        } else {
            val possible = ChronoUnit.DAYS.between(from, to).toInt() + 1
            val done = completions.count { it in from.toEpochDay()..to.toEpochDay() }
            MonthBar(
                month = month,
                fraction = (done.toFloat() / possible).coerceIn(0f, 1f),
                trackable = true,
                inProgress = month == currentMonth,
            )
        }
    }
}

internal fun windowLabel(window: List<YearMonth>): String {
    val first = window.first()
    val last = window.last()
    val firstLabel = first.format(MONTH_LABEL_FORMAT).uppercase()
    val lastLabel = last.format(MONTH_LABEL_FORMAT).uppercase()
    // Both years when the window straddles: an unqualified "AUG" would read as this year's.
    return if (first.year == last.year) {
        "$firstLabel – $lastLabel ${last.year}"
    } else {
        "$firstLabel ${first.year} – $lastLabel ${last.year}"
    }
}
