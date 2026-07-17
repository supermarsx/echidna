package com.echidna.app

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.echidna.app.system.WidgetEngineState
import com.echidna.app.system.WidgetSupport
import com.echidna.app.system.engineWidgetState
import com.echidna.app.model.EngineStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Widget RemoteViews build/inflation smoke on a real device.
 *
 * The individual widget providers keep their `render` step private and only fire when a widget is
 * actually placed on a host, which an unrooted instrumentation run can't do. So this test rebuilds
 * the same [RemoteViews] each provider builds — the compact control widget, the wide preset widget,
 * and the large quick-controls hub — using the exact shared [WidgetSupport] state-mapping helpers,
 * then **inflates** them with [RemoteViews.apply]. Inflation executes every queued remote action
 * (`setImageViewResource` for the status dot, `setBackgroundResource`/`setTextColor` for the pills,
 * every `setTextViewText`) against real Views, so a broken layout id, a missing drawable/colour, or
 * a non-remotable setter would crash here. Every honest [WidgetEngineState] is exercised.
 *
 * Honest by construction: it also checks the *live* widget state derived from the real repository is
 * never fabricated as ACTIVE on this unrooted device (no native engine installed).
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class WidgetRemoteViewsInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun inflate(views: RemoteViews): View {
        val parent = FrameLayout(context)
        // apply() replays every queued RemoteViews action against freshly-inflated real Views; it
        // throws on a bad layout id, missing resource, or non-remotable setter.
        return views.apply(context, parent)
    }

    @Test
    fun compactControlWidgetInflatesForEveryEngineState() {
        for (state in WidgetEngineState.values()) {
            val views = RemoteViews(context.packageName, R.layout.widget_control)
            WidgetSupport.applyStatusDot(views, R.id.controlStatusDot, state)
            views.setTextViewText(R.id.controlStatusLabel, state.label)
            WidgetSupport.applyPill(context, views, R.id.controlMaster, "On", on = true)

            val root = inflate(views)
            assertNotNull("compact widget inflated for $state", root)
            assertNotNull(
                "status dot present for $state",
                root.findViewById<View>(R.id.controlStatusDot),
            )
            assertNotNull(root.findViewById<View>(R.id.controlMaster))
        }
    }

    @Test
    fun presetWidgetInflatesWithActivePresetName() {
        val views = RemoteViews(context.packageName, R.layout.widget_preset)
        views.setTextViewText(R.id.presetName, "Test Preset")
        val root = inflate(views)
        assertNotNull(root)
        assertNotNull(root.findViewById<View>(R.id.presetName))
        assertNotNull(root.findViewById<View>(R.id.presetPrev))
        assertNotNull(root.findViewById<View>(R.id.presetNext))
    }

    @Test
    fun quickHubWidgetInflatesForEveryEngineStateWithAllPills() {
        for (state in WidgetEngineState.values()) {
            val views = RemoteViews(context.packageName, R.layout.widget_quick)
            WidgetSupport.applyStatusDot(views, R.id.quickStatusDot, state)
            views.setTextViewText(R.id.quickStatusLabel, state.detail)
            views.setTextViewText(R.id.quickPresetName, "Test Preset")
            WidgetSupport.applyPill(context, views, R.id.quickMaster, "Master", on = true)
            WidgetSupport.applyPill(context, views, R.id.quickBypass, "Bypass", on = false)
            WidgetSupport.applyPill(context, views, R.id.quickSidetone, "Sidetone", on = false)

            val root = inflate(views)
            assertNotNull("quick hub inflated for $state", root)
            assertNotNull(root.findViewById<View>(R.id.quickStatusDot))
            assertNotNull(root.findViewById<View>(R.id.quickMaster))
            assertNotNull(root.findViewById<View>(R.id.quickPanic))
        }
    }

    @Test
    fun liveWidgetStateIsHonestlyNotActiveOnUnrootedDevice() {
        // The real repository is initialised by EchidnaApplication; on an unrooted emulator the
        // native engine is not installed, so the honest widget state must never read ACTIVE.
        val live = WidgetSupport.engineState()
        assertTrue(
            "unrooted device must not fabricate ACTIVE, was $live",
            live != WidgetEngineState.ACTIVE,
        )
    }

    @Test
    fun engineStateMappingMatchesEngineStatusPrecedenceOnDevice() {
        val notInstalled = EngineStatus(nativeInstalled = false, active = false, selinuxMode = "enforcing")
        assertEquals(
            WidgetEngineState.NOT_INSTALLED,
            engineWidgetState(notInstalled, masterEnabled = true, bypass = false),
        )

        val errored = EngineStatus(
            nativeInstalled = true,
            active = false,
            selinuxMode = "enforcing",
            lastError = "boom",
        )
        assertEquals(
            WidgetEngineState.ERROR,
            engineWidgetState(errored, masterEnabled = true, bypass = false),
        )

        val standby = EngineStatus(nativeInstalled = true, active = false, selinuxMode = "enforcing")
        assertEquals(
            WidgetEngineState.STANDBY,
            engineWidgetState(standby, masterEnabled = false, bypass = true),
        )
    }
}
