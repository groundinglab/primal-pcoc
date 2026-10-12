package net.primal.android.pcoc.verify

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.primal.android.R
import net.primal.android.pcoc.verify.VerifyPcocContract.MeasureRow
import net.primal.android.pcoc.verify.VerifyPcocContract.Notice
import net.primal.android.pcoc.verify.VerifyPcocContract.UiState
import net.primal.android.stream.player.StreamStateProvider
import net.primal.android.theme.PrimalTheme
import net.primal.android.theme.domain.PrimalTheme as ThemeChoice
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w480dp-h2000dp")
class VerifyPcocScreenTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(HostActivityRule()).around(compose)

    @Test
    fun `a finished check lists the measurement record`() {
        show(UiState(recorded = true, rows = sampleRows()))

        compose.onNodeWithText("A measurement record was written.").assertIsDisplayed()
        compose.onNodeWithText("Profile").assertIsDisplayed()
        compose.onNodeWithText("production").assertIsDisplayed()
        compose.onNodeWithText("Shape").assertIsDisplayed()
        compose.onNodeWithText("match").assertIsDisplayed()
        compose.onNodeWithText("Headers").assertIsDisplayed()
        compose.onNodeWithText("Checkpoints").assertIsDisplayed()
        compose.onNodeWithText("every one is mismatch").assertIsDisplayed()
        compose.onNodeWithText("Waypoints").assertIsDisplayed()
        compose.onNodeWithText("Six leg").assertIsDisplayed()
        compose.onNodeWithText("not defined").assertIsDisplayed()
        compose.onNodeWithText("Portable index").assertIsDisplayed()
        compose.onNodeWithText("Portable index did not answer · index.groundinglab.io · 2 reads").assertIsDisplayed()
        absentVerdicts()
    }

    @Test
    fun `an unreadable file is a plain message`() {
        show(UiState(notice = Notice.CouldNotRead))

        compose.onNodeWithText("The object file could not be read.").assertIsDisplayed()
        compose.onNodeWithText("A measurement record was written.").assertDoesNotExist()
        compose.onNodeWithText("Profile").assertDoesNotExist()
        absentVerdicts()
    }

    @Test
    fun `an unfinished run and an undecodable file are plain messages`() {
        val state = mutableStateOf(UiState(notice = Notice.DidNotFinish))
        show { state.value }
        compose.onNodeWithText("Verify did not finish.").assertIsDisplayed()
        absentVerdicts()

        state.value = UiState(notice = Notice.Unreadable)
        compose.onNodeWithText("This file is not a hike object this verifier can decode, so there is no record.")
            .assertIsDisplayed()
        compose.onNodeWithText("Verify did not finish.").assertDoesNotExist()
        absentVerdicts()
    }

    @Test
    fun `a running check shows measuring and not a record`() {
        show(UiState(busy = true))

        compose.onNodeWithText("Measuring · reading the portable index").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
        compose.onNodeWithText("A measurement record was written.").assertDoesNotExist()
        absentVerdicts()
    }

    private fun show(state: UiState) {
        show { state }
    }

    private fun show(state: () -> UiState) {
        compose.setContent {
            StreamStateProvider {
                PrimalTheme(primalTheme = ThemeChoice.Ice) {
                    VerifyPcocScreen(state = state(), onClose = {}, eventPublisher = {})
                }
            }
        }
    }

    private fun absentVerdicts() {
        compose.onNodeWithText("valid").assertDoesNotExist()
        compose.onNodeWithText("invalid").assertDoesNotExist()
        compose.onNodeWithText("accept").assertDoesNotExist()
        compose.onNodeWithText("deny").assertDoesNotExist()
        compose.onNodeWithText("refuse").assertDoesNotExist()
    }

    private class HostActivityRule : org.junit.rules.TestRule {
        override fun apply(base: Statement, description: Description): Statement =
            object : Statement() {
                override fun evaluate() {
                    val context = ApplicationProvider.getApplicationContext<Application>()
                    val component = ComponentName(context.packageName, ComponentActivity::class.java.name)
                    val shadow = shadowOf(context.packageManager)
                    val info = shadow.addActivityIfNotPresent(component)
                    info.exported = true
                    shadow.addOrUpdateActivity(info)
                    shadow.addIntentFilterForActivity(
                        component,
                        IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
                    )
                    base.evaluate()
                }
            }
    }

    private fun sampleRows(): List<MeasureRow> =
        listOf(
            MeasureRow(R.string.pcoc_verify_row_profile, "production"),
            MeasureRow(R.string.pcoc_verify_row_shape, "match"),
            MeasureRow(R.string.pcoc_verify_row_headers, "every one is not read"),
            MeasureRow(R.string.pcoc_verify_row_checkpoints, "every one is mismatch"),
            MeasureRow(R.string.pcoc_verify_row_waypoints, "every one is not read"),
            MeasureRow(R.string.pcoc_verify_row_six_leg, "not defined"),
            MeasureRow(
                R.string.pcoc_verify_row_index,
                "Portable index did not answer · index.groundinglab.io · 2 reads",
            ),
        )
}
