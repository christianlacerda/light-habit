package com.christianlacerda.habits.ui

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.viewModelScope
import com.christianlacerda.habits.model.Habit
import com.christianlacerda.habits.model.HabitState
import com.christianlacerda.habits.model.WeekStart
import com.christianlacerda.habits.model.activeHabits
import com.christianlacerda.habits.model.addHabit
import com.christianlacerda.habits.model.archiveHabit
import com.christianlacerda.habits.model.canAddHabit
import com.christianlacerda.habits.model.deleteHabit
import com.christianlacerda.habits.model.minWeekOffset
import com.christianlacerda.habits.model.renameHabit
import com.christianlacerda.habits.model.toggleCompletion
import com.christianlacerda.habits.model.unarchiveHabit
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SimpleLightScreen
import java.time.LocalDate
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

    private fun minWeekOffset(): Int = minWeekOffset(_state.value, _today.value)

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
        get() = _state.value.activeHabits()

    val archivedHabits: List<Habit>
        get() = _state.value.habits.filter { it.archivedAt != null }.sortedBy { it.order }

    /**
     * Called when `+` is tapped. Returns true if the caller should navigate to the
     * naming screen; if the cap is already hit, it surfaces the limit message instead
     * and returns false.
     */
    fun requestAdd(): Boolean {
        if (!_state.value.canAddHabit()) {
            _limitMessage.value = ADD_LIMIT_MESSAGE
            return false
        }
        return true
    }

    fun addHabit(name: String) {
        updateAndPersist { addHabit(it, name, LocalDate.now(), UUID.randomUUID().toString()) }
    }

    /**
     * Tapping a day cell toggles that habit's completion for that date. Fires the
     * DataStore write immediately (see [updateAndPersist]) — the real usage pattern is
     * tap-then-pocket, so a debounced or exit-time write would silently lose taps.
     */
    fun toggleCompletion(habitId: String, epochDay: Long) {
        updateAndPersist { toggleCompletion(it, habitId, epochDay, LocalDate.now()) }
    }

    fun archiveHabit(habitId: String) {
        updateAndPersist { archiveHabit(it, habitId, LocalDate.now()) }
    }

    /** Returns true if the habit was unarchived; false (with the limit message surfaced)
     *  if 3 habits are already active. */
    fun unarchiveHabit(habitId: String): Boolean {
        if (!_state.value.canAddHabit()) {
            _limitMessage.value = RESTORE_LIMIT_MESSAGE
            return false
        }
        updateAndPersist { unarchiveHabit(it, habitId) }
        return true
    }

    /** Only reachable behind a confirmation step in the UI. */
    fun deleteHabit(habitId: String) {
        updateAndPersist { deleteHabit(it, habitId) }
    }

    fun dismissLimitMessage() {
        _limitMessage.value = null
    }

    fun renameHabit(habitId: String, newName: String) {
        updateAndPersist { renameHabit(it, habitId, newName) }
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

    /**
     * Updates in-memory state synchronously (so the UI reflects the tap immediately)
     * and fires the DataStore write right away on IO — not debounced, not batched, not
     * deferred to screen exit — since a habit tracker's worst bug is a tap that silently
     * didn't save because the app got backgrounded a moment later. A null from
     * [transform] is a refused change and writes nothing.
     */
    private fun updateAndPersist(transform: (HabitState) -> HabitState?) {
        val newState = transform(_state.value) ?: return
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
