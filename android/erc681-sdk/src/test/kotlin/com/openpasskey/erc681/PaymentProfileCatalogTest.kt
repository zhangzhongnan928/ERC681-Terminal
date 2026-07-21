package com.openpasskey.erc681

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PaymentProfileCatalogTest {
    @Test
    fun `catalog selects one complete network vault token profile`() {
        val aud = profile(84532, "11", "21", "AUD")
        val usd = profile(84532, "12", "22", "USDC")
        val catalog = PaymentProfileCatalog(listOf(aud, usd), aud.id).selecting(usd.id)

        assertEquals(usd, catalog.selected)
        assertEquals(2, catalog.profiles.size)
    }

    @Test
    fun `upsert replaces exact identity without discarding other profiles`() {
        val aud = profile(84532, "11", "21", "AUD")
        val usd = profile(84532, "12", "22", "USDC")
        val refreshedAud = aud.copy(token = aud.token.copy(symbol = "AUDM"))

        val result = PaymentProfileCatalog(listOf(aud, usd), usd.id).upserting(refreshedAud)

        assertEquals(listOf(refreshedAud, usd), result.profiles)
        assertEquals(refreshedAud, result.selected)
    }

    @Test
    fun `selection must identify a catalog profile`() {
        val profile = profile(84532, "11", "21", "AUD")
        assertFailsWith<IllegalArgumentException> {
            PaymentProfileCatalog(listOf(profile), "missing")
        }
        assertFailsWith<IllegalArgumentException> {
            PaymentProfileCatalog(listOf(profile), selectedProfileId = null)
        }
    }

    @Test
    fun `removal of selected profile reselects first remaining insertion`() {
        // The first ID intentionally sorts after the second ID. Selection is insertion-based,
        // never an address-sort rule owned by one host platform.
        val aud = profile(84532, "ff", "ee", "AUD")
        val usd = profile(8453, "11", "22", "USDC")
        val eur = profile(11155111, "33", "44", "EURC")
        val catalog = PaymentProfileCatalog(listOf(aud, usd, eur), eur.id)

        val remaining = catalog.removing(eur.id)
        val stillSelected = remaining.removing(usd.id)
        val empty = stillSelected.removing(aud.id)

        assertEquals(listOf(aud, usd), remaining.profiles)
        assertEquals(aud, remaining.selected)
        assertEquals(aud, stillSelected.selected)
        assertTrue(empty.profiles.isEmpty())
        assertEquals(null, empty.selected)
        assertFailsWith<IllegalArgumentException> { catalog.removing("missing") }
    }

    @Test
    fun `invoice creation consumes exactly one selected profile`() {
        val selected = profile(84532, "11", "21", "AUD")
        val invoice = PaymentInvoiceFactory.create(
            selected,
            TokenAmount.parse("12.34", selected.token.decimals),
            InvoiceId.parse("0x" + "51".repeat(32)),
        )

        assertEquals(selected.network.chainId, invoice.request.chainId)
        assertEquals(selected.network.vault, invoice.vault)
        assertEquals(selected.token.address, invoice.request.token)
        assertTrue(invoice.erc681Uri.startsWith("ethereum:${selected.token.address.value}@84532/transfer"))
    }

    @Test
    fun `profile invoice rejects an amount parsed for different token decimals`() {
        val selected = profile(84532, "11", "21", "USDC")

        assertFailsWith<IllegalArgumentException> {
            PaymentInvoiceFactory.create(
                selected,
                TokenAmount.parse("12.34", 18),
                InvoiceId.parse("0x" + "ab".repeat(32)),
            )
        }
    }

    private fun profile(chainId: Long, vaultByte: String, tokenByte: String, symbol: String) =
        PaymentProfile(
            network = NetworkConfig(
                chainId = chainId,
                rpcUrl = "https://rpc.example.test",
                factory = EvmAddress.parse("0x" + "31".repeat(20)),
                receiverImplementation = EvmAddress.parse("0x" + "41".repeat(20)),
                vault = EvmAddress.parse("0x" + vaultByte.repeat(20)),
            ),
            token = PaymentTokenConfig(
                EvmAddress.parse("0x" + tokenByte.repeat(20)),
                symbol,
                6,
            ),
        )
}
