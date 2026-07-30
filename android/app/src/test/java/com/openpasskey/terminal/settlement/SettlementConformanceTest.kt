package com.openpasskey.terminal.settlement

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.openpasskey.erc681.NativeAsset
import com.openpasskey.terminal.data.model.SettlementFeeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.web3j.crypto.Credentials
import org.web3j.crypto.Hash
import org.web3j.crypto.RawTransaction
import org.web3j.crypto.TransactionEncoder
import org.web3j.utils.Numeric
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path

class SettlementConformanceTest {
    private val root: JsonObject = loadFixture()
    private val abi = root.getAsJsonObject("settlementAbi")
    private val sweptLog = abi.getAsJsonObject("sweptLog")

    @Test
    fun `fixture versions and canonical authorization calldata match`() {
        assertEquals(2, root["schemaVersion"].asInt)
        assertEquals("1.6", root["paymentVectorVersion"].asString)
        assertEquals("1.5", root["deploymentProtocolVersion"].asString)
        assertEquals("1.5", abi["protocolVersion"].asString)
        assertEquals(abi["isOperatorCalldata"].asString, SettlementAbi.encodeIsOperator(abi["operator"].asString))
        assertFalse(SettlementAbi.decodeIsOperator(abi["isOperatorFalseResult"].asString))
        assertTrue(SettlementAbi.decodeIsOperator(abi["isOperatorTrueResult"].asString))
        assertThrows(IllegalArgumentException::class.java) {
            SettlementAbi.decodeIsOperator("0x" + "00".repeat(31) + "02")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SettlementAbi.decodeIsOperator(abi["isOperatorTrueResult"].asString + "00")
        }
        assertEquals("0x8da5cb5b", SettlementAbi.encodeOwner())
    }

    @Test
    fun `single and two item sweep calldata match fixture exactly`() {
        val invoiceId = invoiceId()
        val token = tokenAddress()
        val receiver = receiverAddress()
        val amount = BigInteger(sweptLog["expectedAmount"].asString)
        assertEquals(
            abi["sweepSessionsCalldata"].asString,
            SettlementAbi.encodeSweepSessions(
                listOf(SettlementInvoiceIntent(invoiceId, receiver, amount)),
                token
            )
        )
        assertEquals(
            abi["sweepSessionsTwoItemCalldata"].asString,
            SettlementAbi.encodeSweepSessions(
                listOf(
                    SettlementInvoiceIntent(invoiceId, receiver, amount),
                    SettlementInvoiceIntent("0x" + "aa".repeat(32), receiver, BigInteger.ONE)
                ),
                token
            )
        )
        assertThrows(IllegalArgumentException::class.java) {
            SettlementAbi.encodeSweepSessions(
                (0..SettlementAbi.MAX_BATCH_SIZE).map {
                    SettlementInvoiceIntent("0x" + it.toString(16).padStart(64, '0'), receiver, BigInteger.ONE)
                },
                token
            )
        }
    }

    @Test
    fun `native asset uses the unchanged sweepSessions entry point`() {
        val encoded = SettlementAbi.encodeSweepSessions(
            listOf(
                SettlementInvoiceIntent(
                    invoiceId(),
                    receiverAddress(),
                    BigInteger.ONE,
                ),
            ),
            NativeAsset.address.value,
        )

        assertTrue(encoded.startsWith(abi["sweepSessionsSelector"].asString))
        val tokenWord = encoded.removePrefix("0x").drop(8).chunked(64)[2]
        assertEquals(
            NativeAsset.address.value.removePrefix("0x"),
            tokenWord.takeLast(40),
        )
    }

    @Test
    fun `full zero and partial sweep evidence retain canonical identity and amount`() {
        val intent = intent()
        val full = verified(log(sweptLog["data"].asString), intent)
        assertEquals(SweepProofClassification.FULL, SettlementAbi.classify(full))
        assertEquals(BigInteger(sweptLog["sweptAmount"].asString), full.sweptAmount)
        assertEquals(BigInteger(sweptLog["fee"].asString), full.fee)
        assertEquals(9L, full.logIndex)
        assertEquals(TX_HASH, full.transactionHash)
        assertEquals(BLOCK_HASH, full.blockHash)

        val zero = verified(log(sweptLog["zeroSweptData"].asString), intent)
        assertEquals(SweepProofClassification.ZERO, SettlementAbi.classify(zero))
        assertEquals(
            SweepProofClassification.ZERO,
            SettlementAbi.classify(zero, previouslyProvenSwept = full.sweptAmount),
        )
        assertEquals(BigInteger.ZERO, zero.sweptAmount)

        val partial = verified(log(sweptLog["partialSweptData"].asString), intent)
        assertEquals(SweepProofClassification.PARTIAL, SettlementAbi.classify(partial))
        assertEquals(BigInteger("5000000000000000000"), partial.sweptAmount)
        val later = partial.copy(sweptAmount = BigInteger("7340000000000000000"))
        assertEquals(
            SweepProofClassification.FULL,
            SettlementAbi.classify(later, previouslyProvenSwept = partial.sweptAmount)
        )
    }

    @Test
    fun `removed malformed and permanently ambiguous duplicate logs fail closed`() {
        val intent = intent()
        val canonical = log(sweptLog["data"].asString)
        assertTrue(verify(listOf(canonical.copy(removed = true)), intent).isEmpty())

        val malformed = Numeric.hexStringToByteArray(sweptLog["data"].asString)
        malformed[32] = 1 // token address word must have twelve leading zero bytes
        assertTrue(verify(listOf(log(Numeric.toHexString(malformed))), intent).isEmpty())

        // A third matching log must never re-add evidence after the second made it ambiguous.
        assertTrue(verify(listOf(canonical, canonical, canonical), intent).isEmpty())
    }

    @Test
    fun `type two signing vector produces exact raw transaction and hash`() {
        val vector = root.getAsJsonObject("settlementSigningVector")
        val credentials = Credentials.create(vector["privateKey"].asString)
        assertEquals(vector["operator"].asString, "0x" + credentials.address.removePrefix("0x"))
        val raw = RawTransaction.createTransaction(
            vector["chainId"].asLong,
            BigInteger.valueOf(vector["nonce"].asLong),
            BigInteger(vector["gasLimit"].asString),
            vector["to"].asString,
            BigInteger(vector["value"].asString),
            vector["calldata"].asString,
            BigInteger(vector["maxPriorityFeePerGas"].asString),
            BigInteger(vector["maxFeePerGas"].asString)
        )
        val signed = TransactionEncoder.signMessage(raw, credentials)
        assertEquals(vector["rawTransaction"].asString, Numeric.toHexString(signed))
        assertEquals(vector["transactionHash"].asString, Numeric.toHexString(Hash.sha3(signed)))
    }

    @Test
    fun `fee mode and reserve policy fail closed without blind type fallback`() {
        val legacy = SettlementFeePolicy.quote(null, BigInteger("100"))
        assertEquals(SettlementFeeMode.LEGACY, legacy.mode)
        assertEquals(BigInteger("120"), legacy.gasPrice)
        val typeTwo = SettlementFeePolicy.quote(BigInteger("100"), BigInteger("110"))
        assertEquals(SettlementFeeMode.EIP1559, typeTwo.mode)
        val requirement = SettlementBalancePolicy.requirement(BigInteger("100000"), typeTwo)
        assertTrue(requirement.requiredBalance > requirement.maximumGasCost)
        assertTrue(requirement.safetyReserve >= BigInteger("100000000000000"))
    }

    @Test
    fun `orphan or unavailable receipt block is never canonical proof`() {
        val receipt = SettlementReceipt(
            successful = true,
            blockNumber = 123,
            blockHash = BLOCK_HASH,
            transactionHash = TX_HASH,
            logs = emptyList(),
        )
        assertTrue(
            com.openpasskey.terminal.data.repository.receiptMatchesCanonicalBlock(
                receipt,
                BLOCK_HASH.uppercase(),
            ),
        )
        assertFalse(
            com.openpasskey.terminal.data.repository.receiptMatchesCanonicalBlock(
                receipt,
                "0x" + "cc".repeat(32),
            ),
        )
        assertFalse(
            com.openpasskey.terminal.data.repository.receiptMatchesCanonicalBlock(receipt, null),
        )
    }

    @Test
    fun `confirmation depth must still hold after final receipt identity checks`() {
        val receiptBlock = 123L
        val requiredConfirmations = 2
        val firstHead = 124L
        val regressedFinalHead = 123L

        assertTrue(
            com.openpasskey.terminal.data.repository.settlementHasRequiredConfirmationDepth(
                receiptBlock,
                requiredConfirmations,
                firstHead,
            ),
        )
        assertFalse(
            com.openpasskey.terminal.data.repository.settlementHasRequiredConfirmationDepth(
                receiptBlock,
                requiredConfirmations,
                regressedFinalHead,
            ),
        )
        assertThrows(ArithmeticException::class.java) {
            com.openpasskey.terminal.data.repository.settlementConfirmationTarget(
                Long.MAX_VALUE,
                2,
            )
        }
    }

    @Test
    fun `ambiguous recovery stays under review until cumulative proof is full`() {
        assertTrue(com.openpasskey.terminal.data.repository.shouldPersistSettlementReview(true, null))
        assertTrue(
            com.openpasskey.terminal.data.repository.shouldPersistSettlementReview(
                true,
                SweepProofClassification.ZERO,
            ),
        )
        assertTrue(
            com.openpasskey.terminal.data.repository.shouldPersistSettlementReview(
                true,
                SweepProofClassification.PARTIAL,
            ),
        )
        assertFalse(
            com.openpasskey.terminal.data.repository.shouldPersistSettlementReview(
                true,
                SweepProofClassification.FULL,
            ),
        )
        assertFalse(
            com.openpasskey.terminal.data.repository.shouldPersistSettlementReview(
                false,
                SweepProofClassification.PARTIAL,
            ),
        )
    }

    private fun verified(log: SettlementReceiptLog, intent: SettlementInvoiceIntent): VerifiedSweep =
        verify(listOf(log), intent).getValue(intent.invoiceId.lowercase())

    private fun verify(
        logs: List<SettlementReceiptLog>,
        intent: SettlementInvoiceIntent
    ): Map<String, VerifiedSweep> = SettlementAbi.verifySweptEvents(
        logs,
        sweptLog["address"].asString,
        tokenAddress(),
        listOf(intent)
    )

    private fun intent() = SettlementInvoiceIntent(
        invoiceId(),
        receiverAddress(),
        BigInteger(sweptLog["expectedAmount"].asString)
    )

    private fun log(data: String) = SettlementReceiptLog(
        address = sweptLog["address"].asString,
        topics = sweptLog.getAsJsonArray("topics").map { it.asString },
        data = data,
        transactionHash = TX_HASH,
        blockHash = BLOCK_HASH,
        logIndex = 9,
        removed = false
    )

    private fun invoiceId(): String = "0x" + sweptLog["data"].asString.removePrefix("0x").take(64)
    private fun tokenAddress(): String = "0x" + sweptLog["data"].asString.removePrefix("0x").substring(88, 128)
    private fun receiverAddress(): String = "0x" + sweptLog.getAsJsonArray("topics")[1].asString.takeLast(40)

    private fun loadFixture(): JsonObject {
        val candidates = listOf(
            Path.of("../conformance/opk-erc681-v1.json"),
            Path.of("conformance/opk-erc681-v1.json"),
            Path.of("../../conformance/opk-erc681-v1.json")
        )
        val path = candidates.firstOrNull(Files::isRegularFile)
            ?: error("Unable to locate shared conformance fixture from ${Path.of("").toAbsolutePath()}")
        return JsonParser.parseString(String(Files.readAllBytes(path), Charsets.UTF_8)).asJsonObject
    }

    companion object {
        private const val TX_HASH =
            "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        private const val BLOCK_HASH =
            "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
