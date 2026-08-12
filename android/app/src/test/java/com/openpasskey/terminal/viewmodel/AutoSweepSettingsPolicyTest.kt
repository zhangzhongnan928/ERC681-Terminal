package com.openpasskey.terminal.viewmodel

import com.openpasskey.terminal.lifecycle.TerminalLifecycleGate
import com.openpasskey.terminal.chain.PaymentToken
import com.openpasskey.terminal.chain.TerminalConfigSnapshot
import com.openpasskey.terminal.chain.TerminalPaymentProfile
import com.openpasskey.terminal.wallet.OperatorWalletAvailability
import com.openpasskey.terminal.wallet.OperatorWalletSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSweepSettingsPolicyTest {
    @Test
    fun newSettingsStateDefaultsAutoSweepOff() {
        assertFalse(SettingsState().autoSweepEnabled)
        assertFalse(SettingsState().savingAutoSweepPreference)
    }

    @Test
    fun upgradedEnabledPreferenceWithoutExactGrantFailsClosed() {
        assertTrue(autoSweepRequiresSecurityUpgradeEnrollment(storedEnabled = true, exactGrantReady = false))
        assertFalse(autoSweepRequiresSecurityUpgradeEnrollment(storedEnabled = true, exactGrantReady = true))
        assertFalse(autoSweepRequiresSecurityUpgradeEnrollment(storedEnabled = false, exactGrantReady = false))
    }

    @Test
    fun configurationCommitNeverRunsBeforeDurableAutoSweepRevocation() {
        val events = mutableListOf<String>()
        val committed = commitAfterAutoSweepRevocation(
            disableAndRevoke = { events += "revoke"; true },
            commitConfiguration = { events += "config"; true },
        )
        assertTrue(committed)
        assertEquals(listOf("revoke", "config"), events)

        val rejectedEvents = mutableListOf<String>()
        val failure = runCatching {
            commitAfterAutoSweepRevocation(
                disableAndRevoke = { rejectedEvents += "revoke"; false },
                commitConfiguration = { rejectedEvents += "config"; true },
            )
        }
        assertTrue(failure.isFailure)
        assertEquals(listOf("revoke"), rejectedEvents)
    }

    @Test
    fun enrollmentAcceptsMultipleTokensButRejectsMultipleChainVaultGroups() {
        val first = profile(chainId = 84_532, vault = VAULT, token = TOKEN)
        val secondToken = profile(
            chainId = 84_532,
            vault = VAULT,
            token = "0x4444444444444444444444444444444444444444",
        )
        val config = config(listOf(first, secondToken))
        val wallet = OperatorWalletSnapshot(OperatorWalletAvailability.READY, OPERATOR)
        assertEquals(1, requiredUnattendedAutoSweepScopes(config, wallet).size)

        val otherVault = profile(
            chainId = 84_532,
            vault = "0x5555555555555555555555555555555555555555",
            token = TOKEN,
        )
        val rejected = runCatching {
            requiredUnattendedAutoSweepScopes(config(listOf(first, otherVault)), wallet)
        }
        assertTrue(rejected.isFailure)
        assertTrue(rejected.exceptionOrNull()?.message.orEmpty().contains("one chain and vault"))
    }

    @Test
    fun preferenceWriteRequiresTheCapturedAdminAuthorization() = runBlocking {
        val session = AdminSessionGate()
        val unlock = session.beginUnlock()
        assertTrue(session.completeUnlock(unlock))
        val authorization = requireNotNull(session.unlockedEpochOrNull())
        var writes = 0

        updateAutoSweepPreferenceExclusively(
            lifecycleGate = TerminalLifecycleGate(),
            commitWithAuthorization = { commit ->
                session.withAuthorization(authorization, commit)
            },
            update = {
                writes += 1
                true
            },
        )
        assertEquals(1, writes)

        session.lock()
        val rejected = runCatching {
            updateAutoSweepPreferenceExclusively(
                lifecycleGate = TerminalLifecycleGate(),
                commitWithAuthorization = { commit ->
                    session.withAuthorization(authorization, commit)
                },
                update = {
                    writes += 1
                    true
                },
            )
        }
        assertTrue(rejected.isFailure)
        assertEquals(1, writes)
    }

    private fun profile(chainId: Long, vault: String, token: String) = TerminalPaymentProfile(
        networkName = "Base Sepolia",
        rpcUrl = "https://sepolia.base.org",
        chainId = chainId,
        factoryAddress = FACTORY,
        receiverImplementationAddress = IMPLEMENTATION,
        vaultAddress = vault,
        confirmationBlocks = 2,
        token = PaymentToken(token, "AUDM", 6),
        protocolVersion = "1.6",
    )

    private fun config(profiles: List<TerminalPaymentProfile>): TerminalConfigSnapshot {
        val selected = profiles.first()
        return TerminalConfigSnapshot(
            networkName = selected.networkName,
            rpcUrl = selected.rpcUrl,
            chainId = selected.chainId,
            factoryAddress = selected.factoryAddress,
            receiverImplementationAddress = selected.receiverImplementationAddress,
            vaultAddress = selected.vaultAddress,
            confirmationBlocks = selected.confirmationBlocks,
            paymentTokens = listOf(selected.token),
            protocolVersion = selected.protocolVersion,
            provisionedOperatorAddress = OPERATOR,
            provisioned = true,
            paymentProfiles = profiles,
            selectedProfileId = selected.id,
        )
    }

    private companion object {
        const val OPERATOR = "0x1111111111111111111111111111111111111111"
        const val FACTORY = "0x2592fbab9707e65e21ea14d8a9fe298f5e68a37f"
        const val IMPLEMENTATION = "0xf2e0d5fc47761cac0eedee6cb1af5f31843a0a18"
        const val VAULT = "0x6666666666666666666666666666666666666666"
        const val TOKEN = "0x7777777777777777777777777777777777777777"
    }
}
