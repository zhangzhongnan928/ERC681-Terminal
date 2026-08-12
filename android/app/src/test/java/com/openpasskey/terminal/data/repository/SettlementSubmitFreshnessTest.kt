package com.openpasskey.terminal.data.repository

import com.google.gson.Gson
import com.openpasskey.terminal.chain.TerminalConfigSnapshot
import com.openpasskey.terminal.data.db.InvoiceDao
import com.openpasskey.terminal.data.db.InvoiceDatabase
import com.openpasskey.terminal.data.db.SettlementDao
import com.openpasskey.terminal.data.db.SettlementEventDao
import com.openpasskey.terminal.data.model.Invoice
import com.openpasskey.terminal.data.model.InvoiceStatus
import com.openpasskey.terminal.data.model.SettlementFeeMode
import com.openpasskey.terminal.lifecycle.TerminalLifecycleGate
import com.openpasskey.terminal.rpc.RpcEndpointOverrideState
import com.openpasskey.terminal.rpc.RpcEndpointResolution
import com.openpasskey.terminal.rpc.RpcEndpointResolver
import com.openpasskey.terminal.rpc.RpcEndpointSnapshot
import com.openpasskey.terminal.rpc.RpcWorkCoordinator
import com.openpasskey.terminal.settlement.SettlementBalancePolicy
import com.openpasskey.terminal.settlement.SettlementChainClient
import com.openpasskey.terminal.settlement.SettlementFeeQuote
import com.openpasskey.terminal.settlement.SettlementPreflightSnapshot
import com.openpasskey.terminal.wallet.OperatorWalletAvailability
import com.openpasskey.terminal.wallet.OperatorWalletSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.web3j.crypto.RawTransaction
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.math.BigInteger
import java.util.concurrent.atomic.AtomicLong

class SettlementSubmitFreshnessTest {
    @Test
    fun `submit refuses key use and persistence when live preflight crosses proof TTL`() {
        val invoice = confirmedInvoice()
        val persistence = PersistenceCalls()
        val database = fakeDatabase(invoice, persistence)
        val wallet = RecordingWalletAccess()
        val monotonicClock = AtomicLong(INITIAL_NOW)
        var preflightCalls = 0
        var broadcastCalls = 0
        val client = proxy<SettlementChainClient> { method, arguments ->
            when (method.name) {
                "settlementPreflight" -> {
                    preflightCalls += 1
                    assertEquals(false, arguments?.get(1))
                    monotonicClock.set(EXPIRED_AFTER_PREFLIGHT)
                    preflightSnapshot()
                }
                "sendRawTransaction" -> {
                    broadcastCalls += 1
                    "0x${"00".repeat(32)}"
                }
                "close" -> Unit
                else -> error("Submit unexpectedly called settlement RPC ${method.name}")
            }
        }
        val repository = SettlementRepository(
            database = database,
            walletAccess = wallet,
            chainConfigSnapshot = ::unprovisionedConfig,
            lifecycleGate = TerminalLifecycleGate(),
            rpcWorkCoordinator = RpcWorkCoordinator(),
            clientFactory = { client },
            gson = Gson(),
            elapsedRealtimeMillis = monotonicClock::get,
        )

        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                repository.submit(
                    reviewed = preparedSettlement(invoice),
                    userExplicitlyConfirmed = true,
                    authenticatedAtElapsedRealtimeMillis = INITIAL_NOW,
                )
            }
        }

        assertTrue(error.message.orEmpty().contains("Settlement preflight expired"))
        assertEquals(1, preflightCalls)
        assertEquals(0, wallet.signerActivations)
        assertEquals(0, persistence.settlementInserts)
        assertEquals(0, persistence.invoiceAttachments)
        assertEquals(0, broadcastCalls)
    }

    @Test
    fun `endpoint generation is rechecked at final key use boundary`() {
        val invoice = confirmedInvoice()
        val persistence = PersistenceCalls()
        val resolver = SwitchingResolver("https://rpc-a.example/credential-a")
        val database = fakeDatabase(invoice, persistence) { readCount ->
            if (readCount == 2) resolver.switchTo("https://rpc-b.example/credential-b")
        }
        val wallet = RecordingWalletAccess()
        var preflightCalls = 0
        val client = proxy<SettlementChainClient> { method, arguments ->
            when (method.name) {
                "settlementPreflight" -> {
                    preflightCalls += 1
                    assertEquals(false, arguments?.get(1))
                    preflightSnapshot()
                }
                "close" -> Unit
                else -> error("Submit unexpectedly called settlement RPC ${method.name}")
            }
        }
        val repository = SettlementRepository(
            database = database,
            walletAccess = wallet,
            chainConfigSnapshot = ::unprovisionedConfig,
            lifecycleGate = TerminalLifecycleGate(),
            rpcWorkCoordinator = RpcWorkCoordinator(),
            rpcEndpointResolver = resolver,
            clientFactory = { client },
            gson = Gson(),
            elapsedRealtimeMillis = { INITIAL_NOW },
        )

        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                repository.submit(
                    reviewed = preparedSettlement(invoice),
                    userExplicitlyConfirmed = true,
                    authenticatedAtElapsedRealtimeMillis = INITIAL_NOW,
                )
            }
        }

        assertTrue(error.message.orEmpty().contains("RPC endpoint changed"))
        assertEquals(1, preflightCalls)
        assertEquals(0, wallet.signerActivations)
        assertEquals(0, persistence.settlementInserts)
        assertEquals(0, persistence.invoiceAttachments)
    }

    private class RecordingWalletAccess : SettlementWalletAccess {
        var signerActivations = 0

        override fun snapshot() = OperatorWalletSnapshot(
            availability = OperatorWalletAvailability.READY,
            address = OPERATOR,
        )

        override fun activateAndSignSettlementTransaction(
            transaction: RawTransaction,
            chainId: Long,
            vaultAddress: String,
            operatorAddress: String,
            eip1559: Boolean,
        ): ByteArray {
            signerActivations += 1
            return byteArrayOf(1, 2, 3)
        }
    }

    private data class PersistenceCalls(
        var settlementInserts: Int = 0,
        var invoiceAttachments: Int = 0,
    )

    private fun fakeDatabase(
        invoice: Invoice,
        calls: PersistenceCalls,
        onInvoicesRead: (Int) -> Unit = {},
    ): InvoiceDatabase {
        var invoiceReads = 0
        val invoiceDao = proxy<InvoiceDao> { method, _ ->
            when (method.name) {
                "getByIds" -> {
                    invoiceReads += 1
                    onInvoicesRead(invoiceReads)
                    listOf(invoice)
                }
                "attachSettlement" -> {
                    calls.invoiceAttachments += 1
                    1
                }
                else -> error("Submit unexpectedly called InvoiceDao.${method.name}")
            }
        }
        val settlementDao = proxy<SettlementDao> { method, _ ->
            when (method.name) {
                "getActiveForOperator" -> emptyList<Any>()
                "insert" -> {
                    calls.settlementInserts += 1
                    Unit
                }
                else -> error("Submit unexpectedly called SettlementDao.${method.name}")
            }
        }
        val eventDao = proxy<SettlementEventDao> { method, _ ->
            error("Submit unexpectedly called SettlementEventDao.${method.name}")
        }
        val database = Class.forName(
            "com.openpasskey.terminal.data.db.InvoiceDatabase_Impl",
        ).getDeclaredConstructor().newInstance() as InvoiceDatabase
        database.setGeneratedDao("_invoiceDao", invoiceDao)
        database.setGeneratedDao("_settlementDao", settlementDao)
        database.setGeneratedDao("_settlementEventDao", eventDao)
        return database
    }

    private class SwitchingResolver(initialEndpoint: String) : RpcEndpointResolver {
        private var endpoint = initialEndpoint
        private var generation = 0L

        override fun snapshot(chainId: Long): RpcEndpointSnapshot = RpcEndpointSnapshot(
            chainId = chainId,
            state = RpcEndpointOverrideState.READY,
            providerLabel = "Test provider",
        )

        @Synchronized
        override fun resolve(chainId: Long, fallbackUrl: String): String = endpoint

        @Synchronized
        override fun resolveCurrent(chainId: Long, fallbackUrl: String): RpcEndpointResolution =
            RpcEndpointResolution(chainId, endpoint, generation)

        @Synchronized
        override fun isCurrent(resolution: RpcEndpointResolution): Boolean =
            resolution.generation == generation

        @Synchronized
        fun switchTo(nextEndpoint: String) {
            endpoint = nextEndpoint
            generation += 1
        }
    }

    private fun InvoiceDatabase.setGeneratedDao(fieldName: String, dao: Any) {
        javaClass.getDeclaredField(fieldName).apply {
            isAccessible = true
            set(this@setGeneratedDao, dao)
        }
    }

    private fun preparedSettlement(invoice: Invoice): PreparedSettlement {
        val gasLimit = BigInteger.valueOf(100_000)
        val feeQuote = SettlementFeeQuote(
            mode = SettlementFeeMode.LEGACY,
            gasPrice = BigInteger.ONE,
        )
        val requirement = SettlementBalancePolicy.requirement(gasLimit, feeQuote)
        return PreparedSettlement(
            invoiceIds = listOf(invoice.invoiceId),
            chainId = invoice.chainId,
            networkName = invoice.networkName,
            rpcUrl = invoice.rpcUrl,
            rpcEndpointGeneration = 0,
            vaultAddress = invoice.vaultAddress,
            tokenAddress = invoice.token,
            tokenSymbol = invoice.tokenSymbol,
            tokenDecimals = invoice.tokenDecimals,
            operatorAddress = OPERATOR,
            totalExpectedAmount = BigInteger.ONE,
            totalObservedAmount = BigInteger.ONE,
            confirmedObservedAmounts = listOf(BigInteger.ONE),
            callData = "0x682b11b5",
            nonce = BigInteger.ZERO,
            gasLimit = gasLimit,
            feeQuote = feeQuote,
            maximumGasCost = requirement.maximumGasCost,
            safetyReserve = requirement.safetyReserve,
            requiredBalance = requirement.requiredBalance,
            currentBalance = LIVE_NATIVE_BALANCE,
            requiredConfirmations = invoice.confirmationBlocks,
            confirmedRequiredBalance = requirement.requiredBalance,
            historicalProofFingerprint = historicalProofFingerprint(invoice),
            historicalProofAtElapsedRealtimeMillis = PROOF_ISSUED_AT,
            gasEstimateAtElapsedRealtimeMillis = PROOF_ISSUED_AT,
            preparedAtElapsedRealtimeMillis = PROOF_ISSUED_AT,
        )
    }

    private fun preflightSnapshot() = SettlementPreflightSnapshot(
        chainId = CHAIN_ID,
        ownerAddress = null,
        operatorListed = true,
        canonicalBlockHashes = listOf(CONFIRMATION_HASH),
        canonicalBlockHashesAfter = listOf(CONFIRMATION_HASH),
        receiverBalances = listOf(BigInteger.ONE),
        nonce = BigInteger.ZERO,
        gasLimit = null,
        feeQuote = SettlementFeeQuote(
            mode = SettlementFeeMode.LEGACY,
            gasPrice = BigInteger.ONE,
        ),
        nativeBalance = LIVE_NATIVE_BALANCE,
    )

    private fun confirmedInvoice() = Invoice(
        invoiceId = INVOICE_ID,
        receiver = RECEIVER,
        operatorAddress = OPERATOR,
        token = TOKEN,
        tokenSymbol = "AUD",
        tokenDecimals = 18,
        expectedAmount = "1",
        receivedAmount = "1",
        status = InvoiceStatus.PAID,
        createdAt = 1,
        chainId = CHAIN_ID,
        networkName = NETWORK_NAME,
        rpcUrl = RPC_URL,
        factoryAddress = FACTORY,
        receiverImplementationAddress = RECEIVER_IMPLEMENTATION,
        vaultAddress = VAULT,
        confirmationBlocks = 2,
        firstDetectedBlock = 10,
        firstDetectedBlockHash = CONFIRMATION_HASH,
        lastObservedBlock = 11,
        confirmedAtBlock = 11,
    )

    private fun historicalProofFingerprint(invoice: Invoice) = listOf(
        invoice.chainId.toString(),
        RPC_URL,
        FACTORY.lowercase(),
        RECEIVER_IMPLEMENTATION.lowercase(),
        VAULT_RUNTIME_HASH,
        invoice.vaultAddress.lowercase(),
        invoice.token.lowercase(),
        invoice.tokenDecimals.toString(),
        invoice.tokenSymbol,
    ).joinToString("|")

    private fun unprovisionedConfig() = TerminalConfigSnapshot(
        networkName = NETWORK_NAME,
        rpcUrl = RPC_URL,
        chainId = CHAIN_ID,
        factoryAddress = FACTORY,
        receiverImplementationAddress = RECEIVER_IMPLEMENTATION,
        vaultAddress = "",
        confirmationBlocks = 2,
        paymentTokens = emptyList(),
        protocolVersion = "",
        provisionedOperatorAddress = null,
        provisioned = false,
    )

    private inline fun <reified T> proxy(
        crossinline handler: (Method, Array<out Any?>?) -> Any?,
    ): T = Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java),
    ) { _, method, arguments -> handler(method, arguments) } as T

    private companion object {
        const val CHAIN_ID = 84532L
        const val NETWORK_NAME = "Base Sepolia"
        const val RPC_URL = "https://sepolia.base.org"
        const val FACTORY = "0x2592fbab9707e65e21ea14d8a9fe298f5e68a37f"
        const val RECEIVER_IMPLEMENTATION = "0xf2e0d5fc47761cac0eedee6cb1af5f31843a0a18"
        const val VAULT = "0x1111111111111111111111111111111111111111"
        const val INVOICE_ID =
            "0x294d3b9eb0136d18f2a3e8aa9c10224029893c244e822a15902256d778f7f729"
        const val RECEIVER = "0xbbd352de4428d535ac79849abefa8d69bb51c671"
        const val TOKEN = "0x7ffba642bc902880a737cb1c18a4e9540879e211"
        const val OPERATOR = "0x1111111111111111111111111111111111111111"
        const val CONFIRMATION_HASH =
            "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val VAULT_RUNTIME_HASH =
            "0x32ad6b6076f449fbc39e115afc2645c65071280af2d461dc315544ac0a1d7e58"
        const val PROOF_ISSUED_AT = 50_000L
        const val INITIAL_NOW = 100_000L
        const val EXPIRED_AFTER_PREFLIGHT = 110_001L
        val LIVE_NATIVE_BALANCE: BigInteger = BigInteger("200000000000000")
    }
}
