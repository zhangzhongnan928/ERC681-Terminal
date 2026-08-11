package com.openpasskey.terminal.ui.demo

import android.content.Intent
import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.openpasskey.terminal.LiveTerminalStackFactory
import com.openpasskey.terminal.MainActivity
import com.openpasskey.terminal.OPKTerminalApp
import com.openpasskey.terminal.ProcessLaunchMode
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.Test

class MainActivityDemoBoundaryTest {
    private val liveFactoryCalled = AtomicBoolean(false)

    private val factoryBoundary = object : ExternalResource() {
        override fun before() {
            assertEquals(ProcessLaunchMode.UNDECIDED, MainActivity.processLaunchMode)
            MainActivity.setLiveStackFactoryForTesting(
                LiveTerminalStackFactory { _, _ ->
                    liveFactoryCalled.set(true)
                    error("Live terminal factory must never run in reviewer demo mode")
                },
            )
        }

        override fun after() {
            MainActivity.setLiveStackFactoryForTesting(null)
        }
    }

    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(factoryBoundary).around(composeRule)

    @Test
    fun realLauncherKeepsDemoIsolatedAcrossRecreationAndAnotherActivity() {
        composeRule.onNodeWithTag("cold_launch_choice").assertIsDisplayed()
        assertFalse(liveFactoryCalled.get())
        composeRule.onNodeWithTag("cold_launch_reviewer_demo").performClick()
        composeRule.onNodeWithText(ReviewerDemoCopy.BANNER_LABEL).assertIsDisplayed()
        assertEquals(ProcessLaunchMode.DEMO, MainActivity.processLaunchMode)
        assertFalse(liveFactoryCalled.get())

        composeRule.onNodeWithTag("reviewer_demo_simulate_payment")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithText("Paid").assertIsDisplayed()

        val activityBeforeRecreation = composeRule.activity
        composeRule.activityRule.scenario.recreate()
        val demoOwner = composeRule.activity
        check(demoOwner !== activityBeforeRecreation)
        composeRule.onNodeWithText(ReviewerDemoCopy.BANNER_LABEL).assertIsDisplayed()
        composeRule.onNodeWithText("Waiting").assertIsDisplayed()
        assertEquals(ProcessLaunchMode.DEMO, MainActivity.processLaunchMode)
        assertFalse(liveFactoryCalled.get())

        demoOwner.startActivity(Intent(demoOwner, MainActivity::class.java))
        val secondActivity = waitForOtherResumedActivity(demoOwner)
        requestLiveTerminalDirectly(secondActivity)

        assertEquals(ProcessLaunchMode.DEMO, MainActivity.processLaunchMode)
        assertFalse(liveFactoryCalled.get())

        InstrumentationRegistry.getInstrumentation().runOnMainSync(secondActivity::finish)
        waitUntilResumed(demoOwner)
        composeRule.waitForIdle()
        composeRule.onNodeWithText(ReviewerDemoCopy.BANNER_LABEL).assertIsDisplayed()
    }

    @Suppress("DEPRECATION")
    @Test
    fun fragmentActivityAcceptsActivityResultRegistryPermissionRequestCodes() {
        composeRule.runOnUiThread {
            composeRule.activity.validateRequestPermissionsRequestCode(0x0001_0000)
        }
    }

    private fun requestLiveTerminalDirectly(activity: MainActivity) {
        val method = MainActivity::class.java.getDeclaredMethod(
            "activateLiveTerminal",
            OPKTerminalApp::class.java,
        ).apply {
            isAccessible = true
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            method.invoke(activity, activity.application as OPKTerminalApp)
        }
    }

    private fun waitForOtherResumedActivity(excluded: MainActivity): MainActivity =
        waitForActivity("A second MainActivity did not reach RESUMED") { activity ->
            activity !== excluded &&
                activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }

    private fun waitUntilResumed(expected: MainActivity) {
        waitForActivity("The demo MainActivity did not return to RESUMED") { activity ->
            activity === expected &&
                activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
    }

    private fun waitForActivity(
        failureMessage: String,
        predicate: (MainActivity) -> Boolean,
    ): MainActivity {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        repeat(50) {
            val match = AtomicReference<MainActivity?>()
            instrumentation.runOnMainSync {
                match.set(
                    ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<MainActivity>()
                        .firstOrNull(predicate),
                )
            }
            match.get()?.let { return it }
            SystemClock.sleep(100)
        }
        error(failureMessage)
    }
}
