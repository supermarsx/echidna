package com.echidna.app.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
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
import com.echidna.app.ui.whitelist.WhitelistEditorScreen
import com.echidna.app.ui.whitelist.WhitelistEditorViewModel

fun androidx.navigation.NavGraphBuilder.AppNavGraph(navController: NavHostController) {
    composable(AppDestination.Dashboard.route) {
        val viewModel: DashboardViewModel = viewModel()
        DashboardScreen(viewModel = viewModel)
    }
    composable(AppDestination.PresetManager.route) {
        val viewModel: PresetManagerViewModel = viewModel()
        val context = LocalContext.current
        val importLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent()
        ) { uri ->
            if (uri != null) {
                val json = context.contentResolver.openInputStream(uri)
                    ?.bufferedReader()?.use { reader -> reader.readText() }
                if (json != null) viewModel.importPreset(json)
            }
        }
        PresetManagerScreen(
            viewModel = viewModel,
            onImportRequest = { importLauncher.launch("application/json") },
            onExportResult = { json -> shareJson(context, json) },
            onShareResult = { json -> shareJson(context, json) }
        )
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
        SettingsScreen(
            viewModel = viewModel,
            onLaunchCompatibility = {
                navController.navigate(AppDestination.CompatibilityWizard.route)
            },
            onLaunchWhitelist = {
                navController.navigate(AppDestination.WhitelistEditor.route)
            }
        )
    }
    composable(AppDestination.CompatibilityWizard.route) {
        val viewModel: CompatibilityWizardViewModel = viewModel()
        CompatibilityWizardScreen(viewModel = viewModel, onFinish = {
            navController.popBackStack()
        })
    }
    composable(AppDestination.WhitelistEditor.route) {
        val viewModel: WhitelistEditorViewModel = viewModel()
        WhitelistEditorScreen(viewModel = viewModel)
    }
}

private fun shareJson(context: Context, json: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_TEXT, json)
    }
    context.startActivity(Intent.createChooser(intent, "Share preset"))
}
