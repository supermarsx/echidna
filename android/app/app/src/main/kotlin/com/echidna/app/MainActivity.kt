package com.echidna.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.echidna.app.data.ControlStateRepository
import com.echidna.app.ui.AppDestination
import com.echidna.app.ui.AppIcons
import com.echidna.app.ui.AppNavGraph
import com.echidna.app.ui.theme.EchidnaTheme
import com.echidna.app.ui.theme.isDark

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings by ControlStateRepository.settingsState.collectAsStateWithLifecycle()
            val dark = settings.themeMode.isDark(isSystemInDarkTheme())

            // Keep the status-bar icons legible against the resolved scheme, and honor the
            // user's keep-screen-on choice while the app is in the foreground.
            val view = LocalView.current
            DisposableEffect(dark) {
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
                onDispose {}
            }
            DisposableEffect(settings.keepScreenOn) {
                if (settings.keepScreenOn) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                onDispose {}
            }

            EchidnaTheme(
                themeMode = settings.themeMode,
                dynamicColor = settings.dynamicColor,
                accentColor = settings.accentColor
            ) {
                Surface {
                    EchidnaApp()
                }
            }
        }
    }
}

@Composable
private fun EchidnaApp() {
    val navController = rememberNavController()
    val destinations = AppDestination.bottomDestinations
    Scaffold(
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination
                destinations.forEach { destination ->
                    NavigationBarItem(
                        icon = {
                            val icon = AppIcons.iconFor(destination)
                            Icon(imageVector = icon, contentDescription = destination.label)
                        },
                        label = { Text(destination.label) },
                        selected = currentDestination?.hierarchy?.any { it.route == destination.route } == true,
                        onClick = {
                            if (currentDestination?.route != destination.route) {
                                navController.navigate(destination.route) {
                                    popUpTo(AppDestination.Dashboard.route)
                                    launchSingleTop = true
                                }
                            }
                        },
                        colors = NavigationBarItemDefaults.colors()
                    )
                }
            }
        }
    ) { paddingValues ->
        NavHost(
            navController = navController,
            startDestination = AppDestination.Dashboard.route,
            modifier = Modifier.padding(paddingValues)
        ) {
            AppNavGraph(navController)
        }
    }
}
