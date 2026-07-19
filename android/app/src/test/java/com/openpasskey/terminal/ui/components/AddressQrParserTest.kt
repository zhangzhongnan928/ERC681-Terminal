package com.openpasskey.terminal.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AddressQrParserTest {
    private val mixedCaseAddress = "0x7fFbA642bc902880a737cb1c18a4E9540879e211"
    private val canonicalAddress = "0x7ffba642bc902880a737cb1c18a4e9540879e211"

    @Test
    fun `raw address is canonicalized`() {
        assertEquals(canonicalAddress, AddressQrParser.parse(mixedCaseAddress))
    }

    @Test
    fun `address-only ethereum forms are accepted`() {
        assertEquals(
            canonicalAddress,
            AddressQrParser.parse("ethereum:$mixedCaseAddress"),
        )
        assertEquals(
            canonicalAddress,
            AddressQrParser.parse("ETHEREUM://$mixedCaseAddress"),
        )
    }

    @Test
    fun `payment URI including canonical conformance form is rejected`() {
        val canonicalPaymentUri =
            "ethereum:0x7ffba642bc902880a737cb1c18a4e9540879e211@84532/transfer" +
                "?address=0x9107decd2cb06c57c40a663648e19cde1d52f606" +
                "&uint256=12340000000000000000"

        assertThrows(IllegalArgumentException::class.java) {
            AddressQrParser.parse(canonicalPaymentUri)
        }
    }

    @Test
    fun `zero malformed and ambiguous payloads fail closed`() {
        listOf(
            "",
            "not an address",
            "0x1234",
            "0x0000000000000000000000000000000000000000",
            "ethereum:example.eth@1",
            "ethereum:$mixedCaseAddress@1",
            "ethereum:$mixedCaseAddress/transfer",
            "ethereum:$mixedCaseAddress?value=42",
            "ethereum:$mixedCaseAddress#fragment",
            "ethereum:$mixedCaseAddress\n",
            "wc:$mixedCaseAddress@1",
            "https://example.com/$mixedCaseAddress",
            "{\"address\":\"$mixedCaseAddress\"}",
            "0x" + "ab".repeat(32),
            "abandon ability able about above absent absorb abstract absurd abuse access accident",
        ).forEach { payload ->
            assertThrows(payload, IllegalArgumentException::class.java) {
                AddressQrParser.parse(payload)
            }
        }
    }
}
