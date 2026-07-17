package com.echidna.app.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.echidna.app.ui.alerts.AlertsScreen
import com.echidna.app.ui.alerts.AlertsViewModel
import com.echidna.app.ui.compatibility.CompatibilityWizardScreen
import com.echidna.app.ui.compatibility.CompatibilityWizardViewModel
import com.echidna.app.ui.dashboard.DashboardScreen
import com.echidna.app.ui.dashboard.DashboardViewModel
import com.echidna.app.ui.diagnostics.DiagnosticsScreen
import com.echidna.app.ui.diagnostics.DiagnosticsViewModel
import com.echidna.app.ui.effects.EffectsEditorScreen
import com.echidna.app.ui.effects.EffectsEditorViewModel
import com.echidna.app.ui.help.HelpScreen
import com.echidna.app.ui.help.HelpViewModel
import com.echidna.app.ui.install.InstallEngineScreen
import com.echidna.app.ui.install.InstallEngineViewModel
import com.echidna.app.ui.lab.LabScreen
import com.echidna.app.ui.lab.LabViewModel
import com.echidna.app.ui.onboarding.OnboardingViewModel
import com.echidna.app.ui.onboarding.OnboardingWizardHost
import com.echidna.app.ui.preset.PresetManagerScreen
import com.echidna.app.ui.preset.PresetManagerViewModel
import com.echidna.app.ui.settings.SettingsScreen
import com.echidna.app.ui.settings.SettingsViewModel
import com.echidna.app.ui.whitelist.WhitelistEditorScreen
import com.echidna.app.ui.whitelist.WhitelistEditorViewModel

fun androidx.navigation.NavGraphBuilder.AppNavGraph(navController: NavHostController) {
    composable(AppDestination.Dashboard.route) {
        val viewModel: DashboardViewModel = viewModel()
        DashboardScreen(
            viewModel = viewModel,
            onOpenInstall = { navController.navigate(AppDestination.InstallEngine.route) }
        )
    }
    composable(AppDestination.InstallEngine.route) {
        val viewModel: InstallEngineViewModel = viewModel()
        InstallEngineScreen(
            viewModel = viewModel,
            onClose = { navController.popBackStack() }
        )
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
    composable(AppDestination.Alerts.route) {
        val viewModel: AlertsViewModel = viewModel()
        AlertsScreen(
            viewModel = viewModel,
            onOpenInstall = { navController.navigate(AppDestination.InstallEngine.route) },
            onLaunchWhitelist = {
                navController.navigate(AppDestination.WhitelistEditor.route)
            },
            onLaunchCompatibility = {
                navController.navigate(AppDestination.CompatibilityWizard.route)
            }
        )
    }
    composable(AppDestination.Diagnostics.route) {
        val viewModel: DiagnosticsViewModel = viewModel()
        DiagnosticsScreen(viewModel = viewModel)
    }
    composable(AppDestination.Lab.route) {
        val viewModel: LabViewModel = viewModel()
        LabScreen(viewModel = viewModel)
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
            },
            onLaunchInstaller = {
                navController.navigate(AppDestination.InstallEngine.route)
            },
            onOpenAlerts = {
                navController.navigate(AppDestination.Alerts.route) {
                    popUpTo(AppDestination.Dashboard.route)
                    launchSingleTop = true
                }
            },
            onRunSetupAgain = {
                // Re-arm the persisted first-run flag, then re-enter the wizard (t14).
                viewModel.rerunOnboarding()
                navController.navigate(AppDestination.Onboarding.route) {
                    launchSingleTop = true
                }
            },
            onOpenHelp = {
                navController.navigate(AppDestination.Help.route) { launchSingleTop = true }
            }
        )
    }
    composable(AppDestination.Help.route) {
        val viewModel: HelpViewModel = viewModel()
        HelpScreen(viewModel = viewModel)
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
    // t14: first-run onboarding wizard (rendered full-screen; see MainActivity chrome gating).
    composable(AppDestination.Onboarding.route) {
        val viewModel: OnboardingViewModel = viewModel()
        OnboardingWizardHost(
            viewModel = viewModel,
            onFinished = {
                navController.navigate(AppDestination.Dashboard.route) {
                    popUpTo(AppDestination.Onboarding.route) { inclusive = true }
                    launchSingleTop = true
                }
            },
            onOpenInstaller = {
                // Land Dashboard under the installer so its Close returns into the app, not out of it.
                navController.navigate(AppDestination.Dashboard.route) {
                    popUpTo(AppDestination.Onboarding.route) { inclusive = true }
                    launchSingleTop = true
                }
                navController.navigate(AppDestination.InstallEngine.route)
            },
            onOpenLab = {
                navController.navigate(AppDestination.Dashboard.route) {
                    popUpTo(AppDestination.Onboarding.route) { inclusive = true }
                    launchSingleTop = true
                }
                navController.navigate(AppDestination.Lab.route) {
                    launchSingleTop = true
                }
            },
        )
    }
}

private fun shareJson(context: Context, json: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_TEXT, json)
    }
    context.startActivity(Intent.createChooser(intent, "Share preset"))
}
