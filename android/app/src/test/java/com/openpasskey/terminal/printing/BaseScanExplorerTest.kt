package com.openpasskey.terminal.printing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BaseScanExplorerTest {
    private val hash = "0x" + "aB".repeat(32)

    @Test
    fun `maps Base mainnet and Sepolia transaction URLs`() {
        assertEquals(
            "https://basescan.org/tx/${hash.lowercase()}",
            BaseScanExplorer.transactionUrl(8453, hash),
        )
        assertEquals(
            "https://sepolia.basescan.org/tx/${hash.lowercase()}",
            BaseScanExplorer.transactionUrl(84532, hash),
        )
    }

    @Test
    fun `rejects malformed hashes and non Base chains`() {
        assertThrows(IllegalArgumentException::class.java) {
            BaseScanExplorer.transactionUrl(84532, "0x1234")
        }
        assertThrows(IllegalArgumentException::class.java) {
            BaseScanExplorer.transactionUrl(1, hash)
        }
    }
}
