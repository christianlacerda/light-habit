package com.christianlacerda.habits.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.christianlacerda.habits.model.WeekStart
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

/**
 * Preferences, reached via the gear in [HomeScreen]'s bottom bar. Thin on purpose: habit
 * management lives on the rows in [HomeScreen]'s edit mode, leaving one real preference.
 *
 * Takes the shared [HabitTrackerViewModel] by constructor — [SimpleLightScreen] has no
 * ViewModelStore of its own, and the state is already loaded and live.
 */
class HabitSettingsScreen(
    sealedActivity: SealedLightActivity,
    private val viewModel: HabitTrackerViewModel,
) : SimpleLightScreen<Unit>(sealedActivity) {

    // Re-entry starts at the week grid. Unguarded is safe here because the radio commits
    // immediately, so a screen-off pause loses nothing — see HabitReportScreen.onAppPause.
    override fun onAppPause() {
        goBack()
    }

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.state.collectAsState()

        LightTheme(colors = themeColors) {
            HabitSettingsContent(
                weekStart = state.weekStart,
                onBack = { goBack() },
                onSetWeekStart = viewModel::setWeekStart,
            )
        }
    }
}

@Composable
private fun HabitSettingsContent(
    weekStart: WeekStart,
    onBack: () -> Unit,
    onSetWeekStart: (WeekStart) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.BACK,
                onClick = onBack,
                contentDescription = "Back",
            ),
            center = LightTopBarCenter.Text("Settings"),
            modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
        )

        // No scroll container: one section of two rows cannot overflow, and a scrollbar
        // gutter on a screen this short only advertises content that isn't there.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 1f.gridUnitsAsDp()),
        ) {
            SectionHeader(text = "WEEK STARTS ON")
            WeekStartRow(
                label = "Sunday",
                selected = weekStart == WeekStart.SUNDAY,
                onClick = { onSetWeekStart(WeekStart.SUNDAY) },
            )
            WeekStartRow(
                label = "Monday",
                selected = weekStart == WeekStart.MONDAY,
                onClick = { onSetWeekStart(WeekStart.MONDAY) },
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    LightText(
        text = text,
        variant = LightTextVariant.Detail,
        lighten = true,
        modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp()),
    )
}

/** One radio-style row per option. There is no deselect: one of Sunday/Monday always applies. */
@Composable
private fun WeekStartRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(vertical = 0.75f.gridUnitsAsDp(), horizontal = 0.25f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightIcon(
            icon = if (selected) LightIcons.SELECT_ON else LightIcons.SELECT_OFF,
            size = 1.6f,
            modifier = Modifier.padding(end = 0.75f.gridUnitsAsDp()),
        )
        LightText(text = label, variant = LightTextVariant.Copy)
    }
}
