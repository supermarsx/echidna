package com.echidna.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.ui.graphics.vector.ImageVector

object AppIcons {
    fun iconFor(destination: AppDestination): ImageVector = when (destination.route) {
        AppDestination.Dashboard.route -> Icons.Filled.Dashboard
        AppDestination.PresetManager.route -> Icons.Filled.Tune
        AppDestination.EffectsEditor.route -> Icons.Filled.Build
        AppDestination.Diagnostics.route -> Icons.Filled.Analytics
        AppDestination.Settings.route -> Icons.Filled.Settings
        else -> Icons.Filled.Dashboard
    }
}
