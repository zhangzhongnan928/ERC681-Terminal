package com.openpasskey.terminal.ui.demo

import android.net.TrafficStats
import android.os.Process
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ColdLaunchDemoIsolationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun demoColdLaunchDoesNotCrossLiveFactoryOrCreateExternalState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databasesBefore = directoryEntries(context.applicationInfo.dataDir, "databases")
        val preferencesBefore = directoryEntries(context.applicationInfo.dataDir, "shared_prefs")
        val filesBefore = directoryEntries(context.applicationInfo.dataDir, "files")
        val keystoreBefore = androidKeystoreAliases()
        val rxBefore = TrafficStats.getUidRxBytes(Process.myUid())
        val txBefore = TrafficStats.getUidTxBytes(Process.myUid())
        val demoModeSelected = AtomicBoolean(false)
        val liveFactoryTripwire = AtomicBoolean(false)

        composeRule.setContent {
            ColdLaunchRoot(
                onEnterDemo = {
                    demoModeSelected.set(true)
                    true
                },
                onExitDemo = {},
                onOpenTerminal = {
                    liveFactoryTripwire.set(true)
                    error("Live terminal factory must never run in reviewer demo mode")
                },
            )
        }

        composeRule.onNodeWithTag("cold_launch_choice").assertIsDisplayed()
        composeRule.onNodeWithText("Explore OPK Terminal").assertIsDisplayed()
        composeRule.onNodeWithText("Explore offline product tour", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithTag("cold_launch_reviewer_demo").performClick()
        composeRule.onNodeWithText("Offline product tour").assertIsDisplayed()
        composeRule.onNodeWithText(ReviewerDemoCopy.BANNER_LABEL).assertIsDisplayed()
        composeRule.onNodeWithTag("reviewer_demo_simulate_payment").performClick()
        composeRule.onNodeWithText("Paid").assertIsDisplayed()

        assertTrue(demoModeSelected.get())
        assertFalse(liveFactoryTripwire.get())
        assertEquals(
            databasesBefore,
            directoryEntries(context.applicationInfo.dataDir, "databases"),
        )
        assertEquals(
            preferencesBefore,
            directoryEntries(context.applicationInfo.dataDir, "shared_prefs"),
        )
        assertEquals(filesBefore, directoryEntries(context.applicationInfo.dataDir, "files"))
        assertEquals(keystoreBefore, androidKeystoreAliases())
        assertTrafficUnchanged(rxBefore, TrafficStats.getUidRxBytes(Process.myUid()))
        assertTrafficUnchanged(txBefore, TrafficStats.getUidTxBytes(Process.myUid()))
    }

    private fun directoryEntries(dataDir: String, child: String): Set<String> =
        File(dataDir, child).list()?.toSet().orEmpty()

    private fun androidKeystoreAliases(): Set<String> {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return keyStore.aliases().toList().toSet()
    }

    private fun assertTrafficUnchanged(before: Long, after: Long) {
        if (before != TrafficStats.UNSUPPORTED.toLong() &&
            after != TrafficStats.UNSUPPORTED.toLong()
        ) {
            assertEquals(before, after)
        }
    }
}
