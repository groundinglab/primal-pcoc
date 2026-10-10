package net.primal.android.pcoc.create

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
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
        compose.onNodeWithText("https://bari.pcoc.app", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Coming soon").assertDoesNotExist()
    }

    @Test
    fun `a running hike shows the stage and stop`() {
        show(
            CreateUiState(
                phase = ProducePhase.Running,
                stage = 2,
                stagesClosed = 2,
                legsTotal = 40,
                tipHeight = 900_000,
            ),
        )
        compose.onNodeWithText("Hike running").assertIsDisplayed()
        compose.onNodeWithText("Stage 2 · 2 of 18 closed").assertIsDisplayed()
        compose.onNodeWithText("40").assertIsDisplayed()
        compose.onNodeWithText("Stop").assertIsDisplayed()
        compose.onNodeWithText("About 2 h 40 min left, from headers still to come.").assertIsDisplayed()
    }

    @Test
    fun `a written object shows the digest`() {
        show(
            CreateUiState(
                phase = ProducePhase.Written,
                legsTotal = 100,
                objectBytes = 4096,
                objectSha256 = "ab",
                savedForAccount = true,
            ),
        )
        compose.onNodeWithText("Object written").assertIsDisplayed()
        compose.onNodeWithText("ab").assertIsDisplayed()
        compose.onNodeWithText("Saved for this account. Sign in can use it.").assertIsDisplayed()
    }

    @Test
    fun `a stopped hike writes nothing`() {
        show(CreateUiState(phase = ProducePhase.Stopped))
        compose.onNodeWithText("The hike was stopped. No object was written.").assertIsDisplayed()
    }

    private fun show(state: CreateUiState) {
        compose.setContent {
            StreamStateProvider {
                PrimalTheme(primalTheme = ThemeChoice.Ice) {
                    CreatePcocScreen(state = state, onClose = {}, eventPublisher = {})
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
