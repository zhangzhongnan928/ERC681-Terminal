package com.openpasskey.terminal.viewmodel

import com.openpasskey.terminal.chain.PaymentToken
import com.openpasskey.terminal.chain.TerminalConfigSnapshot
import com.openpasskey.terminal.wallet.OperatorWalletAvailability
import com.openpasskey.terminal.wallet.OperatorWalletSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsOperatorBindingPolicyTest {
    @Test
    fun fundingQrRequiresReadyWalletBoundToProvisioningQrOperator() {
        assertEquals(
            "ethereum:$OPERATOR@84532",
            operatorFundingPayload(config(), wallet()),
        )
        assertNull(operatorFundingPayload(config().copy(provisionedOperatorAddress = null), wallet()))
        assertNull(operatorFundingPayload(config().copy(provisionedOperatorAddress = OTHER), wallet()))
        assertNull(operatorFundingPayload(config().copy(provisioned = false), wallet()))
        assertNull(
            operatorFundingPayload(
                config(),
                OperatorWalletSnapshot(OperatorWalletAvailability.NOT_CREATED),
            ),
        )
    }

    private fun wallet() = OperatorWalletSnapshot(
        availability = OperatorWalletAvailability.READY,
        address = OPERATOR,
    )

    private fun config() = TerminalConfigSnapshot(
        networkName = "Base Sepolia",
        rpcUrl = "https://sepolia.base.org",
        chainId = 84532,
        factoryAddress = "0x062e3b5d3107e4d1b8dda314e16b9f8ca6eb63d5",
        receiverImplementationAddress = "0xdaa292b1bf533737c5ce5d27f220273971db3bdc",
        vaultAddress = "0x1ed67e540e6ab92dc3537a7bba3bcab6fdd69da1",
        confirmationBlocks = 2,
        paymentTokens = listOf(
            PaymentToken("0x7ffba642bc902880a737cb1c18a4e9540879e211", "AUD", 18),
        ),
        protocolVersion = "1.4.1",
        provisionedOperatorAddress = OPERATOR,
        provisioned = true,
    )

    private companion object {
        const val OPERATOR = "0x1111111111111111111111111111111111111111"
        const val OTHER = "0x2222222222222222222222222222222222222222"
    }
}
