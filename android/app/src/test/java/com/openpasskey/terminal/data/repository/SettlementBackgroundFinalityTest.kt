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
import com.openpasskey.terminal.data.model.SettlementTransaction
import com.openpasskey.terminal.data.model.SettlementTransactionStatus
import com.openpasskey.terminal.lifecycle.TerminalLifecycleGate
import com.openpasskey.terminal.rpc.RpcWorkCoordinator
import com.openpasskey.terminal.settlement.SettlementAbi
import com.openpasskey.terminal.settlement.SettlementChainClient
import com.openpasskey.terminal.settlement.SettlementReceipt
import com.openpasskey.terminal.settlement.SettlementReceiptLog
import com.openpasskey.terminal.settlement.SettlementRecoverySnapshot
import com.openpasskey.terminal.wallet.OperatorWalletSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.web3j.crypto.RawTransaction
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.Executor

class SettlementBackgroundFinalityTest {
    @Test
    fun `regressed ordered head keeps canonical receipt confirming`() = runBlocking {
        val harness = RecoveryHarness { FINALITY_TARGET - 1 }

        harness.repository.recoverPending()

        assertEquals(1, harness.snapshotCalls)
        assertEquals(1, harness.finalHeadCalls)
        assertEquals(listOf("snapshot", "finalHead"), harness.rpcCalls)
        assertEquals(SettlementTransactionStatus.CONFIRMING, harness.transaction.status)
        assertEquals(RECEIPT_BLOCK, harness.transaction.receiptBlock)
        assertTrue(harness.transaction.error.orEmpty().contains("Confirmation depth changed"))
        assertEquals(0, harness.releaseSettlementCalls)
        assertEquals(0, harness.markSettledCalls)
        assertEquals(0, harness.insertEventCalls)
    }

    @Test
    fun `failed ordered head keeps canonical receipt confirming`() = runBlocking {
        val harness = RecoveryHarness { error("final head unavailable") }

        harness.repository.recoverPending()

        assertEquals(1, harness.snapshotCalls)
        assertEquals(1, harness.finalHeadCalls)
        assertEquals(listOf("snapshot", "finalHead"), harness.rpcCalls)
        assertEquals(SettlementTransactionStatus.CONFIRMING, harness.transaction.status)
        assertEquals(RECEIPT_BLOCK, harness.transaction.receiptBlock)
        assertTrue(harness.transaction.error.orEmpty().contains("final head unavailable"))
        assertEquals(0, harness.releaseSettlementCalls)
        assertEquals(0, harness.markSettledCalls)
        assertEquals(0, harness.insertEventCalls)
    }

    @Test
    fun `passing ordered head permits verified terminal recovery transition`() = runBlocking {
        val harness = RecoveryHarness { FINALITY_TARGET }

        harness.repository.recoverPending()

        assertEquals(1, harness.snapshotCalls)
        assertEquals(1, harness.finalHeadCalls)
        assertEquals(listOf("snapshot", "finalHead"), harness.rpcCalls)
        assertEquals(SettlementTransactionStatus.VERIFIED, harness.transaction.status)
        assertEquals(RECEIPT_BLOCK, harness.transaction.receiptBlock)
        assertNull(harness.transaction.signedRawTransaction)
        assertEquals(0, harness.releaseSettlementCalls)
        assertEquals(1, harness.markSettledCalls)
        assertEquals(1, harness.insertEventCalls)
    }

    private class RecoveryHarness(
        private val finalHead: () -> Long,
    ) {
        var transaction = confirmingTransaction()
        var snapshotCalls = 0
        var finalHeadCalls = 0
        val rpcCalls = mutableListOf<String>()
        var releaseSettlementCalls = 0
        var markSettledCalls = 0
        var insertEventCalls = 0

        private val invoiceDao = proxy<InvoiceDao> { method, _ ->
            when (method.name) {
                "getByIds" -> listOf(confirmedInvoice())
                "releaseSettlement" -> {
                    releaseSettlementCalls += 1
                    Unit
                }
                "markSettled" -> {
                    markSettledCalls += 1
                    Unit
                }
                else -> error("Recovery unexpectedly called InvoiceDao.${method.name}")
            }
        }
        private val settlementDao = proxy<SettlementDao> { method, arguments ->
            when (method.name) {
                "getByStatuses" -> listOf(transaction)
                "getById" -> transaction
                "update" -> {
                    transaction = arguments?.get(0) as SettlementTransaction
                    Unit
                }
                else -> error("Recovery unexpectedly called SettlementDao.${method.name}")
            }
        }
        private val eventDao = proxy<SettlementEventDao> { method, _ ->
            when (method.name) {
                "getByInvoiceScope" -> emptyList<Any>()
                "insertAll" -> {
                    insertEventCalls += 1
                    Unit
                }
                else -> error("Recovery unexpectedly called SettlementEventDao.${method.name}")
            }
        }
        private val database = TestInvoiceDatabase(invoiceDao, settlementDao, eventDao)
        private val client = proxy<SettlementChainClient> { method, _ ->
            when (method.name) {
                "settlementRecoverySnapshot" -> {
                    snapshotCalls += 1
                    rpcCalls += "snapshot"
                    SettlementRecoverySnapshot(
                        receipt = successfulReceipt(),
                        canonicalReceiptBlockHash = BLOCK_HASH,
                        latestBlockNumber = FINALITY_TARGET,
                    )
                }
                "blockNumber" -> {
                    finalHeadCalls += 1
                    rpcCalls += "finalHead"
                    finalHead()
                }
                "close" -> Unit
                else -> error("Recovery unexpectedly called SettlementChainClient.${method.name}")
            }
        }

        val repository = SettlementRepository(
            database = database,
            walletAccess = UnusedWalletAccess,
            chainConfigSnapshot = ::unprovisionedConfig,
            lifecycleGate = TerminalLifecycleGate(),
            rpcWorkCoordinator = RpcWorkCoordinator(),
            clientFactory = { client },
            gson = Gson(),
        )
    }

    @Suppress("OVERRIDE_DEPRECATION")
    private class TestInvoiceDatabase(
        private val invoices: InvoiceDao,
        private val settlements: SettlementDao,
        private val events: SettlementEventDao,
    ) : InvoiceDatabase() {
        override fun invoiceDao(): InvoiceDao = invoices
        override fun settlementDao(): SettlementDao = settlements
        override fun settlementEventDao(): SettlementEventDao = events
        override val transactionExecutor: Executor = Executor(Runnable::run)

        override fun beginTransaction() = Unit

        override fun setTransactionSuccessful() = Unit

        override fun endTransaction() = Unit

        override fun createOpenHelper(config: DatabaseConfiguration): SupportSQLiteOpenHelper =
            error("No SQLite database is used by this repository test")

        override fun createInvalidationTracker(): InvalidationTracker = InvalidationTracker(this)

        override fun clearAllTables() = Unit
    }

    private object UnusedWalletAccess : SettlementWalletAccess {
        override fun snapshot(): OperatorWalletSnapshot = error("Wallet must not be read")

        override fun activateAndSignSettlementTransaction(
            transaction: RawTransaction,
            chainId: Long,
            vaultAddress: String,
            operatorAddress: String,
            eip1559: Boolean,
        ): ByteArray = error("Wallet must not be activated")
    }

    private companion object {
        const val CHAIN_ID = 84532L
        const val RECEIPT_BLOCK = 100L
        const val REQUIRED_CONFIRMATIONS = 2
        const val FINALITY_TARGET = RECEIPT_BLOCK + REQUIRED_CONFIRMATIONS - 1
        const val RPC_URL = "https://sepolia.base.org"
        const val TRANSACTION_ID = "settlement-1"
        const val INVOICE_ID =
            "0x294d3b9eb0136d18f2a3e8aa9c10224029893c244e822a15902256d778f7f729"
        const val TX_HASH =
            "0x1111111111111111111111111111111111111111111111111111111111111111"
        const val BLOCK_HASH =
            "0x2222222222222222222222222222222222222222222222222222222222222222"
        const val VAULT = "0x1ed67e540e6ab92dc3537a7bba3bcab6fdd69da1"
        const val TOKEN = "0x7ffba642bc902880a737cb1c18a4e9540879e211"
        const val RECEIVER = "0x9107decd2cb06c57c40a663648e19cde1d52f606"

        fun confirmingTransaction() = SettlementTransaction(
            id = TRANSACTION_ID,
            chainId = CHAIN_ID,
            networkName = "Base Sepolia",
            rpcUrl = RPC_URL,
            vaultAddress = VAULT,
            tokenAddress = TOKEN,
            tokenSymbol = "AUD",
            operatorAddress = "0x5555555555555555555555555555555555555555",
            invoiceIdsJson = "[\"$INVOICE_ID\"]",
            expectedAmountsJson = "[\"1\"]",
            receiverAddressesJson = "[\"$RECEIVER\"]",
            requiredConfirmations = REQUIRED_CONFIRMATIONS,
            callData = "0x682b11b5",
            nonce = "0",
            gasLimit = "100000",
            feeMode = SettlementFeeMode.LEGACY,
            gasPrice = "1",
            maxPriorityFeePerGas = null,
            maxFeePerGas = null,
            maxGasCostWei = "100000",
            feeReserveWei = "100000000000000",
            requiredBalanceWei = "100000000100000",
            txHash = TX_HASH,
            signedRawTransaction = "0xdeadbeef",
            status = SettlementTransactionStatus.CONFIRMING,
            receiptBlock = RECEIPT_BLOCK,
            createdAt = 1,
            updatedAt = 1,
        )

        fun successfulReceipt() = SettlementReceipt(
            successful = true,
            blockNumber = RECEIPT_BLOCK,
            blockHash = BLOCK_HASH,
            transactionHash = TX_HASH,
            logs = listOf(
                SettlementReceiptLog(
                    address = VAULT,
                    topics = listOf(
                        SettlementAbi.sweptTopic,
                        addressWord(RECEIVER),
                        addressWord(VAULT),
                    ),
                    data = "0x" + INVOICE_ID.removePrefix("0x") + addressWord(TOKEN).removePrefix("0x") +
                        uint256Word(1) + uint256Word(1) + uint256Word(0),
                    transactionHash = TX_HASH,
                    blockHash = BLOCK_HASH,
                    logIndex = 0,
                ),
            ),
        )

        fun confirmedInvoice() = Invoice(
            invoiceId = INVOICE_ID,
            receiver = RECEIVER,
            token = TOKEN,
            tokenSymbol = "AUD",
            expectedAmount = "1",
            receivedAmount = "1",
            status = InvoiceStatus.PAID,
            createdAt = 1,
            chainId = CHAIN_ID,
            networkName = "Base Sepolia",
            rpcUrl = RPC_URL,
            vaultAddress = VAULT,
            confirmedAtBlock = FINALITY_TARGET,
            settlementId = TRANSACTION_ID,
        )

        fun addressWord(address: String): String =
            "0x" + address.removePrefix("0x").padStart(64, '0')

        fun uint256Word(value: Long): String = value.toString(16).padStart(64, '0')

        fun unprovisionedConfig() = TerminalConfigSnapshot(
            networkName = "Base Sepolia",
            rpcUrl = RPC_URL,
            chainId = CHAIN_ID,
            factoryAddress = "",
            receiverImplementationAddress = "",
            vaultAddress = "",
            confirmationBlocks = REQUIRED_CONFIRMATIONS,
            paymentTokens = emptyList(),
            protocolVersion = "",
            provisionedOperatorAddress = null,
            provisioned = false,
        )

        inline fun <reified T> proxy(
            crossinline handler: (Method, Array<out Any?>?) -> Any?,
        ): T = Proxy.newProxyInstance(
            T::class.java.classLoader,
            arrayOf(T::class.java),
        ) { _, method, arguments -> handler(method, arguments) } as T
    }
}
