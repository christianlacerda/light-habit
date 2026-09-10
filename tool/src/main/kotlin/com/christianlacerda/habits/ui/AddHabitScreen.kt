package com.christianlacerda.habits.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.text.input.delete
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens

/**
 * A habit name gets one line in the week grid. 32 is scaled arithmetically from a cap of
 * 40 measured on-device against the smaller Detail variant, never re-measured against
 * Paragraph — if names ellipsize sooner than expected, measure rather than trust it.
 * [HabitBlock] ellipsizes as a backstop, and older names may exceed the cap since it only
 * governs typing.
 */
const val HABIT_NAME_MAX_LENGTH = 32

/**
 * Names a habit, for both add and rename — the same flow, pre-filled and relabelled.
 *
 * A screen rather than a modal because naming needs the LP3 keyboard, and
 * [com.thelightphone.sdk.ui.LightFullscreenModal] has no slot for a keyboard or a field.
 *
 * Returns the trimmed name via `goBack(name)`, or nothing on cancel — the back-stack only
 * invokes the caller's callback when a result was set.
 */
class AddHabitScreen(
    sealedActivity: SealedLightActivity,
    private val initialName: String = "",
    private val screenTitle: String = "Name Habit",
    private val submitLabel: String = "ADD",
) : SimpleLightScreen<String>(sealedActivity) {

    // Deliberately NO onAppPause reset: it also fires on screen-off, and here that would
    // discard a half-typed name. Resuming onto one is the cheaper wrong answer.
    @Composable
    override fun Content() {
        val textState = rememberTextFieldState(initialName)
        val themeColors by LightThemeController.colors.collectAsState()
        val keyboardOptionsFlow = rememberKeyboardOptions()

        // Truncate as-you-type, so the field never shows more than the grid can display.
        LaunchedEffect(textState) {
            snapshotFlow { textState.text.toString() }.collect { current ->
                if (current.length > HABIT_NAME_MAX_LENGTH) {
                    textState.edit {
                        delete(HABIT_NAME_MAX_LENGTH, current.length)
                        selection = TextRange(HABIT_NAME_MAX_LENGTH)
                    }
                }
            }
        }

        LightTheme(colors = themeColors) {
            LightTextInputEditor(
                title = screenTitle,
                state = textState,
                keyboardOptionsFlow = keyboardOptionsFlow,
                singleLine = true,
                submitLabel = submitLabel,
                onSubmit = { text ->
                    val trimmed = text.toString().trim()
                    // Silent no-op on an empty name: LightTextInputEditor has no error slot.
                    if (trimmed.isNotEmpty()) {
                        goBack(trimmed)
                    }
                },
                onBack = { goBack() },
                modifier = Modifier.background(LightThemeTokens.colors.background),
            )
        }
    }
}
