package com.christianlacerda.habits.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlinx.serialization.Serializable

/**
 * The habit record and the shape it is stored in.
 *
 * Completions are keyed by habit id plus epoch day, which is what makes persistence and
 * archive-with-history possible. Stored as one JSON blob in DataStore — ample for three
 * habits, and simpler than Room for this much data.
 */

@Serializable
data class Habit(
    val id: String,
    val name: String,
    /** Epoch day the habit was created. Gates tracking at *week* granularity, not the day,
     *  so earlier days in the creation week can still be ticked. */
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

/** The [WeekStart] day on or before this date. Shared by the grid header, offset clamping
 *  and creation-week gating so the three cannot drift apart. */
internal fun LocalDate.snappedToWeekStart(weekStart: WeekStart): LocalDate =
    with(TemporalAdjusters.previousOrSame(weekStart.dayOfWeek))

/** Hard cap on *active* habits; archived ones don't count. Also the number of slots the
 *  report divides its plot area into, so a trend sits where its week strip does. */
internal const val MAX_HABITS = 3
