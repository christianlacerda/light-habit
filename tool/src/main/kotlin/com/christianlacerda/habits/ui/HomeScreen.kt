package com.christianlacerda.habits.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.christianlacerda.habits.model.Habit
import com.christianlacerda.habits.model.WeekStart
import com.christianlacerda.habits.model.snappedToWeekStart
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightFullscreenModal
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollBarPosition
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.verticalGridUnitsAsDp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * The week grid — the tool's initial screen, and the reason it exists.
 *
 * Habit names sit above their own seven-cell strip; the row furniture is in [HabitBlock] and
 * the cells themselves in DayStrip.kt.
 */

/** Grid units of horizontal padding either side of the grid content (see [HabitTrackerScreen]). */
private const val CONTENT_SIDE_PADDING_UNITS = 2f

@InitialScreen
class HomeScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, HabitTrackerViewModel>(sealedActivity) {

    override val viewModelClass: Class<HabitTrackerViewModel>
        get() = HabitTrackerViewModel::class.java

    override fun createViewModel() = HabitTrackerViewModel(lightContext.dataStore)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.state.collectAsState()
        val loaded by viewModel.loaded.collectAsState()
        val limitMessage by viewModel.limitMessage.collectAsState()
        val editMode by viewModel.editMode.collectAsState()
        val today by viewModel.today.collectAsState()
        val weekOffset by viewModel.weekOffset.collectAsState()
        val canGoBack by viewModel.canGoBack.collectAsState()
        val canGoForward by viewModel.canGoForward.collectAsState()
        val activeHabits = remember(state) { state.habits.filter { it.archivedAt == null }.sortedBy { it.order } }
        // Most recently archived first: the one you just put away is the one you'd want back.
        val archivedHabits = remember(state) {
            state.habits.filter { it.archivedAt != null }
                .sortedWith(compareByDescending<Habit> { it.archivedAt }.thenByDescending { it.order })
        }
        var pendingDelete by remember { mutableStateOf<Habit?>(null) }

        LightTheme(colors = themeColors) {
            val toDelete = pendingDelete
            if (toDelete != null) {
                // Replaces this screen's content rather than pushing a screen, so answering
                // returns straight to the grid, still in edit mode.
                HabitDeleteConfirmationContent(
                    message = deleteConfirmationMessage(
                        habitName = toDelete.name,
                        completionCount = state.completions[toDelete.id]?.size ?: 0,
                    ),
                    onCancel = { pendingDelete = null },
                    onConfirm = {
                        viewModel.deleteHabit(toDelete.id)
                        pendingDelete = null
                    },
                )
            } else {
                HabitTrackerScreen(
                    habits = activeHabits,
                    archivedHabits = archivedHabits,
                    completions = state.completions,
                    weekStart = state.weekStart,
                    today = today,
                    weekOffset = weekOffset,
                    canGoBack = canGoBack,
                    canGoForward = canGoForward,
                    loaded = loaded,
                    limitMessage = limitMessage,
                    editMode = editMode,
                    onAddTapped = {
                        if (viewModel.requestAdd()) {
                            navigateTo(screenFactory = { AddHabitScreen(it) }) { name ->
                                viewModel.addHabit(name)
                            }
                        }
                    },
                    onSettingsTapped = {
                        navigateTo(screenFactory = { HabitSettingsScreen(it, viewModel) })
                    },
                    onReportTapped = {
                        navigateTo(screenFactory = { HabitReportScreen(it, viewModel) })
                    },
                    onToggle = viewModel::toggleCompletion,
                    onToggleEditMode = viewModel::toggleEditMode,
                    onRenameHabit = { habit ->
                        navigateTo(screenFactory = {
                            AddHabitScreen(
                                it,
                                initialName = habit.name,
                                screenTitle = "Rename Habit",
                                submitLabel = "SAVE",
                            )
                        }) { newName ->
                            viewModel.renameHabit(habit.id, newName)
                        }
                    },
                    onArchiveHabit = { habit -> viewModel.archiveHabit(habit.id) },
                    onUnarchiveHabit = { habit -> viewModel.unarchiveHabit(habit.id) },
                    onDeleteHabit = { habit -> pendingDelete = habit },
                    onDismissLimitMessage = viewModel::dismissLimitMessage,
                    onPreviousWeek = viewModel::goToPreviousWeek,
                    onNextWeek = viewModel::goToNextWeek,
                )
            }
        }
    }
}

@Composable
private fun HabitTrackerScreen(
    habits: List<Habit>,
    archivedHabits: List<Habit>,
    completions: Map<String, Set<Long>>,
    weekStart: WeekStart,
    today: LocalDate,
    weekOffset: Int,
    canGoBack: Boolean,
    canGoForward: Boolean,
    loaded: Boolean,
    limitMessage: String?,
    editMode: Boolean,
    onAddTapped: () -> Unit,
    onSettingsTapped: () -> Unit,
    onReportTapped: () -> Unit,
    onToggle: (habitId: String, epochDay: Long) -> Unit,
    onToggleEditMode: () -> Unit,
    onRenameHabit: (Habit) -> Unit,
    onArchiveHabit: (Habit) -> Unit,
    onUnarchiveHabit: (Habit) -> Unit,
    onDeleteHabit: (Habit) -> Unit,
    onDismissLimitMessage: () -> Unit,
    onPreviousWeek: () -> Unit,
    onNextWeek: () -> Unit,
) {
    val colors = LightThemeTokens.colors

    val todayEpoch = remember(today) { today.toEpochDay() }
    val displayedWeekStart = remember(today, weekStart, weekOffset) {
        today.snappedToWeekStart(weekStart).plusWeeks(weekOffset.toLong())
    }
    // Only the current week (offset 0) can contain today; a past week's index range
    // (7..13 relative to its own start) would otherwise happen to fall outside 0..6 and
    // just look right by accident rather than by an explicit check.
    val todayIndex = remember(weekOffset, displayedWeekStart, today) {
        if (weekOffset == 0) ChronoUnit.DAYS.between(displayedWeekStart, today).toInt() else -1
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background),
        ) {
            LightTopBar(
                // Hidden rather than dimmed when unavailable: a spacer keeps the bar's
                // geometry identical (see LightBarButtonView's null branch), and a chevron
                // that's simply absent when there's nowhere to go means no control on this
                // screen ever does nothing when tapped — the presence of `›` alone tells
                // you you're not on the current week. Also off in edit mode, which doesn't
                // navigate weeks at all.
                leftButton = if (canGoBack && !editMode) {
                    LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        contentDescription = "Previous week",
                        onClick = onPreviousWeek,
                    )
                } else {
                    null
                },
                // Edit mode gets its own unambiguous label here instead of the week
                // range — this is the first thing a glance at the screen lands on, and
                // it doesn't depend on noticing the bottom bar's DONE label or the
                // per-row borders below.
                center = LightTopBarCenter.Text(if (editMode) "Editing" else weekRangeLabel(displayedWeekStart, today)),
                rightButton = if (canGoForward && !editMode) {
                    LightBarButton.LightIcon(
                        icon = LightIcons.ARROW_RIGHT,
                        contentDescription = "Next week",
                        onClick = onNextWeek,
                    )
                } else {
                    null
                },
            )

            if (!loaded) {
                Spacer(modifier = Modifier.weight(1f))
            } else if (habits.isEmpty()) {
                EmptyHabitsContent(modifier = Modifier.weight(1f))
            } else {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    Spacer(modifier = Modifier.height(1.2f.verticalGridUnitsAsDp()))

                    if (editMode) {
                        // The scroll bar sits in a reserved gutter at the very edge of the
                        // screen, so this spans the full width and the content is inset
                        // from the inside. The gutter is the same 2u as the side padding,
                        // so it doubles as the right margin and rows keep exactly the width
                        // they have at rest — the bar just occupies the margin they'd have
                        // left empty.
                        LightScrollView(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            scrollBarPosition = LightScrollBarPosition.Outside,
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = CONTENT_SIDE_PADDING_UNITS.gridUnitsAsDp()),
                            ) {
                                // One list, active first. Archived habits carry no separate
                                // heading: a dimmed name over UNARCHIVE/DELETE already says
                                // what they are, and a label set like a habit name only read
                                // as one more habit.
                                habits.forEachIndexed { index, habit ->
                                    if (index > 0) HabitSeparator(editMode = true)
                                    HabitBlock(
                                        habit = habit,
                                        completedDays = completions[habit.id] ?: emptySet(),
                                        weekStart = displayedWeekStart,
                                        todayIndex = todayIndex,
                                        todayEpoch = todayEpoch,
                                        startWeekEpoch = LocalDate.ofEpochDay(habit.createdAt).snappedToWeekStart(weekStart).toEpochDay(),
                                        editMode = true,
                                        onToggle = onToggle,
                                        onRename = { onRenameHabit(habit) },
                                        onArchive = { onArchiveHabit(habit) },
                                        onDelete = { onDeleteHabit(habit) },
                                    )
                                }

                                archivedHabits.forEachIndexed { index, habit ->
                                    if (index > 0 || habits.isNotEmpty()) {
                                        HabitSeparator(editMode = true)
                                    }
                                    ArchivedHabitBlock(
                                        habit = habit,
                                        onUnarchive = { onUnarchiveHabit(habit) },
                                        onDelete = { onDeleteHabit(habit) },
                                    )
                                }
                            }
                        }
                    } else {
                        DayLetterRow(
                            weekStart = displayedWeekStart,
                            todayIndex = todayIndex,
                            modifier = Modifier.padding(
                                horizontal = CONTENT_SIDE_PADDING_UNITS.gridUnitsAsDp(),
                            ),
                        )

                        Spacer(modifier = Modifier.height(1.2f.verticalGridUnitsAsDp()))

                        // Stacked from the top, leftover collecting at the bottom. Do not
                        // spread these with Arrangement.SpaceEvenly: it looks even only at
                        // three habits, opens a chasm at two and strands one in mid-screen.
                        // The bottom band is where the third habit goes, so leaving it empty
                        // keeps a row in place whether you track one or three.
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(horizontal = CONTENT_SIDE_PADDING_UNITS.gridUnitsAsDp()),
                            verticalArrangement = Arrangement.Top,
                        ) {
                            habits.forEachIndexed { index, habit ->
                                // Explicit again: the spread was supplying these gaps for
                                // free, and top-anchoring stacks the rows flush without them.
                                if (index > 0) HabitSeparator(editMode = false)
                                HabitBlock(
                                    habit = habit,
                                    completedDays = completions[habit.id] ?: emptySet(),
                                    weekStart = displayedWeekStart,
                                    todayIndex = todayIndex,
                                    todayEpoch = todayEpoch,
                                    startWeekEpoch = LocalDate.ofEpochDay(habit.createdAt).snappedToWeekStart(weekStart).toEpochDay(),
                                    editMode = false,
                                    onToggle = onToggle,
                                    onRename = { onRenameHabit(habit) },
                                    onArchive = { onArchiveHabit(habit) },
                                    onDelete = { onDeleteHabit(habit) },
                                )
                            }
                        }
                    }
                }
            }

            // Icon + text + icon takes LightBottomBar's mixed layout, and a text item caps
            // the bar at three slots. Ranked by frequency at rest: ticking needs no button,
            // so REPORT takes the centre and editing steps back to a pencil.
            //
            // `+` appears only while editing — on the resting screen it did nothing at
            // MAX_HABITS but raise a modal, which at least lands usefully next to ARCHIVE.
            LightBottomBar(
                items = listOf(
                    // No gear while editing. Edit mode is about the habits in front of you;
                    // an escape hatch to app preferences in the middle of that is an offer
                    // to do something unrelated. The slot stays (LightBarButtonView draws a
                    // spacer for a null item) so the two live controls don't slide sideways.
                    if (editMode) {
                        null
                    } else {
                        LightBarButton.LightIcon(
                            icon = LightIcons.SETTINGS,
                            contentDescription = "Settings",
                            onClick = onSettingsTapped,
                        )
                    },
                    if (editMode) {
                        LightBarButton.Text(text = "DONE", onClick = onToggleEditMode)
                    } else {
                        LightBarButton.Text(text = "REPORT", onClick = onReportTapped)
                    },
                    if (editMode) {
                        LightBarButton.LightIcon(
                            icon = LightIcons.ADD,
                            contentDescription = "Add habit",
                            onClick = onAddTapped,
                        )
                    } else {
                        LightBarButton.LightIcon(
                            icon = LightIcons.PENCIL,
                            contentDescription = "Edit habits",
                            onClick = onToggleEditMode,
                        )
                    },
                ),
            )
        }

        // Transient, dismissible explanation of the 3-habit cap. `+` still responds to
        // a tap at the cap — it just explains itself instead of silently doing nothing.
        limitMessage?.let { message ->
            LightFullscreenModal(
                message = message,
                onClose = onDismissLimitMessage,
            )
        }
    }
}

/**
 * Calm first-run / all-habits-archived state. No loud call to action — just an
 * explanation, in the same typography as the rest of the tool, that tapping `+`
 * is what to do next.
 */
@Composable
private fun EmptyHabitsContent(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        LightText(
            text = "No habits yet. Tap + to add one.",
            variant = LightTextVariant.Copy,
            align = TextAlign.Center,
            lighten = true,
            modifier = Modifier.padding(horizontal = CONTENT_SIDE_PADDING_UNITS.gridUnitsAsDp()),
        )
    }
}

private val MONTH_DAY_FORMAT = DateTimeFormatter.ofPattern("MMM d")
private val MONTH_DAY_YEAR_FORMAT = DateTimeFormatter.ofPattern("MMM d yyyy")
private val DAY_ONLY_FORMAT = DateTimeFormatter.ofPattern("d")

private fun weekRangeLabel(weekStart: LocalDate, today: LocalDate): String {
    val weekEnd = weekStart.plusDays(6)
    val endLabel = if (weekStart.month == weekEnd.month) {
        weekEnd.format(DAY_ONLY_FORMAT)
    } else {
        weekEnd.format(MONTH_DAY_FORMAT)
    }
    // A week viewed months into the past can straddle a year boundary a plain "MMM d"
    // start would silently misrepresent — spell out the year only when it differs from
    // the year currently on screen elsewhere, not on every past week.
    val startLabel = if (weekStart.year != today.year) {
        weekStart.format(MONTH_DAY_YEAR_FORMAT)
    } else {
        weekStart.format(MONTH_DAY_FORMAT)
    }
    return "$startLabel–$endLabel"
}
