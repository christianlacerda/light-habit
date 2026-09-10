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

/** How far a pre-creation day's border fades below `contentSecondary`. Alpha rather than a
 *  third colour: the theme has two content tones, and fading one works in both schemes. */
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
 * A single day's check box. Filled = done, outlined = not done, both neutral — a miss gets
 * no "streak broken" styling. Today adds a separate outer ring with a gap, since a thicker
 * same-colour border would vanish against a filled square.
 *
 * The three states are three weights of one square: [DayCellState.FUTURE] dimmed to
 * `contentSecondary`, [DayCellState.BEFORE_HABIT] fainter still. Drawing those two alike
 * made a newly added habit look broken, and omitting the pre-creation square entirely read
 * as a failure to draw rather than as a statement.
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
    // Hit area only — the halo keeps its drawn size, so the today ring doesn't inflate.
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
                // Never filled before creation: toggling is gated to TRACKABLE.
                .background(if (filled) colors.content else Color.Transparent),
        )
    }
}
