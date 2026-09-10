package com.christianlacerda.habits.ui

import androidx.compose.foundation.layout.size
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.viewModelScope
import com.christianlacerda.habits.model.Habit
import com.christianlacerda.habits.model.HabitState
import com.christianlacerda.habits.model.MAX_HABITS
import com.christianlacerda.habits.model.WeekStart
import com.christianlacerda.habits.model.snappedToWeekStart
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SimpleLightScreen
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The tool's single state holder: every screen shares this one instance.
 *
 * Persistence lives here rather than behind a repository. It is one key, one JSON blob and
 * two coroutines, and a layer in front of that would be ceremony rather than separation.
 */

private const val ADD_LIMIT_MESSAGE = "3 habits is the limit — archive one to add another."
private const val RESTORE_LIMIT_MESSAGE = "3 habits is the limit — archive one to restore this."

private val HABIT_STATE_KEY = stringPreferencesKey("habit_state_json")

class HabitTrackerViewModel(
    private val dataStore: DataStore<Preferences>,
) : LightViewModel<Unit>() {

    private val json = Json { ignoreUnknownKeys = true }

    private val _state = MutableStateFlow(HabitState())
    val state: StateFlow<HabitState> = _state.asStateFlow()

    /** True once the initial DataStore read has completed, so the UI can avoid a false
     *  "empty state" flash while the real (possibly non-empty) data is still loading. */
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val _limitMessage = MutableStateFlow<String?>(null)
    val limitMessage: StateFlow<String?> = _limitMessage.asStateFlow()

    /** Whether the grid is in edit mode (entered via the bottom bar's EDIT/DONE toggle).
     *  In edit mode each habit's day strip is replaced in place by its three management
     *  actions — Rename, Archive, Delete — so managing a habit costs no extra screen. */
    private val _editMode = MutableStateFlow(false)
    val editMode: StateFlow<Boolean> = _editMode.asStateFlow()

    fun toggleEditMode() {
        _editMode.value = !_editMode.value
    }

    fun exitEditMode() {
        _editMode.value = false
    }

    private val _today = MutableStateFlow(LocalDate.now())
    val today: StateFlow<LocalDate> = _today.asStateFlow()

    /** 0 = current week, negative = weeks in the past. Never positive — an offset, not
     *  an anchor date, so it self-corrects when [_today] refreshes across midnight rather
     *  than pinning the grid to a date that's since become "the future." */
    private val _weekOffset = MutableStateFlow(0)
    val weekOffset: StateFlow<Int> = _weekOffset.asStateFlow()

    val canGoBack: StateFlow<Boolean> = combine(_weekOffset, _state, _today) { offset, _, _ ->
        offset > minWeekOffset()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val canGoForward: StateFlow<Boolean> = _weekOffset
        .map { it < 0 }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        _today.value = LocalDate.now()
        clampWeekOffset()
    }

    // Drops edit mode, deliberately NOT the week offset. onAppPause fires on screen-off
    // too, so anything reset here is also reset by a display timeout. Edit mode survives
    // that cheaply — nothing is ever in flight. The week offset does not: resetting it
    // silently returned a backfill to the current week, and since every week's strip looks
    // identical, the next tap then wrote to the wrong day.
    override fun onAppPause() {
        exitEditMode()
    }

    // No onBackPressed override: LightViewModel.onBackPressed() is unreachable for a root
    // screen, so one would look like working behaviour while doing nothing. Intercepting
    // back needs an SDK change, which this fork does not make.

    /** Week the record starts: earliest createdAt among ACTIVE habits only. Archived
     *  habits are excluded even though their history is real, because they render in no
     *  week — flooring on them made `‹` walk back through weeks whose grid was empty or
     *  fully dimmed, and left both chevrons live on the "No habits yet" empty state. The
     *  reachable range should promise exactly as much history as the screen can show, so
     *  unarchiving a habit correctly extends the floor back again. */
    private fun earliestWeekStart(): LocalDate =
        (_state.value.habits.filter { it.archivedAt == null }.minOfOrNull { it.createdAt }
            ?.let { LocalDate.ofEpochDay(it) } ?: _today.value)
            .snappedToWeekStart(_state.value.weekStart)

    private fun currentWeekStart(): LocalDate = _today.value.snappedToWeekStart(_state.value.weekStart)

    private fun minWeekOffset(): Int =
        (-ChronoUnit.WEEKS.between(earliestWeekStart(), currentWeekStart())).toInt().coerceAtMost(0)

    private fun clampWeekOffset() {
        _weekOffset.value = _weekOffset.value.coerceIn(minWeekOffset(), 0)
    }

    fun goToPreviousWeek() {
        _weekOffset.value = (_weekOffset.value - 1).coerceAtLeast(minWeekOffset())
    }

    fun goToNextWeek() {
        _weekOffset.value = (_weekOffset.value + 1).coerceAtMost(0)
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val stored = runCatching {
                val prefs = dataStore.data.first()
                prefs[HABIT_STATE_KEY]?.let { json.decodeFromString<HabitState>(it) }
            }.getOrNull() ?: HabitState()
            withContext(Dispatchers.Main) {
                _state.value = stored
                _loaded.value = true
            }
        }
    }

    val activeHabits: List<Habit>
        get() = _state.value.habits.filter { it.archivedAt == null }.sortedBy { it.order }

    val archivedHabits: List<Habit>
        get() = _state.value.habits.filter { it.archivedAt != null }.sortedBy { it.order }

    private val canAddHabit: Boolean
        get() = activeHabits.size < MAX_HABITS

    /**
     * Called when `+` is tapped. Returns true if the caller should navigate to the
     * naming screen; if the cap is already hit, it surfaces the limit message instead
     * and returns false.
     */
    fun requestAdd(): Boolean {
        if (!canAddHabit) {
            _limitMessage.value = ADD_LIMIT_MESSAGE
            return false
        }
        return true
    }

    fun addHabit(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || !canAddHabit) return
        val today = LocalDate.now().toEpochDay()
        val habit = Habit(
            id = UUID.randomUUID().toString(),
            name = trimmed,
            createdAt = today,
            archivedAt = null,
            order = nextOrder(),
        )
        updateAndPersist { it.copy(habits = it.habits + habit) }
    }

    /**
     * Tapping a day cell toggles that habit's completion for that date. Fires the
     * DataStore write immediately (see [updateAndPersist]) — the real usage pattern is
     * tap-then-pocket, so a debounced or exit-time write would silently lose taps.
     */
    fun toggleCompletion(habitId: String, epochDay: Long) {
        if (epochDay > LocalDate.now().toEpochDay()) return // defense in depth; UI shouldn't call this for future days
        val habit = _state.value.habits.find { it.id == habitId } ?: return
        val startWeekEpoch = LocalDate.ofEpochDay(habit.createdAt).snappedToWeekStart(_state.value.weekStart).toEpochDay()
        if (epochDay < startWeekEpoch) return // defense in depth; UI shouldn't call this before the habit's creation week
        updateAndPersist { state ->
            val current = state.completions[habitId] ?: emptySet()
            val updated = if (epochDay in current) current - epochDay else current + epochDay
            state.copy(completions = state.completions + (habitId to updated))
        }
    }

    /** Archive is not delete — completion history is untouched, the habit just stops
     *  appearing in the grid and frees up an active slot. */
    fun archiveHabit(habitId: String) {
        val today = LocalDate.now().toEpochDay()
        updateAndPersist { state ->
            state.copy(
                habits = state.habits.map {
                    if (it.id == habitId) it.copy(archivedAt = today) else it
                },
            )
        }
    }

    /** Returns true if the habit was unarchived; false (with the limit message surfaced)
     *  if 3 habits are already active. */
    fun unarchiveHabit(habitId: String): Boolean {
        if (!canAddHabit) {
            _limitMessage.value = RESTORE_LIMIT_MESSAGE
            return false
        }
        val order = nextOrder()
        updateAndPersist { state ->
            state.copy(
                habits = state.habits.map {
                    if (it.id == habitId) it.copy(archivedAt = null, order = order) else it
                },
            )
        }
        return true
    }

    /** Permanently removes a habit and its completion history. Only reachable from the
     *  archived list, behind a confirmation step in the UI. */
    fun deleteHabit(habitId: String) {
        updateAndPersist { state ->
            state.copy(
                habits = state.habits.filterNot { it.id == habitId },
                completions = state.completions - habitId,
            )
        }
    }

    fun dismissLimitMessage() {
        _limitMessage.value = null
    }

    /** Renaming has no effect on history or archive state — it's a pure label change,
     *  reachable from a habit row's Rename action in edit mode. No-op on a blank name. */
    fun renameHabit(habitId: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        updateAndPersist { state ->
            state.copy(
                habits = state.habits.map {
                    if (it.id == habitId) it.copy(name = trimmed) else it
                },
            )
        }
    }

    /** Display-only preference — reorders the grid's day-letter header and week grouping.
     *  Completions are keyed by epoch day, so no data migration is needed. */
    fun setWeekStart(weekStart: WeekStart) {
        updateAndPersist { it.copy(weekStart = weekStart) }
    }

    fun completionCount(habitId: String): Int = _state.value.completions[habitId]?.size ?: 0

    /** Re-reads the clock. [onScreenShow] covers the home screen, but a [SimpleLightScreen]
     *  sharing this view model (settings, report) never triggers it, so a session left open
     *  across midnight would draw those screens against yesterday's date. */
    fun refreshToday() {
        _today.value = LocalDate.now()
    }

    private fun nextOrder(): Int = (_state.value.habits.maxOfOrNull { it.order } ?: -1) + 1

    /**
     * Updates in-memory state synchronously (so the UI reflects the tap immediately)
     * and fires the DataStore write right away on IO — not debounced, not batched, not
     * deferred to screen exit — since a habit tracker's worst bug is a tap that silently
     * didn't save because the app got backgrounded a moment later.
     */
    private fun updateAndPersist(transform: (HabitState) -> HabitState) {
        val newState = transform(_state.value)
        _state.value = newState
        clampWeekOffset()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                dataStore.edit { prefs ->
                    prefs[HABIT_STATE_KEY] = json.encodeToString(newState)
                }
            }
        }
    }
}
