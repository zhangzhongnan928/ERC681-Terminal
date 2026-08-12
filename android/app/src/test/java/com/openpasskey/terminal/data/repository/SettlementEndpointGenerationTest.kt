package com.openpasskey.terminal.data.repository

import androidx.room.DatabaseConfiguration
import androidx.room.InvalidationTracker
import androidx.sqlite.db.SupportSQLiteOpenHelper
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.web3j.crypto.RawTransaction
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.math.BigInteger
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong

class SettlementEndpointGenerationTest {
    @Test
    fun `endpoint rotation forces fresh historical proof and gas estimate on new endpoint`() =
        runBlocking {
            val invoice = confirmedInvoice()
            val resolver = SwitchingResolver(ENDPOINT_A)
            val clock = AtomicLong(100_000)
            val historicalEndpoints = mutableListOf<String>()
            val preflightEndpoints = mutableListOf<String>()
            val includeGasEstimate = mutableListOf<Boolean>()
            val repository = SettlementRepository(
                database = fakeDatabase(invoice),
                walletAccess = ReadyWallet,
                chainConfigSnapshot = ::unprovisionedConfig,
                lifecycleGate = TerminalLifecycleGate(),
                rpcWorkCoordinator = RpcWorkCoordinator(),
                rpcEndpointResolver = resolver,
                clientFactory = { endpoint ->
                    preflightEndpoints += endpoint
                    preflightClient { includeGas ->
                        includeGasEstimate += includeGas
                    }
                },
                gson = Gson(),
                elapsedRealtimeMillis = clock::get,
                historicalSnapshotValidationOverride = { _, endpoint ->
                    historicalEndpoints += endpoint
                },
            )

            val reviewed = repository.prepare(listOf(invoice.invoiceId))
            assertEquals(0L, reviewed.rpcEndpointGeneration)
            assertEquals(RPC_URL, reviewed.rpcUrl)
            assertEquals(1, resolver.resolveCurrentCalls)

            resolver.switchTo(ENDPOINT_B)
            clock.set(110_000)
            val refreshed = repository.prepareForAuthentication(reviewed)

            assertEquals(1L, refreshed.rpcEndpointGeneration)
            assertNotEquals(reviewed.rpcEndpointGeneration, refreshed.rpcEndpointGeneration)
            assertEquals(RPC_URL, refreshed.rpcUrl)
            assertEquals(listOf(ENDPOINT_A, ENDPOINT_B), historicalEndpoints)
            assertEquals(listOf(ENDPOINT_A, ENDPOINT_B), preflightEndpoints)
            assertEquals(listOf(true, true), includeGasEstimate)
            assertEquals(2, resolver.resolveCurrentCalls)
            assertTrue(
                refreshed.historicalProofAtElapsedRealtimeMillis >
                    reviewed.historicalProofAtElapsedRealtimeMillis,
            )
            assertTrue(
                refreshed.gasEstimateAtElapsedRealtimeMillis >
                    reviewed.gasEstimateAtElapsedRealtimeMillis,
            )
        }

    @Test
    fun `historical proof is reused across prepares on the same endpoint generation`() =
        runBlocking {
            val invoice = confirmedInvoice()
            val resolver = SwitchingResolver(ENDPOINT_A)
            val clock = AtomicLong(100_000)
            val historicalEndpoints = mutableListOf<String>()
            val repository = SettlementRepository(
                database = fakeDatabase(invoice),
                walletAccess = ReadyWallet,
                chainConfigSnapshot = ::unprovisionedConfig,
                lifecycleGate = TerminalLifecycleGate(),
                rpcWorkCoordinator = RpcWorkCoordinator(),
                rpcEndpointResolver = resolver,
                clientFactory = { preflightClient { } },
                gson = Gson(),
                elapsedRealtimeMillis = clock::get,
                historicalSnapshotValidationOverride = { _, endpoint ->
                    historicalEndpoints += endpoint
                },
            )

            val reviewed = repository.prepare(listOf(invoice.invoiceId))
            // Far beyond the historical TTL: generation identity, not wall-clock age, bounds
            // reuse of the immutable-pin validation within one process.
            clock.set(500_000)
            val again = repository.prepare(listOf(invoice.invoiceId))

            assertEquals(listOf(ENDPOINT_A), historicalEndpoints)
            assertEquals(reviewed.rpcEndpointGeneration, again.rpcEndpointGeneration)
            assertEquals(500_000L, again.historicalProofAtElapsedRealtimeMillis)

            resolver.switchTo(ENDPOINT_B)
            val rotated = repository.prepare(listOf(invoice.invoiceId))

            assertEquals(listOf(ENDPOINT_A, ENDPOINT_B), historicalEndpoints)
            assertEquals(1L, rotated.rpcEndpointGeneration)
        }

    private fun preflightClient(onPreflight: (Boolean) -> Unit): SettlementChainClient =
        proxy { method, arguments ->
            when (method.name) {
                "settlementPreflight" -> {
                    val includeGas = arguments?.get(1) as Boolean
                    onPreflight(includeGas)
                    SettlementPreflightSnapshot(
                        chainId = CHAIN_ID,
                        ownerAddress = null,
                        operatorListed = true,
                        canonicalBlockHashes = listOf(CONFIRMATION_HASH),
                        canonicalBlockHashesAfter = listOf(CONFIRMATION_HASH),
                        receiverBalances = listOf(BigInteger.ONE),
                        nonce = BigInteger.ZERO,
                        gasLimit = if (includeGas) GAS_LIMIT else null,
                        feeQuote = FEE_QUOTE,
                        nativeBalance = LIVE_NATIVE_BALANCE,
                    )
                }
                "close" -> Unit
                else -> error("Unexpected settlement RPC call ${method.name}")
            }
        }

    private fun fakeDatabase(invoice: Invoice): InvoiceDatabase {
        val invoices = proxy<InvoiceDao> { method, _ ->
            when (method.name) {
                "getByIds" -> listOf(invoice)
                else -> error("Unexpected InvoiceDao call ${method.name}")
            }
        }
        val settlements = proxy<SettlementDao> { method, _ ->
            when (method.name) {
                "getActiveForOperator" -> emptyList<Any>()
                else -> error("Unexpected SettlementDao call ${method.name}")
            }
        }
        val events = proxy<SettlementEventDao> { method, _ ->
            when (method.name) {
                "getByInvoiceScope" -> emptyList<Any>()
                else -> error("Unexpected SettlementEventDao call ${method.name}")
            }
        }
        return TestInvoiceDatabase(invoices, settlements, events)
    }

    private class TestInvoiceDatabase(
        private val invoices: InvoiceDao,
        private val settlements: SettlementDao,
        private val events: SettlementEventDao,
    ) : InvoiceDatabase() {
        override fun invoiceDao(): InvoiceDao = invoices
        override fun settlementDao(): SettlementDao = settlements
        override fun settlementEventDao(): SettlementEventDao = events
        override val transactionExecutor: Executor = Executor(Runnable::run)

        @Suppress("OVERRIDE_DEPRECATION")
        override fun beginTransaction() = Unit
        @Suppress("OVERRIDE_DEPRECATION")
        override fun setTransactionSuccessful() = Unit
        @Suppress("OVERRIDE_DEPRECATION")
        override fun endTransaction() = Unit

        override fun createOpenHelper(config: DatabaseConfiguration): SupportSQLiteOpenHelper =
            error("No SQLite database is used by this test")

        override fun createInvalidationTracker(): InvalidationTracker = InvalidationTracker(this)
        override fun clearAllTables() = Unit
    }

    private class SwitchingResolver(initialEndpoint: String) : RpcEndpointResolver {
        private var endpoint = initialEndpoint
        private var generation = 0L
        var resolveCurrentCalls = 0
            private set

        override fun snapshot(chainId: Long): RpcEndpointSnapshot = RpcEndpointSnapshot(
            chainId = chainId,
            state = RpcEndpointOverrideState.READY,
            providerLabel = "Test provider",
        )

        @Synchronized
        override fun resolve(chainId: Long, fallbackUrl: String): String = endpoint

        @Synchronized
        override fun resolveCurrent(chainId: Long, fallbackUrl: String): RpcEndpointResolution {
            resolveCurrentCalls += 1
            return RpcEndpointResolution(chainId, endpoint, generation)
        }

        @Synchronized
        override fun isCurrent(resolution: RpcEndpointResolution): Boolean =
            resolution.generation == generation

        @Synchronized
        fun switchTo(nextEndpoint: String) {
            endpoint = nextEndpoint
            generation += 1
        }
    }

    private object ReadyWallet : SettlementWalletAccess {
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
        ): ByteArray = error("Signing is not expected")
    }

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

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> proxy(
        crossinline handler: (Method, Array<out Any?>?) -> Any?,
    ): T = Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java),
    ) { proxy, method, arguments ->
        when (method.name) {
            "toString" -> "${T::class.java.simpleName}Fake"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === arguments?.firstOrNull()
            else -> handler(method, arguments)
        }
    } as T

    private companion object {
        const val CHAIN_ID = 84_532L
        const val NETWORK_NAME = "Base Sepolia"
        const val RPC_URL = "https://sepolia.base.org"
        const val ENDPOINT_A = "https://rpc-a.example/credential-a"
        const val ENDPOINT_B = "https://rpc-b.example/credential-b"
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
        val GAS_LIMIT: BigInteger = BigInteger.valueOf(100_000)
        val FEE_QUOTE = SettlementFeeQuote(
            mode = SettlementFeeMode.LEGACY,
            gasPrice = BigInteger.ONE,
        )
        val LIVE_NATIVE_BALANCE: BigInteger =
            SettlementBalancePolicy.requirement(GAS_LIMIT, FEE_QUOTE).requiredBalance
    }
}
