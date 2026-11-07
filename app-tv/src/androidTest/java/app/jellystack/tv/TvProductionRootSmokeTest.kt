package app.jellystack.tv

import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TvProductionRootSmokeTest {
    val composeRule = createAndroidComposeRule<MainActivity>()

    /** TV remotes do not use touch mode; enter key input mode before the activity launches. */
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(DpadInputModeRule()).around(composeRule)

    @Test
    fun coldLaunchAndRecreationKeepProductionRootFocusable() {
        composeRule.onNodeWithTag("video-pro-login-account").assertExists().assertIsFocused()

        composeRule.activityRule.scenario.recreate()

        composeRule.onNodeWithTag("video-pro-login-account").assertExists().assertIsFocused()
    }
}

private class DpadInputModeRule : ExternalResource() {
    @Suppress("DEPRECATION")
    override fun before() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
    }
}
