package com.openpasskey.terminal.wallet

import com.openpasskey.erc681.EvmAddress
import org.junit.Assert.assertThrows
import org.junit.Test

class OperatorWalletBindingPolicyTest {
    @Test
    fun signerActivationRequiresExactCurrentOperatorBinding() {
        requireVerifiedSettlementActivation(wallet(), CHAIN_ID, EvmAddress.parse(VAULT))

        listOf(
            wallet().copy(activatedOperatorAddress = null),
            wallet().copy(activatedOperatorAddress = OTHER_OPERATOR),
            wallet().copy(address = OTHER_OPERATOR),
        ).forEach { staleOrMismatchedWallet ->
            assertThrows(IllegalArgumentException::class.java) {
                requireVerifiedSettlementActivation(
                    staleOrMismatchedWallet,
                    CHAIN_ID,
                    EvmAddress.parse(VAULT),
                )
            }
        }
    }

    private fun wallet() = OperatorWalletSnapshot(
        availability = OperatorWalletAvailability.READY,
        address = OPERATOR,
        activatedChainId = CHAIN_ID,
        activatedVaultAddress = VAULT,
        activatedOperatorAddress = OPERATOR,
    )

    private companion object {
        const val CHAIN_ID = 84532L
        const val OPERATOR = "0x1111111111111111111111111111111111111111"
        const val OTHER_OPERATOR = "0x2222222222222222222222222222222222222222"
        const val VAULT = "0x1ed67e540e6ab92dc3537a7bba3bcab6fdd69da1"
    }
}
