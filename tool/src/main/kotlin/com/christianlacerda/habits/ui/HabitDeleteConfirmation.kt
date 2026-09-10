package com.christianlacerda.habits.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp

/**
 * Names what is lost — a plain "are you sure?" gets tapped through reflexively. A habit
 * with no completions omits the day count rather than saying "0 days", but still asks:
 * DELETE sits one tap from RENAME, on a device with no undo.
 */
internal fun deleteConfirmationMessage(habitName: String, completionCount: Int): String {
    if (completionCount == 0) return "Delete “$habitName”?"
    val days = if (completionCount == 1) "day" else "days"
    return "Delete “$habitName”? $completionCount $days will be erased."
}

/**
 * Inline two-choice confirmation, swapped in over [HomeScreen]'s content.
 * [com.thelightphone.sdk.ui.LightFullscreenModal] has room for one close button; this needs
 * cancel and confirm.
 */
@Composable
internal fun HabitDeleteConfirmationContent(message: String, onCancel: () -> Unit, onConfirm: () -> Unit) {
    val colors = LightThemeTokens.colors

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 2f.gridUnitsAsDp()),
            contentAlignment = Alignment.Center,
        ) {
            LightText(
                text = message,
                variant = LightTextVariant.Copy,
                align = TextAlign.Center,
            )
        }

        LightBottomBar(
            items = listOf(
                LightBarButton.Text(text = "CANCEL", onClick = onCancel),
                LightBarButton.Text(text = "DELETE", onClick = onConfirm),
            ),
        )
    }
}
