package com.echidna.app.ui.help

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric + Compose end-to-end coverage for the Help surface, driven with synthetic docs (no
 * asset dependency): the index lists grouped docs, search returns ranked results, tapping opens a
 * doc, Back returns to the index, and internal doc-to-doc links navigate within Help.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HelpScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private val docs = listOf(
        HelpDoc(
            id = "start.md", title = "Start", group = "Documentation", order = -1,
            markdown = "# Start\n\nGetting going.\n\n[Open Details](details.md)\n",
        ),
        HelpDoc(
            id = "details.md", title = "Details", group = "Documentation", order = 0,
            markdown = "# Details\n\n## Recovery\n\nThe bootloop recovery steps live here.\n",
        ),
        HelpDoc(
            id = "hardening/threat.md", title = "Threat Model", group = "Hardening", order = 0,
            markdown = "# Threat Model\n\nAdversary capabilities.\n",
        ),
    )

    private fun viewModel() = HelpViewModel(app) { docs }

    private fun setContent(vm: HelpViewModel = viewModel()) {
        composeRule.setContent { MaterialTheme { HelpScreen(viewModel = vm) } }
    }

    // Invokes a node's OnClick semantics action. Robolectric has no real input pipeline, so gesture
    // injection (performClick) is unreliable for LazyColumn items; the semantics action exercises the
    // same onClick wiring. Real-gesture coverage lives in the instrumentation tests (androidTest).
    private fun clickTag(tag: String) =
        composeRule.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick)

    @Test
    fun `index lists docs grouped`() {
        setContent()
        composeRule.onNodeWithTag(HelpTestTags.DOC_INDEX).assertExists()
        composeRule.onNodeWithText("Start").assertIsDisplayed()
        composeRule.onNodeWithText("Details").assertIsDisplayed()
        composeRule.onNodeWithText("Threat Model").assertIsDisplayed()
        // Group headers derived from the docs layout.
        composeRule.onNodeWithText("Documentation").assertIsDisplayed()
        composeRule.onNodeWithText("Hardening").assertIsDisplayed()
    }

    @Test
    fun `search returns results and tapping opens the doc`() {
        setContent()
        composeRule.onNodeWithTag(HelpTestTags.SEARCH_FIELD).performTextInput("bootloop")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(HelpTestTags.RESULTS).assertExists()
        clickTag(HelpTestTags.resultRow(0))
        composeRule.waitForIdle()
        // The doc view is shown with the matching doc (its recovery section body renders).
        composeRule.onNodeWithTag(HelpTestTags.DOC_VIEW).assertExists()
        composeRule.onNodeWithTag(HelpTestTags.DOC_TITLE).assertIsDisplayed()
        composeRule.onNodeWithText("recovery steps", substring = true).assertIsDisplayed()
    }

    @Test
    fun `no matches shows an empty-state message`() {
        setContent()
        composeRule.onNodeWithTag(HelpTestTags.SEARCH_FIELD).performTextInput("zzzznotpresent")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(HelpTestTags.NO_RESULTS).assertExists()
    }

    @Test
    fun `tapping a doc opens it and Back returns to the index`() {
        val vm = viewModel()
        setContent(vm)
        clickTag(HelpTestTags.docRow("start.md"))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(HelpTestTags.DOC_VIEW).assertExists()

        clickTag(HelpTestTags.DOC_BACK)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(HelpTestTags.DOC_INDEX).assertExists()
    }

    @Test
    fun `opening a doc renders its Markdown and in-doc links`() {
        setContent()
        clickTag(HelpTestTags.docRow("start.md"))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(HelpTestTags.DOC_VIEW).assertExists()
        // The doc's Markdown — including the doc-to-doc link text — is rendered. The actual link tap
        // (a ClickableText gesture) is covered by the instrumentation test where real input works.
        composeRule.onNodeWithText("Getting going", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Open Details", substring = true).assertIsDisplayed()
    }

    @Test
    fun `viewModel back stack reports handled navigation`() {
        val vm = viewModel()
        assertTrue(vm.state.value.isHome)
        vm.openDoc("start.md")
        vm.openDoc("details.md")
        // Back to start, then to home.
        assertTrue(vm.back())
        assertTrue(vm.state.value.openDocId == "start.md")
        assertTrue(vm.back())
        assertTrue(vm.state.value.isHome)
        // Nothing left to handle.
        assertTrue(!vm.back())
    }
}
