package com.christianlacerda.habits.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlinx.serialization.Serializable

/**
 * The habit record and the shape it is stored in.
 *
 * Habits have stable identity ([Habit.id]) and completions are keyed by habit id plus epoch
 * day ([HabitState.completions]) rather than by "the seven booleans currently on screen".
 * That is what makes persistence and archive-with-history possible at all. The whole thing
 * is one JSON blob in the tool's DataStore — ample for a handful of habits over a year of
 * days, and far simpler than pulling Room in for this much data.
 */

@Serializable
data class Habit(
    val id: String,
    val name: String,
    /** Epoch day ([LocalDate.toEpochDay]) the habit was created. Gates tracking at week
     *  granularity, not the exact day — a habit can log days before its creation date as
     *  long as they fall in the same week, so day-one still works (create Wednesday, tick
     *  Monday and Tuesday of that week). */
    val createdAt: Long,
    /** Epoch day the habit was archived, or null if it's active. */
    val archivedAt: Long? = null,
    /** Display order among habits in the same state (active or archived). */
    val order: Int,
)

/** Which day the week grid (and the S M T W T F S header) starts on. Display-only —
 *  completions are keyed by epoch day, so changing this reorders the grid without
 *  touching any stored data. */
@Serializable
enum class WeekStart {
    SUNDAY,
    MONDAY,
    ;

    val dayOfWeek: DayOfWeek
        get() = if (this == SUNDAY) DayOfWeek.SUNDAY else DayOfWeek.MONDAY
}

@Serializable
data class HabitState(
    val habits: List<Habit> = emptyList(),
    /** habitId -> set of epoch days marked complete. */
    val completions: Map<String, Set<Long>> = emptyMap(),
    val weekStart: WeekStart = WeekStart.SUNDAY,
)

/** The Sunday/Monday (per [WeekStart]) on or before this date — the shared "which week
 *  is this day in" formula used for the grid header, offset clamping, and creation-week
 *  gating, so those three don't drift against each other. */
internal fun LocalDate.snappedToWeekStart(weekStart: WeekStart): LocalDate =
    with(TemporalAdjusters.previousOrSame(weekStart.dayOfWeek))

/** Hard cap on the number of *active* habits that can exist at once. Archived habits don't count.
 *  Also the number of slots [HabitReportScreen] divides its plot area into, so a habit's trend
 *  sits at the same height there as its week strip does here. */
internal const val MAX_HABITS = 3
