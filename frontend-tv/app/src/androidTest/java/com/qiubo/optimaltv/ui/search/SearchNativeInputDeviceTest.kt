package com.qiubo.optimaltv.ui.search

import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.compose.rememberNavController
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.hasFocus
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withClassName
import androidx.test.espresso.matcher.ViewMatchers.withHint
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.qiubo.optimaltv.Graph
import com.qiubo.optimaltv.focus.FocusHarnessActivity
import com.qiubo.optimaltv.focus.Harness
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.equalTo
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchNativeInputDeviceTest {
    @get:Rule
    val rule = createAndroidComposeRule<FocusHarnessActivity>()

    @Test
    fun searchUsesFrameworkEditTextAndTheInstalledIme() {
        Graph.init(rule.activity.applicationContext)
        Harness.nativeFocus = true
        rule.runOnUiThread {
            Harness.scene = {
                SearchScreen(rememberNavController())
            }
        }
        rule.waitForIdle()

        val searchEditor =
            allOf(
                withClassName(equalTo(EditText::class.java.name)),
                withHint("搜索影片、演员"),
            )

        onView(searchEditor)
            .check(matches(isDisplayed()))
            .check(matches(hasFocus()))
            .perform(click())
            .perform(replaceText("test"))
            .check(matches(withClassName(equalTo(EditText::class.java.name))))

        rule.runOnIdle {
            val inputMethodManager =
                rule.activity.getSystemService(InputMethodManager::class.java)
            assertTrue(inputMethodManager?.isAcceptingText == true)
        }
    }
}
