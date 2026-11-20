package com.christianlacerda.habits.model

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Thursday. With Sunday as week start, its week runs Nov 15–21. */
private val TODAY: LocalDate = LocalDate.of(2026, 11, 19)

private fun habit(id: String, created: LocalDate, archived: LocalDate? = null, order: Int = 0) =
    Habit(id = id, name = id, createdAt = created.toEpochDay(), archivedAt = archived?.toEpochDay(), order = order)

private fun LocalDate.day(): Long = toEpochDay()

class HabitRulesTest {

    private val read = habit("read", created = TODAY.minusDays(30))

    // --- toggling ---------------------------------------------------------------------

    @Test
    fun aDayCanBeTickedAndUnticked() {
        val start = HabitState(habits = listOf(read))
        val ticked = assertNotNull(toggleCompletion(start, "read", TODAY.day(), TODAY))
        assertEquals(setOf(TODAY.day()), ticked.completions["read"])
        val unticked = assertNotNull(toggleCompletion(ticked, "read", TODAY.day(), TODAY))
        assertEquals(emptySet(), unticked.completions["read"])
    }

    @Test
    fun aFutureDayCannotBeTicked() {
        val state = HabitState(habits = listOf(read))
        assertNull(toggleCompletion(state, "read", TODAY.plusDays(1).day(), TODAY))
    }

    @Test
    fun earlierDaysInTheCreationWeekCanBeTicked() {
        // Created Thursday; Sunday the 15th is in the same week.
        val state = HabitState(habits = listOf(habit("new", created = TODAY)))
        assertNotNull(toggleCompletion(state, "new", LocalDate.of(2026, 11, 15).day(), TODAY))
    }

    @Test
    fun daysBeforeTheCreationWeekCannotBeTicked() {
        val state = HabitState(habits = listOf(habit("new", created = TODAY)))
        assertNull(toggleCompletion(state, "new", LocalDate.of(2026, 11, 14).day(), TODAY))
    }

    @Test
    fun theCreationWeekFollowsTheWeekStartSetting() {
        // Monday start: the creation week begins on the 16th, so the 15th is out of range.
        val state = HabitState(habits = listOf(habit("new", created = TODAY)), weekStart = WeekStart.MONDAY)
        assertNull(toggleCompletion(state, "new", LocalDate.of(2026, 11, 15).day(), TODAY))
        assertNotNull(toggleCompletion(state, "new", LocalDate.of(2026, 11, 16).day(), TODAY))
    }

    @Test
    fun anUnknownHabitCannotBeTicked() {
        assertNull(toggleCompletion(HabitState(), "ghost", TODAY.day(), TODAY))
    }

    // --- adding -----------------------------------------------------------------------

    @Test
    fun addingTrimsTheNameAndStampsToday() {
        val added = assertNotNull(addHabit(HabitState(), "  Read  ", TODAY, "id1"))
        val h = added.habits.single()
        assertEquals("Read", h.name)
        assertEquals(TODAY.day(), h.createdAt)
        assertNull(h.archivedAt)
    }

    @Test
    fun aBlankNameIsRefused() {
        assertNull(addHabit(HabitState(), "   ", TODAY, "id1"))
    }

    @Test
    fun aFourthActiveHabitIsRefused() {
        var state = HabitState()
        repeat(MAX_HABITS) { state = assertNotNull(addHabit(state, "h$it", TODAY, "id$it")) }
        assertNull(addHabit(state, "one too many", TODAY, "id9"))
    }

    @Test
    fun archivedHabitsDoNotCountTowardsTheCap() {
        val state = HabitState(
            habits = listOf(
                habit("a", TODAY, order = 0),
                habit("b", TODAY, order = 1),
                habit("c", TODAY, archived = TODAY, order = 2),
            ),
        )
        val added = assertNotNull(addHabit(state, "d", TODAY, "d"))
        assertEquals(3, added.activeHabits().size)
    }

    @Test
    fun newHabitsGoLast() {
        val state = HabitState(habits = listOf(habit("a", TODAY, order = 4)))
        val added = assertNotNull(addHabit(state, "b", TODAY, "b"))
        assertEquals(listOf("a", "b"), added.activeHabits().map { it.id })
    }

    // --- archive, unarchive, delete, rename ---------------------------------------------

    @Test
    fun archivingKeepsHistoryAndUnarchivingBringsItBack() {
        val ticked = HabitState(habits = listOf(read), completions = mapOf("read" to setOf(1L, 2L)))
        val archived = archiveHabit(ticked, "read", TODAY)
        assertEquals(TODAY.day(), archived.habits.single().archivedAt)
        assertEquals(setOf(1L, 2L), archived.completions["read"])
        assertTrue(archived.activeHabits().isEmpty())

        val restored = assertNotNull(unarchiveHabit(archived, "read"))
        assertNull(restored.habits.single().archivedAt)
        assertEquals(setOf(1L, 2L), restored.completions["read"])
    }

    @Test
    fun unarchivingIsRefusedWhenTheSlotsAreFull() {
        val state = HabitState(
            habits = listOf(
                habit("a", TODAY, order = 0),
                habit("b", TODAY, order = 1),
                habit("c", TODAY, order = 2),
                habit("old", TODAY, archived = TODAY, order = 3),
            ),
        )
        assertNull(unarchiveHabit(state, "old"))
    }

    @Test
    fun unarchivedHabitsReturnAtTheEnd() {
        val state = HabitState(
            habits = listOf(habit("old", TODAY, archived = TODAY, order = 0), habit("a", TODAY, order = 1)),
        )
        val restored = assertNotNull(unarchiveHabit(state, "old"))
        assertEquals(listOf("a", "old"), restored.activeHabits().map { it.id })
    }

    @Test
    fun deletingRemovesTheHabitAndItsHistoryOnly() {
        val state = HabitState(
            habits = listOf(read, habit("run", TODAY)),
            completions = mapOf("read" to setOf(1L), "run" to setOf(2L)),
        )
        val after = deleteHabit(state, "read")
        assertEquals(listOf("run"), after.habits.map { it.id })
        assertEquals(mapOf("run" to setOf(2L)), after.completions)
    }

    @Test
    fun renamingChangesOnlyTheName() {
        val state = HabitState(habits = listOf(read), completions = mapOf("read" to setOf(1L)))
        val renamed = assertNotNull(renameHabit(state, "read", "  Books "))
        assertEquals(read.copy(name = "Books"), renamed.habits.single())
        assertEquals(state.completions, renamed.completions)
    }

    @Test
    fun renamingToBlankIsRefused() {
        assertNull(renameHabit(HabitState(habits = listOf(read)), "read", " "))
    }

    // --- week navigation floor ------------------------------------------------------------

    @Test
    fun withNoHabitsTheGridCannotGoBack() {
        assertEquals(0, minWeekOffset(HabitState(), TODAY))
    }

    @Test
    fun theFloorIsTheEarliestActiveHabitsWeek() {
        // Nov 1 is a Sunday: two weeks before the week of Nov 15.
        val state = HabitState(habits = listOf(habit("a", LocalDate.of(2026, 11, 3)), habit("b", TODAY)))
        assertEquals(-2, minWeekOffset(state, TODAY))
    }

    @Test
    fun archivedHabitsDoNotExtendTheFloor() {
        val state = HabitState(
            habits = listOf(habit("old", LocalDate.of(2026, 1, 1), archived = TODAY), habit("b", TODAY)),
        )
        assertEquals(0, minWeekOffset(state, TODAY))
    }
}
