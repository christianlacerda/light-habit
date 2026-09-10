package com.christianlacerda.habits.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.christianlacerda.habits.model.MAX_HABITS
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBarButtonDefaults
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIconConfiguration
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import com.thelightphone.sdk.ui.verticalGridUnitsAsDp
import java.time.YearMonth

/** Gap between a habit's name and the top of its plot. At the old 0.3u a bar at full height
 *  came within a hair of the name above it and the two read as one clump. */
private const val NAME_TO_PLOT_UNITS = 0.8f

/** Floor height for a month with at least one completion. At true scale one day in a month
 *  is a 2dp sliver indistinguishable from none; 4dp overstates it slightly, which is the
 *  better of the two lies. */
private val MIN_VISIBLE_BAR = 4.dp

/** [LightTopBar]'s own metrics, copied so [ReportPagingBar] lines up with every other
 *  screen's bar. Kept private here rather than shared — they mirror SDK internals, and the
 *  only honest way to keep them in step is to notice if the SDK's bar ever moves. */
private const val PAGING_BAR_HEIGHT_UNITS = 3f

private const val PAGING_BAR_PADDING_UNITS = 1f

/** Visible enough to hold its position and stay recognisable as a chevron, faint enough
 *  that it never reads as the live control sitting opposite it. */
private const val DISABLED_CHEVRON_ALPHA = 0.3f

/**
 * Monthly trend report, reached from REPORT in [HomeScreen]'s bottom bar.
 *
 * One line per habit, a bar per month, no numbers: the question is "more or less than
 * before", not "how many". Bar height carries the whole signal, and the bars identify the
 * screen, so it has no title.
 *
 * Active habits only — archived history is preserved and returns on unarchive, but drawing
 * it needed a scrollbar, which cost horizontal room on a screen that exists to be legible.
 * Read-only, and shares the one [HabitTrackerViewModel].
 */
class HabitReportScreen(
    sealedActivity: SealedLightActivity,
    private val viewModel: HabitTrackerViewModel,
) : SimpleLightScreen<Unit>(sealedActivity) {

    // Re-entry starts at the week grid: LightOS keeps the task alive, so without this,
    // reopening Habits lands back on the report.
    //
    // Unguarded on purpose. onAppPause also fires on screen-off and a tool cannot tell the
    // two apart — onUserLeaveHint lives in sdk/, and the sandbox blocks every fallback. The
    // cost is bounded here because the screen is read-only: at worst a timeout returns you
    // to the grid. HomeScreen and AddHabitScreen opt out, where guessing wrong would cost
    // a wrong write or typed input.
    override fun onAppPause() {
        goBack()
    }

    override fun willShow() {
        // A SimpleLightScreen never reaches LightViewModel.onScreenShow, so without this a
        // session left open across midnight would bucket today into yesterday's month.
        viewModel.refreshToday()
    }

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.state.collectAsState()
        val loaded by viewModel.loaded.collectAsState()
        val today by viewModel.today.collectAsState()

        // View state, not tool state: which six-month window is on screen has nothing to
        // persist and no bearing on the home grid, so it lives here rather than in the shared
        // view model. 0 = window ending this month, negative = older windows.
        var windowOffset by remember { mutableIntStateOf(0) }

        val habits = state.habits.filter { it.archivedAt == null }.sortedBy { it.order }

        val currentMonth = remember(today) { YearMonth.from(today) }
        // Floored on the habits actually drawn. Including archived ones here would let `‹`
        // walk back through months whose only habit renders nowhere on this screen — six
        // empty columns with no way to tell why.
        val earliestMonth = remember(habits, currentMonth) {
            earliestHabitMonth(habits, currentMonth)
        }
        val minOffset = remember(earliestMonth, currentMonth) {
            minWindowOffset(earliestMonth, currentMonth)
        }

        // Clamped on read rather than only on tap: archiving the oldest habit while parked in
        // an old window shrinks the record under our feet, and an offset past the new floor
        // would render six empty columns.
        val clampedOffset = windowOffset.coerceIn(minOffset, 0)
        val window = remember(currentMonth, clampedOffset) {
            monthWindow(currentMonth, clampedOffset)
        }

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                // Range in the middle, paging either side. Floored at the first recorded
                // month and never forward past the current window, so the report cannot show
                // a month that hasn't happened. The way out is Close at the bottom, which
                // leaves both bar slots free for navigation.
                ReportPagingBar(
                    label = windowLabel(window),
                    canGoEarlier = clampedOffset > minOffset,
                    canGoLater = clampedOffset < 0,
                    onEarlier = { windowOffset = clampedOffset - 1 },
                    onLater = { windowOffset = clampedOffset + 1 },
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 2f.gridUnitsAsDp()),
                ) {
                    if (!loaded) return@Column

                    if (habits.isEmpty()) {
                        EmptyReport(hasArchived = state.habits.any { it.archivedAt != null })
                        return@Column
                    }

                    // Same 1.2u the home screen puts below its top bar.
                    Spacer(modifier = Modifier.height(1.2f.verticalGridUnitsAsDp()))

                    // Equal shares of the height actually available — never a fixed budget
                    // in grid units. verticalGridUnitsAsDp divides screenHeightDp, not the
                    // window this is drawn into, and the text lines are sp-sized, so a fixed
                    // budget overflows and the last habit loses its baseline and month axis.
                    //
                    // Every slot is reserved regardless of habit count, so a habit keeps its
                    // place whether you track one or three. maxOf guards the cap itself.
                    repeat(maxOf(MAX_HABITS, habits.size)) { index ->
                        val habit = habits.getOrNull(index)
                        if (habit == null) {
                            Spacer(modifier = Modifier.weight(1f))
                        } else {
                            HabitTrendBlock(
                                bars = monthBars(habit, state, window, today),
                                name = habit.name,
                                currentMonth = currentMonth,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                // Text rather than a glyph: `‹` is the paging control on this screen, so the
                // way out has to be unmistakably not that. Sits in the bar the home screen
                // already uses for its controls, and a lone item centres itself there.
                LightBottomBar(
                    items = listOf(
                        LightBarButton.Text(text = "CLOSE", onClick = { goBack() }),
                    ),
                )
            }
        }
    }
}

/**
 * The report's own top bar, hand-built to [LightTopBar]'s metrics rather than using it.
 *
 * Both chevrons are always drawn, dimmed when there's nowhere to go — [LightBarButton] has
 * no disabled state, and a lone `‹` would read as "back" while meaning "back six months".
 * Position says what a chevron does; weight says whether it can.
 *
 * Mirrors [LightTopBar]'s metrics — 3u tall, 1u padding, [LightTextVariant.Fine] label,
 * [LightBarButtonDefaults.ICON_SIZE_UNITS] icons — so it sits at the same height as every
 * other screen's bar.
 */
@Composable
private fun ReportPagingBar(
    label: String,
    canGoEarlier: Boolean,
    canGoLater: Boolean,
    onEarlier: () -> Unit,
    onLater: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(PAGING_BAR_HEIGHT_UNITS.gridUnitsAsDp())
            .padding(horizontal = PAGING_BAR_PADDING_UNITS.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PagingChevron(
            icon = LightIcons.BACK,
            description = "Earlier months",
            enabled = canGoEarlier,
            onClick = onEarlier,
        )
        LightText(
            text = label,
            variant = LightTextVariant.Fine,
            align = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        PagingChevron(
            icon = LightIcons.ARROW_RIGHT,
            description = "Later months",
            enabled = canGoLater,
            onClick = onLater,
        )
    }
}

/** Dimmed *and* unclickable when disabled — a chevron that looks live and does nothing on
 *  tap is worse than one that never invited the tap. The content description drops with the
 *  click so a screen reader doesn't announce an action that isn't there. */
@Composable
private fun PagingChevron(
    icon: LightIconConfiguration,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    LightIcon(
        icon = icon,
        size = LightBarButtonDefaults.ICON_SIZE_UNITS,
        contentDescription = if (enabled) description else null,
        modifier = Modifier
            .alpha(if (enabled) 1f else DISABLED_CHEVRON_ALPHA)
            .then(
                if (enabled) {
                    Modifier.lightClickable(onClickLabel = description, onClick = onClick)
                } else {
                    Modifier
                },
            ),
    )
}

@Composable
private fun HabitTrendBlock(
    bars: List<MonthBar>,
    name: String,
    currentMonth: YearMonth,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(bottom = 0.6f.verticalGridUnitsAsDp())) {
        LightText(
            text = name,
            // Detail, matching the habit name above each week strip on the home screen. The
            // two screens name the same thing and should weigh the same doing it.
            variant = LightTextVariant.Detail,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(modifier = Modifier.height(NAME_TO_PLOT_UNITS.verticalGridUnitsAsDp()))

        // The plot is the one part of a block that may give way. Everything else is text
        // or a hairline, and all of it has to be on screen for a bar to say anything — so the
        // chart takes the slot's remainder rather than a height of its own, and
        // BoxWithConstraints hands that measured height to the bars scaled against it.
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            val chartHeight = maxHeight
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.Bottom,
            ) {
                bars.forEach { bar ->
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        MonthBarView(bar = bar, chartHeight = chartHeight)
                    }
                }
            }
        }

        // Baseline. Without it a month with no completions and empty space look the same, and
        // the bars have nothing to sit on.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(LightThemeTokens.colors.contentSecondary),
        )

        Spacer(modifier = Modifier.height(0.25f.verticalGridUnitsAsDp()))

        // Axis under its own line rather than one shared row up top: each chart then carries
        // its own labels in the same columns as its bars, so reading a bar never means
        // tracking back up past two other habits to find out which month it is.
        MonthLabelRow(bars = bars, currentMonth = currentMonth)
    }
}

/** Three-letter month names, not the single letters the day strip uses. S/M/T/W/T/F/S is a
 *  convention people already read; J/F/M/A/M/J is not, and three of those letters are
 *  ambiguous. There is room for the longer form at six columns. */
@Composable
private fun MonthLabelRow(bars: List<MonthBar>, currentMonth: YearMonth) {
    Row(modifier = Modifier.fillMaxWidth()) {
        bars.forEach { bar ->
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                LightText(
                    text = bar.month.format(MONTH_LABEL_FORMAT),
                    variant = LightTextVariant.Detail,
                    align = TextAlign.Center,
                    lighten = bar.month != currentMonth,
                    underline = bar.month == currentMonth,
                )
            }
        }
    }
}

@Composable
private fun MonthBarView(bar: MonthBar, chartHeight: Dp) {
    if (!bar.trackable) return

    val color = LightThemeTokens.colors.content
    val barHeight = (chartHeight * bar.fraction).coerceAtLeast(
        if (bar.fraction > 0f) MIN_VISIBLE_BAR else 0.dp,
    )
    if (barHeight <= 0.dp) return

    Box(
        modifier = Modifier
            .width(DAY_CELL_UNITS.gridUnitsAsDp())
            .height(barHeight)
            .then(
                // Hollow while the month is still running, solid once it's closed — the same
                // outline-vs-fill language the day cells already use for done/not-done. Without
                // it, every visit in the first days of a month would show a cliff that isn't real.
                if (bar.inProgress) Modifier.border(1.dp, color) else Modifier.background(color),
            ),
    )
}

@Composable
private fun EmptyReport(hasArchived: Boolean) {
    // Two different empties. "Nothing tracked yet" and "everything tracked is archived, and
    // this screen doesn't draw archived lines" look identical on screen, but only the second
    // has something the reader can do about it. Saying the same thing for both would leave a
    // person with real history staring at a screen that looks broken.
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        LightText(
            text = if (hasArchived) {
                "No active habits. Unarchive one to see its trend."
            } else {
                "Nothing to report yet."
            },
            variant = LightTextVariant.Copy,
            lighten = true,
            align = TextAlign.Center,
        )
    }
}
