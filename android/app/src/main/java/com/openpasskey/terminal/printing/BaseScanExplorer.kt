package com.openpasskey.terminal.printing

object BaseScanExplorer {
    private val transactionHash = Regex("^0x[0-9a-fA-F]{64}$")

    fun transactionUrl(chainId: Long, hash: String): String {
        require(transactionHash.matches(hash)) { "Payment transaction hash is not canonical" }
        val origin = when (chainId) {
            8453L -> "https://basescan.org"
            84532L -> "https://sepolia.basescan.org"
            else -> throw IllegalArgumentException("BaseScan is unavailable for chain $chainId")
        }
        return "$origin/tx/${hash.lowercase()}"
    }
}
