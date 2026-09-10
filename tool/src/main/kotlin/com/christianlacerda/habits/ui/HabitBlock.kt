package com.christianlacerda.habits.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.christianlacerda.habits.model.Habit
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import com.thelightphone.sdk.ui.verticalGridUnitsAsDp
import java.time.LocalDate

/**
 * One habit's row, in both of its modes.
 *
 * At rest the row is a name over its week strip. In edit mode the strip is replaced in place
 * by RENAME / ARCHIVE / DELETE, so managing a habit costs no extra screen. Row height is
 * pinned in both modes so toggling edit never shifts the grid.
 */

/**
 * Android's minimum touch target. Deliberately a raw dp rather than a grid unit: it is an
 * ergonomic floor tied to fingertip size, so it must stay 48dp however the 27x31 design grid
 * happens to map onto the device. A day cell's column is (27 - 2*2)/7 = 3.29u, about 50dp on
 * an LP3, so this fits with roughly a dp of clearance either side and adjacent cells never
 * overlap.
 */
internal val MIN_TOUCH_TARGET = 48.dp

/**
 * Not editing: the habit's name over its 7-day strip, cells individually tappable.
 *
 * Editing: the same name in the same place, with the strip swapped in place for the
 * habit's three management actions. The strip is inert in edit mode anyway — dimmed,
 * unclickable, present only to hold the layout — so the row's lower half is spent on
 * decoration. Putting Rename/Archive/Delete there instead costs no extra screen and no
 * extra tap, and every habit's actions are visible at once.
 *
 * The action row is deliberately [MIN_TOUCH_TARGET] tall, the same height the day strip
 * occupies, so a row's total height is identical in both modes and toggling edit doesn't
 * shift the grid vertically.
 */
@Composable
internal fun HabitBlock(
    habit: Habit,
    completedDays: Set<Long>,
    weekStart: LocalDate,
    todayIndex: Int,
    todayEpoch: Long,
    startWeekEpoch: Long,
    editMode: Boolean,
    onToggle: (habitId: String, epochDay: Long) -> Unit,
    onRename: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        LightText(
            text = habit.name,
            variant = LightTextVariant.Paragraph,
            // The grid allots exactly one line per habit name; a name that's somehow
            // longer than HABIT_NAME_MAX_LENGTH (shouldn't happen — the naming screen
            // enforces the cap while typing) degrades to an ellipsis instead of
            // wrapping and breaking the layout below it.
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(0.35f.verticalGridUnitsAsDp()))

        if (editMode) {
            HabitActionRow(
                HabitActionSpec("RENAME", "Rename ${habit.name}", onRename),
                HabitActionSpec("ARCHIVE", "Archive ${habit.name}", onArchive),
                HabitActionSpec("DELETE", "Delete ${habit.name}", onDelete),
            )
        } else {
            Row(modifier = Modifier.fillMaxWidth()) {
                for (dayIndex in 0..6) {
                    val epochDay = weekStart.plusDays(dayIndex.toLong()).toEpochDay()
                    // Both ends are out of range, but for opposite reasons, and the cell
                    // says which: a future day is one you can't tick *yet*, a pre-creation
                    // day is one there was never anything to tick.
                    val state = when {
                        epochDay < startWeekEpoch -> DayCellState.BEFORE_HABIT
                        epochDay > todayEpoch -> DayCellState.FUTURE
                        else -> DayCellState.TRACKABLE
                    }
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        DayCheckbox(
                            filled = epochDay in completedDays,
                            isToday = dayIndex == todayIndex,
                            state = state,
                            onToggle = if (state == DayCellState.TRACKABLE) {
                                { onToggle(habit.id, epochDay) }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Gap between two adjacent actions. Wide enough that DELETE is a deliberate reach from
 *  ARCHIVE rather than the next thing along, which matters more here than saving width —
 *  there's plenty of it going spare once the three are clustered. */
private const val HABIT_ACTION_GAP_UNITS = 1.5f

/**
 * The gap between two habit rows. Editing draws a line in it: the actions say a row does
 * something, this says where one row's actions end and the next row's begin. The line
 * splits the resting gap in half rather than adding to it, so the rhythm is identical in
 * both modes and toggling edit doesn't shift anything.
 */
@Composable
internal fun HabitSeparator(editMode: Boolean) {
    if (editMode) {
        Spacer(modifier = Modifier.height(0.6f.verticalGridUnitsAsDp()))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(LightThemeTokens.colors.contentSecondary),
        )
        Spacer(modifier = Modifier.height(0.6f.verticalGridUnitsAsDp()))
    } else {
        Spacer(modifier = Modifier.height(1.2f.verticalGridUnitsAsDp()))
    }
}

/**
 * An archived habit, listed below the active ones while editing.
 *
 * Same shape as an active row — name over a right-clustered action row — so the list reads
 * as one list. What separates the two is the row itself: a dimmed name over UNARCHIVE
 * against a full-strength name over RENAME/ARCHIVE. That contrast is the whole signal,
 * which is why the section carries no heading; a label set like a habit name only read as
 * one more habit.
 *
 * Two actions, not three: there's nothing to rename on something you aren't tracking, and
 * nothing to archive on something already archived. DELETE stays rightmost, the same place
 * it sits on an active row, so the destructive action is in one position throughout the
 * list rather than moving depending on which kind of row you're on.
 */
@Composable
internal fun ArchivedHabitBlock(habit: Habit, onUnarchive: () -> Unit, onDelete: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        LightText(
            text = habit.name,
            variant = LightTextVariant.Paragraph,
            lighten = true,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(0.35f.verticalGridUnitsAsDp()))

        HabitActionRow(
            HabitActionSpec("UNARCHIVE", "Unarchive ${habit.name}", onUnarchive),
            HabitActionSpec("DELETE", "Delete ${habit.name}", onDelete),
        )
    }
}

/** One action in a habit row: what it says, what it announces, what it does. */
private data class HabitActionSpec(
    val label: String,
    val clickLabel: String,
    val onClick: () -> Unit,
)

/**
 * A habit's management actions, occupying the band the day strip would otherwise fill.
 *
 * Clustered against the right rather than spread across the width: the habit name owns the
 * left of the row, so ending the actions on one vertical edge reads better than starting
 * them at unrelated places. Editing reserves a gutter for the scroll bar (see the
 * LightScrollView call in HabitTrackerScreen), so that edge can sit flush against the
 * content without a bar ever landing on top of DELETE.
 *
 * Underlined [LightTextVariant.Detail] keeps the actions from out-shouting the habit name,
 * which is set at the same size.
 *
 * The row is pinned to [MIN_TOUCH_TARGET], which is also what the day strip occupies, so a
 * habit's total height doesn't change when edit mode toggles. Each action fills that full
 * height, so its target is the band around the word, not just the glyphs.
 */
@Composable
private fun HabitActionRow(vararg actions: HabitActionSpec) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(MIN_TOUCH_TARGET),
        horizontalArrangement = Arrangement.spacedBy(
            space = HABIT_ACTION_GAP_UNITS.gridUnitsAsDp(),
            alignment = Alignment.End,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        actions.forEach { HabitAction(it.label, it.clickLabel, it.onClick) }
    }
}

@Composable
private fun HabitAction(label: String, clickLabel: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .lightClickable(onClickLabel = clickLabel, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        LightText(
            text = label,
            variant = LightTextVariant.Detail,
            underline = true,
            maxLines = 1,
        )
    }
}
