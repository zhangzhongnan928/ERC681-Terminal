package com.openpasskey.erc681

import com.google.gson.JsonParser
import java.math.BigInteger
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ConformanceTest {
    private val vector by lazy {
        val json = checkNotNull(javaClass.getResourceAsStream("/opk-erc681-v1.json")) {
            "Missing conformance/opk-erc681-v1.json test resource"
        }.bufferedReader().use { it.readText() }
        JsonParser.parseString(json).asJsonObject
    }

    @Test
    fun `keccak implementation uses Ethereum Keccak not NIST SHA3`() {
        assertEquals(
            "0xc5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470",
            Hex.encode(Keccak256.digest(ByteArray(0))),
        )
    }

    @Test
    fun `invoice ID matches checked-in protocol vector`() {
        val invoice = vector.getAsJsonObject("invoiceVector")
        val actual = InvoiceIdGenerator.generate(
            terminalIdentifier = EvmAddress.parse(invoice.get("terminalIdentifier").asString),
            timestampSeconds = invoice.get("timestampSeconds").asLong,
            nonce = Hex.decode(invoice.get("nonce").asString, 32),
        )

        assertEquals(invoice.get("invoiceId").asString, actual.hex)
    }

    @Test
    fun `random invoice IDs are 32 bytes and unique without a wallet key`() {
        val terminal = EvmAddress.parse("0x1111111111111111111111111111111111111111")
        val random = SecureRandom()
        val first = InvoiceIdGenerator.generate(terminal, 1_720_000_000, random)
        val second = InvoiceIdGenerator.generate(terminal, 1_720_000_000, random)

        assertEquals(32, first.toByteArray().size)
        assertNotEquals(first, second)
        assertEquals(32, InvoiceIdGenerator.generate().toByteArray().size)
    }

    @Test
    fun `CREATE2 derivation matches checked-in 88-byte conformance vector`() {
        val configuration = vector.getAsJsonObject("configuration")
        val invoice = vector.getAsJsonObject("invoiceVector")
        val receiver = vector.getAsJsonObject("receiverVector")
        val resolver = Create2ReceiverResolver(
            factory = EvmAddress.parse(configuration.get("factory").asString),
            receiverImplementation = EvmAddress.parse(configuration.get("receiverImplementation").asString),
        )
        val vault = EvmAddress.parse(configuration.get("vault").asString)
        val invoiceId = InvoiceId.parse(invoice.get("invoiceId").asString)
        val initCode = resolver.initCode(vault)

        assertEquals(Create2ReceiverResolver.INIT_CODE_SIZE, initCode.size)
        assertEquals(receiver.get("initCode").asString.lowercase(), Hex.encode(initCode))
        assertEquals(receiver.get("initCodeHash").asString.lowercase(), Hex.encode(Keccak256.digest(initCode)))
        assertEquals(EvmAddress.parse(receiver.get("receiver").asString), resolver.resolve(vault, invoiceId))
    }

    @Test
    fun `CREATE2 derivation matches supplied aaaa invoice golden receiver`() {
        val resolver = Create2ReceiverResolver(
            factory = EvmAddress.parse("0x062e3b5d3107e4d1b8dDA314E16b9F8cA6EB63D5"),
            receiverImplementation = EvmAddress.parse("0xDAa292B1bf533737C5cE5d27F220273971Db3Bdc"),
        )
        val actual = resolver.resolve(
            vault = EvmAddress.parse("0x1111111111111111111111111111111111111111"),
            invoiceId = InvoiceId.parse("0x" + "aa".repeat(32)),
        )

        assertEquals(EvmAddress.parse("0x780fA11FED13b1ea7d92e6Aaf0212F63961A3DA4"), actual)
    }

    @Test
    fun `canonical ERC-681 output and parse match protocol vector`() {
        val configuration = vector.getAsJsonObject("configuration")
        val token = configuration.getAsJsonObject("token")
        val receiver = vector.getAsJsonObject("receiverVector").get("receiver").asString
        val rawUnits = vector.getAsJsonObject("amountVector").get("rawUnits").asString
        val request = Erc681PaymentRequest(
            token = EvmAddress.parse(token.get("address").asString),
            chainId = configuration.get("chainId").asLong,
            receiver = EvmAddress.parse(receiver),
            amount = TokenAmount.ofRaw(BigInteger(rawUnits), token.get("decimals").asInt),
        )
        val canonical =
            "ethereum:${token.get("address").asString.lowercase()}@${configuration.get("chainId").asLong}" +
                "/transfer?address=${receiver.lowercase()}&uint256=$rawUnits"

        assertEquals(canonical, Erc681Codec.encode(request))
        assertEquals(request, Erc681Codec.parse(canonical, request.chainId, request.amount.decimals))
    }

    @Test
    fun `non-canonical and wrong-chain requests fail closed`() {
        val chainId = vector.getAsJsonObject("configuration").get("chainId").asLong
        vector.getAsJsonArray("mustReject").forEach { rejected ->
            assertFailsWith<IllegalArgumentException>(rejected.asString) {
                Erc681Codec.parse(rejected.asString, expectedChainId = chainId, tokenDecimals = 18)
            }
        }

        val valid = vector.get("erc681").asString
        assertFailsWith<IllegalArgumentException> {
            Erc681Codec.parse(valid.replace("&uint256=", "&foo=1&uint256="), chainId, 18)
        }
        assertFailsWith<IllegalArgumentException> {
            Erc681Codec.parse(valid.replace("uint256=", "uint256=0"), chainId, 18)
        }
    }

    @Test
    fun `exact token amounts support common decimals without rounding`() {
        assertEquals(BigInteger("1234567"), TokenAmount.parse("1.234567", 6).rawUnits)
        assertEquals(BigInteger("123456789"), TokenAmount.parse("1.23456789", 8).rawUnits)
        assertEquals(BigInteger("12340000000000000000"), TokenAmount.parse("12.34", 18).rawUnits)
        assertEquals("12.34", TokenAmount.ofRaw(BigInteger("12340000000000000000"), 18).display)

        listOf("", " 1", "1 ", "+1", "-1", "1e2", "1.2.3", ".5", "0").forEach { invalid ->
            assertFailsWith<IllegalArgumentException>(invalid) { TokenAmount.parse(invalid, 6) }
        }
        assertFailsWith<IllegalArgumentException> { TokenAmount.parse("1.0000001", 6) }
        assertFailsWith<IllegalArgumentException> { TokenAmount.parse("1.0000000", 6) }
        assertFailsWith<IllegalArgumentException> { TokenAmount.ofRaw(BigInteger.ONE.shiftLeft(256), 18) }
    }

    @Test
    fun `addresses canonicalize to lowercase and reject malformed input`() {
        val address = EvmAddress.parse("0x7fFbA642bc902880a737cb1c18a4E9540879e211")
        assertEquals("0x7ffba642bc902880a737cb1c18a4e9540879e211", address.value)
        assertFalse(address.isZero)
        assertFailsWith<IllegalArgumentException> { EvmAddress.parse("7ffba642bc902880a737cb1c18a4e9540879e211") }
        assertFailsWith<IllegalArgumentException> { EvmAddress.parse("0x1234") }
        assertTrue(EvmAddress.parse("0x" + "00".repeat(20)).isZero)
    }
}
