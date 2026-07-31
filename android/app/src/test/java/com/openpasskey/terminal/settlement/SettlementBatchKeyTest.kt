package com.openpasskey.terminal.settlement

import com.openpasskey.terminal.data.model.Invoice
import com.openpasskey.terminal.data.model.InvoiceStatus
import com.openpasskey.terminal.provisioning.KnownChainPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SettlementBatchKeyTest {
    @Test
    fun groupingUsesTheCompleteRouteAndConfirmationPolicyRatherThanTheSymbol() {
        val original = invoice()
        val sameSnapshot = original.copy(
            invoiceId = hex('2'),
            receiver = address('8'),
        )
        val sameSymbolDifferentVault = original.copy(vaultAddress = address('4'))
        val sameSymbolDifferentToken = original.copy(token = address('5'))
        val differentChain = original.copy(chainId = 11155111, networkName = "Sepolia")
        val differentConfirmationPolicy = original.copy(confirmationBlocks = 12)

        assertEquals(original.settlementBatchKey(), sameSnapshot.settlementBatchKey())
        assertEquals("1.6", original.settlementBatchKey().protocolVersion)
        assertNotEquals(original.settlementBatchKey(), sameSymbolDifferentVault.settlementBatchKey())
        assertNotEquals(original.settlementBatchKey(), sameSymbolDifferentToken.settlementBatchKey())
        assertNotEquals(original.settlementBatchKey(), differentChain.settlementBatchKey())
        assertNotEquals(
            original.settlementBatchKey(),
            differentConfirmationPolicy.settlementBatchKey(),
        )
    }

    @Test
    fun repositoryBatchGuardRejectsEveryGroupingMismatch() {
        val original = invoice()
        val mismatches = listOf(
            original.copy(vaultAddress = address('4')),
            original.copy(token = address('5')),
            original.copy(chainId = 11155111, networkName = "Sepolia"),
            original.copy(confirmationBlocks = 12),
        )

        mismatches.forEach { mismatch ->
            assertThrows(IllegalArgumentException::class.java) {
                requireSameSettlementBatchSnapshot(listOf(original, mismatch))
            }
        }
    }

    private fun invoice(): Invoice {
        val known = KnownChainPolicy.requireProfile(84_532)
        return Invoice(
            invoiceId = hex('1'),
            receiver = address('7'),
            operatorAddress = address('9'),
            token = address('3'),
            tokenSymbol = "USD",
            tokenDecimals = 6,
            expectedAmount = "10500000",
            status = InvoiceStatus.PAID,
            createdAt = 1,
            chainId = 84532,
            networkName = "Base Sepolia",
            rpcUrl = "https://sepolia.base.org",
            factoryAddress = known.factory.value,
            receiverImplementationAddress = known.receiverImplementation.value,
            vaultAddress = address('2'),
            confirmationBlocks = 2,
            erc681Uri = "ethereum:${address('3')}@84532/transfer",
        )
    }

    private fun address(character: Char) = "0x" + character.toString().repeat(40)
    private fun hex(character: Char) = "0x" + character.toString().repeat(64)
}
