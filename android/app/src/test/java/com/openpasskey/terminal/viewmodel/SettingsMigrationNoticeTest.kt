package com.openpasskey.terminal.viewmodel

import com.openpasskey.terminal.chain.ChainConfigMigrationNotice
import com.openpasskey.terminal.provisioning.KnownChainPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsMigrationNoticeTest {
    @Test
    fun awaitingGasMessageUsesReadableNetworkNativeAmount() {
        val message = awaitingGasReadinessMessage(KnownChainPolicy.requireProfile(84_532))

        assertTrue(message.contains("0.0001 ETH"))
        assertFalse(message.contains("wei"))
    }

    @Test
    fun finalityMigrationNoticeExplainsAutomaticChangeAndPreservedSetup() {
        val message = chainConfigMigrationNoticeMessage(
            ChainConfigMigrationNotice(setOf("profile-a", "profile-b")),
        )

        assertTrue(message.contains("2 existing payment profiles"))
        assertTrue(message.contains("increased to the applicable network policy"))
        assertTrue(message.contains("Terminal setup was preserved"))
        assertTrue(message.contains("review readiness"))
    }
}
