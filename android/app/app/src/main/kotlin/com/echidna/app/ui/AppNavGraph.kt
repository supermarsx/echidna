package com.echidna.app.ui

import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.echidna.app.ui.compatibility.CompatibilityWizardScreen
import com.echidna.app.ui.compatibility.CompatibilityWizardViewModel
import com.echidna.app.ui.dashboard.DashboardScreen
import com.echidna.app.ui.dashboard.DashboardViewModel
import com.echidna.app.ui.diagnostics.DiagnosticsScreen
import com.echidna.app.ui.diagnostics.DiagnosticsViewModel
import com.echidna.app.ui.effects.EffectsEditorScreen
import com.echidna.app.ui.effects.EffectsEditorViewModel
import com.echidna.app.ui.preset.PresetManagerScreen
import com.echidna.app.ui.preset.PresetManagerViewModel
import com.echidna.app.ui.settings.SettingsScreen
import com.echidna.app.ui.settings.SettingsViewModel

fun androidx.navigation.NavGraphBuilder.AppNavGraph(navController: NavHostController) {
    composable(AppDestination.Dashboard.route) {
        val viewModel: DashboardViewModel = viewModel()
        DashboardScreen(viewModel = viewModel)
    }
    composable(AppDestination.PresetManager.route) {
        val viewModel: PresetManagerViewModel = viewModel()
        PresetManagerScreen(viewModel = viewModel)
    }
    composable(AppDestination.EffectsEditor.route) {
        val viewModel: EffectsEditorViewModel = viewModel()
        EffectsEditorScreen(viewModel = viewModel)
    }
    composable(AppDestination.Diagnostics.route) {
        val viewModel: DiagnosticsViewModel = viewModel()
        DiagnosticsScreen(viewModel = viewModel)
    }
    composable(AppDestination.Settings.route) {
        val viewModel: SettingsViewModel = viewModel()
        SettingsScreen(viewModel = viewModel, onLaunchCompatibility = {
            navController.navigate(AppDestination.CompatibilityWizard.route)
        })
    }
    composable(AppDestination.CompatibilityWizard.route) {
        val viewModel: CompatibilityWizardViewModel = viewModel()
        CompatibilityWizardScreen(viewModel = viewModel, onFinish = {
            navController.popBackStack()
        })
    }
}
