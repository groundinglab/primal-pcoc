package net.primal.android.pcoc

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
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
class PcocMenuScreenTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(HostActivityRule()).around(compose)

    @Test
    fun `the menu shows an orange check beside the active account while a pcoc is saved`() {
        show(name = "Alex", showMark = true)
        compose.onNodeWithText("Alex").assertIsDisplayed()
        compose.onNodeWithContentDescription("PCOC saved").assertIsDisplayed()
    }

    @Test
    fun `the menu hides the check when the account has no saved pcoc`() {
        show(name = "Alex", showMark = false)
        compose.onNodeWithText("Alex").assertIsDisplayed()
        compose.onNodeWithContentDescription("PCOC saved").assertDoesNotExist()
    }

    private fun show(name: String, showMark: Boolean) {
        compose.setContent {
            StreamStateProvider {
                PrimalTheme(primalTheme = ThemeChoice.Ice) {
                    PcocScreen(onClose = {}, onDoor = {}, accountName = name, showPcocMark = showMark)
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
