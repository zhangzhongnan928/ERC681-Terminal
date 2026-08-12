package com.openpasskey.terminal.wallet

import org.junit.Assert.assertThrows
import org.junit.Test

class UnattendedAutoSweepGrantPolicyTest {
    @Test
    fun exactScopeIsRequiredAndMissingOrStaleGrantFailsClosed() {
        val current = scope(CHAIN_ID, VAULT)
        requireExactUnattendedAutoSweepGrant(
            UnattendedAutoSweepGrantSnapshot(true, setOf(current)),
            current,
        )

        listOf(
            UnattendedAutoSweepGrantSnapshot(false),
            UnattendedAutoSweepGrantSnapshot(true, setOf(scope(CHAIN_ID, OTHER_VAULT))),
            UnattendedAutoSweepGrantSnapshot(true, setOf(current, scope(CHAIN_ID, OTHER_VAULT))),
            UnattendedAutoSweepGrantSnapshot(true, setOf(scope(84_532, VAULT))),
        ).forEach { staleGrant ->
            assertThrows(IllegalStateException::class.java) {
                requireExactUnattendedAutoSweepGrant(staleGrant, current)
            }
        }
    }

    private fun scope(chainId: Long, vault: String) = UnattendedAutoSweepScope(
        chainId = chainId,
        vaultAddress = vault,
        operatorAddress = OPERATOR,
    ).canonical()

    private companion object {
        const val CHAIN_ID = 8_453L
        const val VAULT = "0x1111111111111111111111111111111111111111"
        const val OTHER_VAULT = "0x2222222222222222222222222222222222222222"
        const val OPERATOR = "0x3333333333333333333333333333333333333333"
    }
}
