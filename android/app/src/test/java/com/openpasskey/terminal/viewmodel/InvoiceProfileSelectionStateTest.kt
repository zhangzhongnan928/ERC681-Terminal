package com.openpasskey.terminal.viewmodel

import com.openpasskey.terminal.chain.PaymentToken
import com.openpasskey.terminal.chain.TerminalConfigSnapshot
import com.openpasskey.terminal.chain.TerminalPaymentProfile
import org.junit.Assert.assertEquals
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

    private fun profile(symbol: String, tokenAddress: String) = TerminalPaymentProfile(
        networkName = "Base Sepolia",
        rpcUrl = "https://sepolia.base.org",
        chainId = 84_532,
        factoryAddress = "0x062e3b5d3107e4d1b8dda314e16b9f8ca6eb63d5",
        receiverImplementationAddress = "0xdaa292b1bf533737c5ce5d27f220273971db3bdc",
        vaultAddress = "0x3333333333333333333333333333333333333333",
        confirmationBlocks = 2,
        token = PaymentToken(tokenAddress, symbol, 18),
        protocolVersion = "1.4.1",
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
