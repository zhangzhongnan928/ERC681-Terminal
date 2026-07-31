package com.openpasskey.terminal.viewmodel

import com.openpasskey.terminal.chain.PaymentToken
import com.openpasskey.terminal.chain.TerminalConfigSnapshot
import com.openpasskey.terminal.chain.TerminalPaymentProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InvoiceProfileSelectionStateTest {
    @Test
    fun successfulCurrencySwitchAlwaysClearsTypedAmountEvenWhenDecimalsMatch() {
        val audm = profile("AUDM", "0x1111111111111111111111111111111111111111")
        val usdc = profile("USDC", "0x2222222222222222222222222222222222222222")
        val before = CreateInvoiceState(
            amount = "10.50",
            profiles = listOf(audm, usdc),
            selectedProfile = audm,
            error = "stale error",
            repositoryFailure = "stale error",
            profileSelectionSequence = 4,
        )

        val after = before.afterProfileSelection(snapshot(usdc, listOf(audm, usdc)))

        assertEquals("", after.amount)
        assertEquals(usdc.id, after.selectedProfile?.id)
        assertEquals(listOf(audm, usdc), after.profiles)
        assertTrue(after.profileSelectionPending)
        assertEquals(5, after.profileSelectionSequence)
        assertNull(after.error)
        assertNull(after.repositoryFailure)
    }

    @Test
    fun genericAndStaleReadinessCallbacksCannotReleasePendingProfileSelection() {
        val audm = profile("AUDM", "0x1111111111111111111111111111111111111111")
        val usdc = profile("USDC", "0x2222222222222222222222222222222222222222")
        val pending = CreateInvoiceState(
            profiles = listOf(audm, usdc),
            selectedProfile = usdc,
            profileSelectionSequence = 5,
            profileSelectionPending = true,
        )

        assertTrue(pending.afterReadinessRefresh(ready = true).profileSelectionPending)
        assertEquals(
            pending,
            pending.afterProfileSelectionReadinessRefresh(
                sequence = 4,
                profileId = usdc.id,
                ready = true,
            ),
        )
        assertEquals(
            pending,
            pending.afterProfileSelectionReadinessRefresh(
                sequence = 5,
                profileId = audm.id,
                ready = true,
            ),
        )
    }

    @Test
    fun matchingSelectionReadinessCallbackReleasesPendingStateEvenWhenRouteIsNotReady() {
        val selected = profile("USDC", "0x2222222222222222222222222222222222222222")
        val pending = CreateInvoiceState(
            profiles = listOf(selected),
            selectedProfile = selected,
            profileSelectionSequence = 8,
            profileSelectionPending = true,
        )

        val completed = pending.afterProfileSelectionReadinessRefresh(
            sequence = 8,
            profileId = selected.id,
            ready = false,
        )

        assertFalse(completed.profileSelectionPending)
        assertEquals(8, completed.profileSelectionSequence)
        assertEquals(selected.id, completed.selectedProfile?.id)
    }

    @Test
    fun authoritativeProfileRemovalReleasesPendingWithoutAcceptingItsStaleCallback() {
        val audm = profile("AUDM", "0x1111111111111111111111111111111111111111")
        val usdc = profile("USDC", "0x2222222222222222222222222222222222222222")
        val pending = CreateInvoiceState(
            amount = "19.95",
            profiles = listOf(audm, usdc),
            selectedProfile = usdc,
            profileSelectionSequence = 11,
            profileSelectionPending = true,
        )

        val afterRemoval = pending.afterConfigurationRefresh(
            configuration = snapshot(audm, listOf(audm)),
            operatorWalletReady = true,
        )

        assertFalse(afterRemoval.profileSelectionPending)
        assertEquals(audm.id, afterRemoval.selectedProfile?.id)
        assertEquals("", afterRemoval.amount)
        assertEquals(
            afterRemoval,
            afterRemoval.afterProfileSelectionReadinessRefresh(
                sequence = 11,
                profileId = usdc.id,
                ready = true,
            ),
        )
    }

    @Test
    fun sameSelectedConfigurationInvalidationTerminatesOwnedCallbackWithoutLaterRpc() {
        val selected = profile("USDC", "0x2222222222222222222222222222222222222222")
        val pending = CreateInvoiceState(
            profiles = listOf(selected),
            selectedProfile = selected,
            profileSelectionSequence = 12,
            profileSelectionPending = true,
        )

        var refreshed = pending.afterConfigurationRefresh(
            configuration = snapshot(selected, listOf(selected)),
            operatorWalletReady = true,
        )
        val callbacks = ReadinessRefreshCallbacks()
        callbacks.add { ready -> refreshed = refreshed.afterReadinessRefresh(ready) }
        callbacks.add { ready ->
            refreshed = refreshed.afterProfileSelectionReadinessRefresh(
                sequence = 12,
                profileId = selected.id,
                ready = ready,
            )
        }

        assertTrue(refreshed.profileSelectionPending)
        callbacks.cancel()
        assertFalse(refreshed.profileSelectionPending)

        val cancelled = refreshed
        callbacks.complete(ready = true)
        assertEquals(
            "Cancelled generation cannot complete again later",
            cancelled,
            refreshed,
        )
    }

    private fun profile(symbol: String, tokenAddress: String) = TerminalPaymentProfile(
        networkName = "Base Sepolia",
        rpcUrl = "https://sepolia.base.org",
        chainId = 84_532,
        factoryAddress = "0x2592fbab9707e65e21ea14d8a9fe298f5e68a37f",
        receiverImplementationAddress = "0xf2e0d5fc47761cac0eedee6cb1af5f31843a0a18",
        vaultAddress = "0x3333333333333333333333333333333333333333",
        confirmationBlocks = 2,
        token = PaymentToken(tokenAddress, symbol, 18),
        protocolVersion = "1.6",
    )

    private fun snapshot(
        selected: TerminalPaymentProfile,
        profiles: List<TerminalPaymentProfile>,
    ) = TerminalConfigSnapshot(
        networkName = selected.networkName,
        rpcUrl = selected.rpcUrl,
        chainId = selected.chainId,
        factoryAddress = selected.factoryAddress,
        receiverImplementationAddress = selected.receiverImplementationAddress,
        vaultAddress = selected.vaultAddress,
        confirmationBlocks = selected.confirmationBlocks,
        paymentTokens = listOf(selected.token),
        protocolVersion = selected.protocolVersion,
        provisionedOperatorAddress = "0x4444444444444444444444444444444444444444",
        provisioned = true,
        paymentProfiles = profiles,
        selectedProfileId = selected.id,
    )
}
