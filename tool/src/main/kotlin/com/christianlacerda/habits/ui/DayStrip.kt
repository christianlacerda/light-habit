package com.christianlacerda.habits.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import java.time.DayOfWeek
import java.time.LocalDate
import kotlinx.coroutines.flow.map

/**
 * The seven day cells and the S M T W T F S header above them.
 *
 * A cell has three weights, not two states — trackable, still in the future, and before the
 * habit existed — because a newly added habit looked broken when those last two were drawn
 * alike.
 */

private fun dayLetterFor(dayOfWeek: DayOfWeek): String = when (dayOfWeek) {
    DayOfWeek.SUNDAY -> "S"
    DayOfWeek.MONDAY -> "M"
    DayOfWeek.TUESDAY -> "T"
    DayOfWeek.WEDNESDAY -> "W"
    DayOfWeek.THURSDAY -> "T"
    DayOfWeek.FRIDAY -> "F"
    DayOfWeek.SATURDAY -> "S"
}

/** Width of a day checkbox, in grid units. Also the width of a month bar in
 *  [HabitReportScreen], so the report's columns read as the same instrument as the
 *  week strip rather than a chart bolted on. */
internal const val DAY_CELL_UNITS = 2.3f

/**
 * How far a pre-creation day's border fades below `contentSecondary`, which future days
 * already use. Alpha rather than a third palette colour: the theme has exactly two content
 * tones, and fading the dimmer of them lands a step further back in both light and dark
 * without inventing a colour that would have to be defined twice.
 */
private const val BEFORE_HABIT_BORDER_ALPHA = 0.45f

@Composable
internal fun DayLetterRow(weekStart: LocalDate, todayIndex: Int, modifier: Modifier = Modifier) {
    val letters = remember(weekStart) {
        (0..6).map { dayLetterFor(weekStart.plusDays(it.toLong()).dayOfWeek) }
    }
    Row(modifier = modifier.fillMaxWidth()) {
        letters.forEachIndexed { index, letter ->
            val isToday = index == todayIndex
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                LightText(
                    text = letter,
                    variant = LightTextVariant.Detail,
                    align = TextAlign.Center,
                    lighten = !isToday,
                    underline = isToday,
                )
            }
        }
    }
}

/** What a day cell can be, relative to the habit it belongs to. */
internal enum class DayCellState {
    /** In range: tappable, and either done or not. */
    TRACKABLE,

    /** After today. The day will arrive; it just hasn't. */
    FUTURE,

    /** Before the week the habit was created in. This day is not un-ticked — it's a day
     *  the habit didn't exist for, and no tap will ever make it tickable. */
    BEFORE_HABIT,
}

/**
 * A single day's check box. Filled = done, outlined = not done — both are neutral;
 * there's no "streak broken" styling for a miss. Today gets a separate outer ring
 * with a visible gap from the box itself, so the emphasis reads in monochrome even
 * when the box is filled (a same-color thicker border on a filled square would be
 * invisible against its own fill).
 *
 * The three states are three weights of the same square, not three different things. A
 * [DayCellState.FUTURE] day is dimmed to `contentSecondary`; a [DayCellState.BEFORE_HABIT]
 * day is fainter still, [BEFORE_HABIT_BORDER_ALPHA] of that. Drawing those two identically
 * is what made a newly added habit look broken — page back a week and its untappable cells
 * were indistinguishable from unreachable future ones, with nothing saying why.
 *
 * Omitting the pre-creation square altogether was tried and reads as a rendering failure
 * rather than a statement: a row that simply stops has no way to say whether it means "not
 * applicable" or "failed to draw". Keeping the square and receding it says the days are
 * there and not yours to fill, which is the actual situation.
 *
 * Edit mode needs no muted variant of this: the strip isn't drawn at all while editing
 * (see [HabitBlock]), so there is no inert grid on screen for a tap to look live against.
 */
@Composable
internal fun DayCheckbox(
    filled: Boolean,
    isToday: Boolean,
    state: DayCellState,
    onToggle: (() -> Unit)?,
) {
    val colors = LightThemeTokens.colors
    val cellSize = DAY_CELL_UNITS.gridUnitsAsDp()
    val haloSize = cellSize + 10.dp
    // Hit area only. The halo stays at its drawn size so raising the target doesn't inflate
    // the today ring along with it — 45dp of drawn halo, 48dp of tappable box around it.
    val touchSize = maxOf(haloSize, MIN_TOUCH_TARGET)

    Box(
        modifier = Modifier
            .size(touchSize)
            .let { base ->
                if (onToggle != null) {
                    base.lightClickable(
                        onClickLabel = "Toggle completion",
                        onClick = onToggle,
                    )
                } else {
                    base
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (isToday) {
            Box(
                modifier = Modifier
                    .size(haloSize)
                    .border(width = 1.5.dp, color = colors.content),
            )
        }
        Box(
            modifier = Modifier
                .size(cellSize)
                .border(
                    width = 1.dp,
                    color = when (state) {
                        DayCellState.TRACKABLE -> colors.content
                        DayCellState.FUTURE -> colors.contentSecondary
                        DayCellState.BEFORE_HABIT ->
                            colors.contentSecondary.copy(alpha = BEFORE_HABIT_BORDER_ALPHA)
                    },
                )
                // A pre-creation day is never filled: toggling is gated to TRACKABLE, so
                // there is no path to a completion before the habit's creation week.
                .background(if (filled) colors.content else Color.Transparent),
        )
    }
}
