package com.openpasskey.terminal.data.repository

import com.openpasskey.terminal.chain.PaymentToken
import com.openpasskey.terminal.chain.TerminalConfigSnapshot
import com.openpasskey.terminal.wallet.OperatorWalletAvailability
import com.openpasskey.terminal.wallet.OperatorWalletSnapshot
import com.openpasskey.terminal.provisioning.KnownChainPolicy
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigInteger

class InvoiceReadinessPolicyTest {
    @Test
    fun readinessFailsClosedForEveryMissingRequirement() {
        assertThrows(IllegalStateException::class.java) {
            requireTerminalReadiness(config().copy(provisioned = false), wallet(), ready())
        }
        assertThrows(IllegalStateException::class.java) {
            requireTerminalReadiness(
                config(),
                OperatorWalletSnapshot(OperatorWalletAvailability.NOT_CREATED),
                ready(),
            )
        }
        assertThrows(IllegalStateException::class.java) {
            requireTerminalReadiness(config(), wallet(), ready().copy(authorized = false))
        }
        val lowGas = assertThrows(IllegalStateException::class.java) {
            requireTerminalReadiness(
                config(),
                wallet(),
                ready().copy(
                    nativeBalance = KnownChainPolicy.requireProfile(84532)
                        .minimumOperatorNativeReserve - BigInteger.ONE,
                ),
            )
        }
        org.junit.Assert.assertTrue(lowGas.message?.contains("0.0001 ETH") == true)
        org.junit.Assert.assertFalse(lowGas.message?.contains("wei") == true)
    }

    @Test
    fun exactMinimumAuthorizedProvisionedWalletIsReady() {
        requireTerminalReadiness(config(), wallet(), ready())
    }

    @Test
    fun concurrentConfigurationOrOperatorChangeFailsClosed() {
        requireInvoiceStateUnchanged(config(), config(), wallet(), wallet())
        assertThrows(IllegalStateException::class.java) {
            requireInvoiceStateUnchanged(
                config(),
                config().copy(vaultAddress = "0x2222222222222222222222222222222222222222"),
                wallet(),
                wallet(),
            )
        }
        assertThrows(IllegalStateException::class.java) {
            requireInvoiceStateUnchanged(
                config(),
                config(),
                wallet(),
                OperatorWalletSnapshot(
                    OperatorWalletAvailability.READY,
                    "0x2222222222222222222222222222222222222222",
                ),
            )
        }
    }

    private fun wallet() = OperatorWalletSnapshot(
        OperatorWalletAvailability.READY,
        "0x1111111111111111111111111111111111111111",
    )

    private fun ready() = InvoiceReadiness(
        authorized = true,
        nativeBalance = KnownChainPolicy.requireProfile(84532).minimumOperatorNativeReserve,
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
        provisionedOperatorAddress = "0x1111111111111111111111111111111111111111",
        provisioned = true,
    )
}
