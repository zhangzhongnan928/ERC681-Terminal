package com.openpasskey.terminal.settlement

import com.openpasskey.erc681.EvmAddress
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.TypeReference
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.Bool
import org.web3j.abi.datatypes.DynamicArray
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.generated.Bytes32
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.crypto.Hash
import org.web3j.utils.Numeric
import java.math.BigInteger

data class SettlementInvoiceIntent(
    val invoiceId: String,
    val receiver: String,
    val expectedAmount: BigInteger
)

data class SettlementReceiptLog(
    val address: String,
    val topics: List<String>,
    val data: String,
    val transactionHash: String? = null,
    val blockHash: String? = null,
    val logIndex: Long? = null,
    val removed: Boolean = false
)

data class VerifiedSweep(
    val invoiceId: String,
    val receiver: String,
    val vault: String,
    val token: String,
    val sweptAmount: BigInteger,
    val expectedAmount: BigInteger,
    val fee: BigInteger,
    val transactionHash: String?,
    val blockHash: String?,
    val logIndex: Long?
)

enum class SweepProofClassification { ZERO, PARTIAL, FULL }

/** ABI surface intentionally limited to the two calls required by app-layer settlement. */
object SettlementAbi {
    const val SWEPT_EVENT_SIGNATURE = "Swept(address,address,bytes32,address,uint256,uint256,uint256)"
    val sweptTopic: String = Hash.sha3String(SWEPT_EVENT_SIGNATURE).lowercase()

    fun encodeIsOperator(operatorAddress: String): String = FunctionEncoder.encode(
        Function(
            "isOperator",
            listOf(Address(EvmAddress.parse(operatorAddress).value)),
            listOf(object : TypeReference<Bool>() {})
        )
    )

    fun decodeIsOperator(result: String): Boolean {
        val bytes = Numeric.hexStringToByteArray(result)
        require(bytes.size == ABI_WORD_BYTES) { "isOperator must return exactly one ABI word" }
        require(bytes.dropLast(1).all { it == 0.toByte() }) {
            "isOperator returned a non-canonical boolean"
        }
        return when (bytes.last().toInt()) {
            0 -> false
            1 -> true
            else -> throw IllegalArgumentException("isOperator returned a non-canonical boolean")
        }
    }

    fun encodeOwner(): String = FunctionEncoder.encode(
        Function(
            "owner",
            emptyList(),
            listOf(object : TypeReference<Address>() {})
        )
    )

    fun decodeOwner(result: String): String = addressFromWord(result)

    fun encodeBalanceOf(accountAddress: String): String = FunctionEncoder.encode(
        Function(
            "balanceOf",
            listOf(Address(EvmAddress.parse(accountAddress).value)),
            listOf(object : TypeReference<Uint256>() {})
        )
    )

    fun decodeUint256Word(result: String): BigInteger {
        val bytes = Numeric.hexStringToByteArray(result)
        require(bytes.size == ABI_WORD_BYTES) { "uint256 result must contain exactly one ABI word" }
        return BigInteger(1, bytes)
    }

    fun classify(
        proof: VerifiedSweep,
        previouslyProvenSwept: BigInteger = BigInteger.ZERO
    ): SweepProofClassification {
        require(previouslyProvenSwept.signum() >= 0) { "Prior swept evidence cannot be negative" }
        val cumulative = previouslyProvenSwept.add(proof.sweptAmount)
        return when {
        cumulative.signum() == 0 -> SweepProofClassification.ZERO
        cumulative < proof.expectedAmount -> SweepProofClassification.PARTIAL
        else -> SweepProofClassification.FULL
        }
    }

    fun encodeSweepSessions(intents: List<SettlementInvoiceIntent>, tokenAddress: String): String {
        require(intents.isNotEmpty()) { "At least one invoice is required" }
        require(intents.size <= MAX_BATCH_SIZE) { "A settlement batch is limited to $MAX_BATCH_SIZE invoices" }
        require(intents.map { it.invoiceId.lowercase() }.distinct().size == intents.size) {
            "Duplicate invoice IDs are not allowed in a settlement batch"
        }
        val invoiceIds = intents.map { intent ->
            val bytes = Numeric.hexStringToByteArray(intent.invoiceId)
            require(bytes.size == 32) { "Invoice ID must contain exactly 32 bytes" }
            Bytes32(bytes)
        }
        val amounts = intents.map { intent ->
            require(intent.expectedAmount.signum() > 0) { "Expected amount must be positive" }
            Uint256(intent.expectedAmount)
        }
        return FunctionEncoder.encode(
            Function(
                "sweepSessions",
                listOf(
                    DynamicArray(Bytes32::class.java, invoiceIds),
                    DynamicArray(Uint256::class.java, amounts),
                    Address(EvmAddress.parse(tokenAddress).value)
                ),
                emptyList()
            )
        )
    }

    /**
     * Returns exactly one fully matching Swept event per invoice. The swept amount is deliberately
     * not interpreted here: callers must distinguish zero, partial, and complete proofs. Missing,
     * removed, malformed, mismatched, or duplicate events are omitted.
     */
    fun verifySweptEvents(
        logs: List<SettlementReceiptLog>,
        vaultAddress: String,
        tokenAddress: String,
        intents: List<SettlementInvoiceIntent>
    ): Map<String, VerifiedSweep> {
        val vault = EvmAddress.parse(vaultAddress).value.lowercase()
        val token = EvmAddress.parse(tokenAddress).value.lowercase()
        require(intents.map { normalizeBytes32(it.invoiceId) }.distinct().size == intents.size) {
            "Duplicate invoice IDs are not allowed"
        }
        val expected = intents.associateBy { normalizeBytes32(it.invoiceId) }
        val matches = linkedMapOf<String, VerifiedSweep>()
        val ambiguous = mutableSetOf<String>()

        logs.asSequence()
            .mapNotNull(::decodeSweptEvent)
            .filter { event ->
                event.vault.lowercase() == vault &&
                    event.token.lowercase() == token
            }
            .forEach { event ->
                val intent = expected[event.invoiceId.lowercase()] ?: return@forEach
                if (!event.receiver.equals(EvmAddress.parse(intent.receiver).value, ignoreCase = true)) return@forEach
                if (event.expectedAmount != intent.expectedAmount) return@forEach
                // Ambiguous duplicate evidence is rejected rather than silently choosing a log.
                val key = event.invoiceId.lowercase()
                if (key in ambiguous) return@forEach
                if (matches.remove(key) != null) ambiguous += key else matches[key] = event
            }
        return matches
    }

    private fun decodeSweptEvent(log: SettlementReceiptLog): VerifiedSweep? = runCatching {
        if (log.removed) return null
        if (log.topics.size != 3 || log.topics[0].lowercase() != sweptTopic) return null
        val emittingVault = EvmAddress.parse(log.address).value
        val receiver = addressFromWord(log.topics[1])
        val indexedVault = addressFromWord(log.topics[2])
        if (!emittingVault.equals(indexedVault, ignoreCase = true)) return null

        val data = Numeric.hexStringToByteArray(log.data)
        if (data.size != EVENT_DATA_BYTES) return null
        val invoiceId = "0x" + Numeric.toHexStringNoPrefix(data.copyOfRange(0, 32)).lowercase()
        if (!data.copyOfRange(32, 44).all { it == 0.toByte() }) return null
        val token = addressFromBytes(data.copyOfRange(44, 64))
        VerifiedSweep(
            invoiceId = invoiceId,
            receiver = receiver,
            vault = indexedVault,
            token = token,
            sweptAmount = BigInteger(1, data.copyOfRange(64, 96)),
            expectedAmount = BigInteger(1, data.copyOfRange(96, 128)),
            fee = BigInteger(1, data.copyOfRange(128, 160)),
            transactionHash = log.transactionHash,
            blockHash = log.blockHash,
            logIndex = log.logIndex
        )
    }.getOrNull()

    private fun addressFromWord(value: String): String {
        val bytes = Numeric.hexStringToByteArray(value)
        require(bytes.size == 32) { "Indexed address topic must contain 32 bytes" }
        require(bytes.copyOfRange(0, 12).all { it == 0.toByte() }) { "Indexed address is not ABI padded" }
        return addressFromBytes(bytes.copyOfRange(12, 32))
    }

    private fun addressFromBytes(bytes: ByteArray): String {
        require(bytes.size == 20) { "Address must contain 20 bytes" }
        return EvmAddress.parse("0x${Numeric.toHexStringNoPrefix(bytes)}").value
    }

    private fun normalizeBytes32(value: String): String {
        val bytes = Numeric.hexStringToByteArray(value)
        require(bytes.size == 32) { "Invoice ID must contain exactly 32 bytes" }
        return "0x${Numeric.toHexStringNoPrefix(bytes).lowercase()}"
    }

    private const val EVENT_DATA_BYTES = 32 * 5
    private const val ABI_WORD_BYTES = 32
    const val MAX_BATCH_SIZE = 20
}
