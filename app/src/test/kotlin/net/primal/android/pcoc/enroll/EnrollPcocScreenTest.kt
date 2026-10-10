package net.primal.android.pcoc.enroll

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.shouldBe
import net.primal.android.pcoc.PcocExportAction
import net.primal.android.pcoc.enroll.EnrollPcocContract.UiState
import net.primal.android.stream.player.StreamStateProvider
import net.primal.android.theme.PrimalTheme
import net.primal.android.theme.domain.PrimalTheme as ThemeChoice
import androidx.test.ext.junit.runners.AndroidJUnit4
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
class EnrollPcocScreenTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(HostActivityRule()).around(compose)

    @Test
    fun `a saved object can be exported`() {
        var action: PcocExportAction? = null
        show(UiState(stored = true, fileName = "hike.cbor")) { action = it }
        compose.onNodeWithText("Export PCOC").assertIsDisplayed()
        compose.onNodeWithText("Export log").assertDoesNotExist()
        compose.onNodeWithText("Save").performClick()
        action shouldBe PcocExportAction.Save
        compose.onNodeWithText("Share").performClick()
        action shouldBe PcocExportAction.Share
    }

    @Test
    fun `an empty account has no export`() {
        show(UiState(stored = false))
        compose.onNodeWithText("Export PCOC").assertDoesNotExist()
    }

    private fun show(state: UiState, onExport: (PcocExportAction) -> Unit = {}) {
        compose.setContent {
            StreamStateProvider {
                PrimalTheme(primalTheme = ThemeChoice.Ice) {
                    EnrollPcocScreen(state = state, onClose = {}, eventPublisher = {}, onExport = onExport)
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
