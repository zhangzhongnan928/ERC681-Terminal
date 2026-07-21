package com.openpasskey.terminal.provisioning

import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigInteger

class NativeCurrencyFormatterTest {
    @Test
    fun baseSepoliaReserveUsesReadableEthInsteadOfRawWei() {
        val policy = KnownChainPolicy.requireProfile(84_532)

        assertEquals("0.0001 ETH", policy.minimumOperatorNativeReserveDisplay())
    }

    @Test
    fun formatterUsesNetworkDecimalsAndSymbolWithoutFloatingPointRounding() {
        assertEquals(
            "1.234567 USDC",
            formatNativeCurrencyAmount(BigInteger("1234567"), decimals = 6, symbol = "USDC"),
        )
        assertEquals("0 ETH", formatNativeCurrencyAmount("0", decimals = 18, symbol = "ETH"))
        assertEquals("Unknown", formatNativeCurrencyAmount("not-a-number", 18, "ETH"))
    }
}
