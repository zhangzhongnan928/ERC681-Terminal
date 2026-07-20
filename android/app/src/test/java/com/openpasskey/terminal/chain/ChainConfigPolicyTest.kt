package com.openpasskey.terminal.chain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChainConfigPolicyTest {
    @Test
    fun missingLegacyOperatorBindingIsNeverCompleteProvisioning() {
        assertTrue(config().hasCompleteProvisioning())
        assertFalse(config().copy(provisionedOperatorAddress = null).hasCompleteProvisioning())
        assertFalse(
            config().copy(
                provisionedOperatorAddress = "0x0000000000000000000000000000000000000000",
            ).hasCompleteProvisioning(),
        )
    }

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
        provisionedOperatorAddress = "0x1111111111111111111111111111111111111111",
        provisioned = true,
    )
}
