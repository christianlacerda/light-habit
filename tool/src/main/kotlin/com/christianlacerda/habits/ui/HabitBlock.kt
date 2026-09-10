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

/** Android's minimum touch target. A raw dp, not a grid unit — it is tied to fingertip size,
 *  so it must stay 48dp however the 27x31 grid maps onto the device. A day column is 3.29u,
 *  about 50dp on an LP3, so cells never overlap. */
internal val MIN_TOUCH_TARGET = 48.dp

/**
 * At rest: the habit's name over its tappable 7-day strip. Editing: the same name, with the
 * strip swapped for RENAME / ARCHIVE / DELETE, so managing a habit costs no extra screen.
 *
 * The action row is [MIN_TOUCH_TARGET] tall — the strip's height — so toggling edit mode
 * never shifts the grid.
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
            // One line per name. A name past HABIT_NAME_MAX_LENGTH ellipsizes rather
            // than wrapping and breaking the layout below.
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
                    // Out of range at both ends, for opposite reasons: a future day can't
                    // be ticked *yet*, a pre-creation day never could.
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
 * Same shape as an active row, so the list reads as one list; the dimmed name over two
 * actions is the whole signal, which is why the section has no heading. Two actions, not
 * three — nothing to rename or archive here. DELETE stays rightmost throughout the list.
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
 * Clustered right, since the name owns the left. Edit mode reserves a scroll-bar gutter, so
 * the edge sits flush without a bar landing on DELETE.
 *
 * Pinned to [MIN_TOUCH_TARGET] — the day strip's height — so toggling edit shifts nothing,
 * and each action fills it, making the target the band rather than the glyphs.
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
