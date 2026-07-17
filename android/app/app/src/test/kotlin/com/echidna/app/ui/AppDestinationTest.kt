package com.echidna.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the app-wide top-bar title dedupe (t18). The bar must not repeat a title a screen already
 * renders in its own content, and screens without an in-content header must still get a title.
 */
class AppDestinationTest {

    @Test
    fun ownHeaderScreens_suppressTheTopBarTitle() {
        // Every screen that draws its own in-content header returns no top-bar title, so it is not
        // double-titled. Settings is the reported regression: its own "Settings" header + a bar
        // "Settings" showed the word twice.
        AppDestination.allDestinations
            .filter { it.rendersOwnTitle }
            .forEach { destination ->
                assertNull(
                    "${destination.route} renders its own header, so the top bar must show no title",
                    AppDestination.topBarTitle(destination.route),
                )
            }
        assertTrue("Settings should render its own header", AppDestination.Settings.rendersOwnTitle)
        assertNull(AppDestination.topBarTitle(AppDestination.Settings.route))
    }

    @Test
    fun headerlessScreens_getTheirTitleFromTheTopBar() {
        // Dashboard and Help have no in-content header — the bar supplies their sole title.
        assertEquals("Dashboard", AppDestination.topBarTitle(AppDestination.Dashboard.route))
        assertEquals("Help", AppDestination.topBarTitle(AppDestination.Help.route))
    }

    @Test
    fun everyRouteHasExactlyOneTitleSource() {
        // No screen may end up with zero titles: it either supplies its own header, or the bar does.
        AppDestination.allDestinations.forEach { destination ->
            val barTitle = AppDestination.topBarTitle(destination.route)
            assertTrue(
                "${destination.route} must have exactly one title source (own header xor top bar)",
                destination.rendersOwnTitle != (barTitle != null),
            )
        }
    }

    @Test
    fun unknownRoute_fallsBackToAppName() {
        assertEquals("Echidna", AppDestination.topBarTitle(null))
        assertEquals("Echidna", AppDestination.topBarTitle("nonexistent"))
    }
}
