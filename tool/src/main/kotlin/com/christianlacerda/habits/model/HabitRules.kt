package com.christianlacerda.habits.model

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * The tool's rules as plain functions over [HabitState].
 *
 * `today` is a parameter rather than a call to the clock, so every rule can be checked on
 * any date. A null return means the change was refused; the caller keeps the state it had.
 */

internal fun HabitState.activeHabits(): List<Habit> =
    habits.filter { it.archivedAt == null }.sortedBy { it.order }

internal fun HabitState.canAddHabit(): Boolean = activeHabits().size < MAX_HABITS

private fun HabitState.nextOrder(): Int = (habits.maxOfOrNull { it.order } ?: -1) + 1

/** First day a habit can be ticked: its creation date snapped back to the week start. */
internal fun HabitState.firstTrackableDay(habit: Habit): LocalDate =
    LocalDate.ofEpochDay(habit.createdAt).snappedToWeekStart(weekStart)

internal fun addHabit(state: HabitState, name: String, today: LocalDate, id: String): HabitState? {
    val trimmed = name.trim()
    if (trimmed.isEmpty() || !state.canAddHabit()) return null
    val habit = Habit(id = id, name = trimmed, createdAt = today.toEpochDay(), order = state.nextOrder())
    return state.copy(habits = state.habits + habit)
}

/** Refused for a day after today, or before the habit's creation week. */
internal fun toggleCompletion(state: HabitState, habitId: String, epochDay: Long, today: LocalDate): HabitState? {
    if (epochDay > today.toEpochDay()) return null
    val habit = state.habits.find { it.id == habitId } ?: return null
    if (epochDay < state.firstTrackableDay(habit).toEpochDay()) return null
    val current = state.completions[habitId].orEmpty()
    val updated = if (epochDay in current) current - epochDay else current + epochDay
    return state.copy(completions = state.completions + (habitId to updated))
}

/** Archive is not delete: the completion history stays. */
internal fun archiveHabit(state: HabitState, habitId: String, today: LocalDate): HabitState =
    state.copy(habits = state.habits.map { if (it.id == habitId) it.copy(archivedAt = today.toEpochDay()) else it })

/** Refused when the active slots are full. */
internal fun unarchiveHabit(state: HabitState, habitId: String): HabitState? {
    if (!state.canAddHabit()) return null
    val order = state.nextOrder()
    return state.copy(
        habits = state.habits.map { if (it.id == habitId) it.copy(archivedAt = null, order = order) else it },
    )
}

/** Removes the habit and its history for good. */
internal fun deleteHabit(state: HabitState, habitId: String): HabitState =
    state.copy(habits = state.habits.filterNot { it.id == habitId }, completions = state.completions - habitId)

/** A pure label change. Refused for a blank name. */
internal fun renameHabit(state: HabitState, habitId: String, newName: String): HabitState? {
    val trimmed = newName.trim()
    if (trimmed.isEmpty()) return null
    return state.copy(habits = state.habits.map { if (it.id == habitId) it.copy(name = trimmed) else it })
}

/**
 * Most negative week offset the grid may reach: the week of the earliest ACTIVE habit.
 *
 * Archived habits are excluded even though their history is real, because they render in
 * no week — flooring on them made `‹` walk back through empty or fully dimmed weeks.
 */
internal fun minWeekOffset(state: HabitState, today: LocalDate): Int {
    val earliest = state.activeHabits().minOfOrNull { it.createdAt }?.let { LocalDate.ofEpochDay(it) } ?: today
    val weeks = ChronoUnit.WEEKS.between(
        earliest.snappedToWeekStart(state.weekStart),
        today.snappedToWeekStart(state.weekStart),
    )
    return (-weeks).toInt().coerceAtMost(0)
}
