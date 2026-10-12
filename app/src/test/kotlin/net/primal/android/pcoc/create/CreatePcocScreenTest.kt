package net.primal.android.pcoc.create

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.kotest.matchers.shouldBe
import net.primal.android.pcoc.PcocExportAction
import net.primal.android.pcoc.PcocExportKind
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
class CreatePcocScreenTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(HostActivityRule()).around(compose)

    @Test
    fun `idle offers a start when the account has a public key`() {
        show(
            CreateUiState(
                npub = "npub1example",
                outputPath = "/data/pcoc/hikes/hike.cbor",
            ),
        )
        compose.onNodeWithText("New hike").assertIsDisplayed()
        compose.onNodeWithText("npub1example").assertIsDisplayed()
        compose.onNodeWithText("Start hike").assertIsDisplayed()
        compose.onNodeWithText("https://bari.groundinglab.io", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Coming soon").assertDoesNotExist()
    }

    @Test
    fun `produce waits out loud for the latest header`() {
        show(CreateUiState(phase = ProducePhase.Running))
        compose.onNodeWithText("Waiting for latest Bitcoin header…").assertIsDisplayed()
    }

    @Test
    fun `a running hike shows the stage and stop`() {
        show(
            CreateUiState(
                phase = ProducePhase.Running,
                stage = 2,
                stagesClosed = 2,
                legsTotal = 40,
                seedHeight = 899_990,
                openBeaconHeight = 899_998,
                tipHeight = 900_000,
                outputPath = "/data/pcoc/hikes/hike.cbor",
                closedStages = listOf(
                    ClosedStage(stage = 0, blockHeight = 1001),
                    ClosedStage(stage = 1, blockHeight = 1002),
                ),
                waypoints = listOf(
                    WaypointRow(index = 1, status = WaypointStatus.Posted),
                    WaypointRow(index = 2, status = WaypointStatus.Anchored),
                ),
                legRatePerSecond = 1.5,
            ),
        )
        compose.onNodeWithText("Stage 2 of 18 · from block 899998 · tip 900000").assertIsDisplayed()
        compose.onNodeWithText("Stage 2 of 18 completed").assertIsDisplayed()
        compose.onNodeWithContentDescription("2 of 18 stages filled").assertIsDisplayed()
        compose.onNodeWithText("Stage 0 · 1,001").assertIsDisplayed()
        compose.onNodeWithText("Stage 1 · 1,002").assertIsDisplayed()
        compose.onNodeWithText("40").assertIsDisplayed()
        compose.onNodeWithText("1.50 legs/s").assertIsDisplayed()
        compose.onNodeWithText("W_1 · posted").assertIsDisplayed()
        compose.onNodeWithText("W_2 · anchored").assertIsDisplayed()
        compose.onNodeWithText("Export log").assertIsDisplayed()
        compose.onNodeWithText("Export PCOC").assertDoesNotExist()
        compose.onNodeWithText("Stop").assertIsDisplayed()
        compose.onNodeWithText("About 2 h 40 min left, from headers still to come.").assertIsDisplayed()
    }

    @Test
    fun `a written object shows the digest`() {
        var exported: Pair<PcocExportKind, PcocExportAction>? = null
        show(
            CreateUiState(
                phase = ProducePhase.Written,
                stagesClosed = HIKE_STAGES,
                legsTotal = 100,
                objectBytes = 4096,
                objectSha256 = "ab",
                savedForAccount = true,
                outputPath = "/data/pcoc/hikes/hike.cbor",
                waypoints = listOf(
                    WaypointRow(index = 1, status = WaypointStatus.Anchored),
                    WaypointRow(index = 2, status = WaypointStatus.Skipped),
                ),
            ),
            onExport = { kind, action -> exported = kind to action },
        )
        compose.onNodeWithText("Object written").assertIsDisplayed()
        compose.onNodeWithText("SHA-256 ab", substring = true).assertIsDisplayed()
        compose.onNodeWithText("1 waypoint anchored, 1 skipped", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Saved for this account. Sign in can use it.").assertIsDisplayed()
        compose.onNodeWithText("Export PCOC").assertIsDisplayed()
        compose.onNodeWithText("Export log").assertIsDisplayed()
        compose.onAllNodesWithText("Save")[0].performClick()
        exported shouldBe (PcocExportKind.Object to PcocExportAction.Save)
        compose.onAllNodesWithText("Save")[1].performClick()
        exported shouldBe (PcocExportKind.Log to PcocExportAction.Save)
    }

    @Test
    fun `a stopped hike writes nothing`() {
        show(CreateUiState(phase = ProducePhase.Stopped))
        compose.onNodeWithText("The hike was stopped. No object was written.").assertIsDisplayed()
    }

    private fun show(
        state: CreateUiState,
        onExport: (PcocExportKind, PcocExportAction) -> Unit = { _, _ -> },
    ) {
        compose.setContent {
            StreamStateProvider {
                PrimalTheme(primalTheme = ThemeChoice.Ice) {
                    CreatePcocScreen(
                        state = state,
                        onClose = {},
                        eventPublisher = {},
                        onExport = onExport,
                    )
                }
            }
        }
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
}
