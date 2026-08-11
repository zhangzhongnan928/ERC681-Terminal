package com.openpasskey.terminal.data.repository

import com.openpasskey.terminal.chain.ChainConfig
import com.openpasskey.terminal.data.db.InvoiceDao
import com.openpasskey.terminal.data.db.SettlementEventDao
import com.openpasskey.terminal.data.model.Invoice
import com.openpasskey.terminal.data.model.InvoiceStatus
import com.openpasskey.terminal.lifecycle.TerminalLifecycleGate
import com.openpasskey.terminal.payment.PaymentTransactionEvidence
import com.openpasskey.terminal.payment.PaymentTransactionResolver
import com.openpasskey.terminal.rpc.RpcWorkCoordinator
import com.openpasskey.terminal.wallet.OperatorWalletStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.`when`

class InvoiceRepositoryPaymentEvidenceTest {
    @Test
    fun `stored incoming evidence is resolved again before it can be printed`() = runBlocking {
        val invoice = invoice()
        val dao = mock(InvoiceDao::class.java)
        `when`(dao.getById(invoice.invoiceId)).thenReturn(invoice)
        var resolverCalls = 0
        val resolver = PaymentTransactionResolver {
            resolverCalls += 1
            evidence()
        }

        val result = repository(dao, resolver).ensurePaymentEvidence(invoice.invoiceId)

        assertEquals(1, resolverCalls)
        assertEquals(invoice, result)
        assertFalse(mockingDetails(dao).invocations.any {
            it.method.name == "persistPaymentEvidence"
        })
    }

    @Test
    fun `a canonical replacement atomically replaces stale evidence for the same cursor`() =
        runBlocking {
            val original = invoice()
            val replacement = evidence(marker = "66", timestamp = 1_704_067_300)
            val updated = original.copy(
                paymentTxHash = replacement.txHash,
                paymentPayerAddress = replacement.payerAddress,
                paymentBlockNumber = replacement.blockNumber,
                paymentBlockHash = replacement.blockHash,
                paidAt = replacement.blockTimestamp,
                receiptPrintedAt = null,
            )
            val dao = mock(InvoiceDao::class.java)
            `when`(dao.getById(original.invoiceId)).thenReturn(original, original, updated)
            `when`(
                dao.persistPaymentEvidence(
                    invoiceId = original.invoiceId,
                    fundingCursorBlock = requireNotNull(original.firstDetectedBlock),
                    fundingCursorHash = requireNotNull(original.firstDetectedBlockHash),
                    expectedPaymentTxHash = original.paymentTxHash,
                    paymentTxHash = replacement.txHash,
                    paymentPayerAddress = replacement.payerAddress,
                    paymentBlockNumber = replacement.blockNumber,
                    paymentBlockHash = replacement.blockHash,
                    paidAt = replacement.blockTimestamp,
                )
            ).thenReturn(1)

            val result = repository(dao, PaymentTransactionResolver { replacement })
                .ensurePaymentEvidence(original.invoiceId)

            assertEquals(updated, result)
        }

    @Test
    fun `unresolvable direct evidence is removed from the printable snapshot`() = runBlocking {
        val invoice = invoice()
        val dao = mock(InvoiceDao::class.java)
        `when`(dao.getById(invoice.invoiceId)).thenReturn(invoice)

        val result = repository(dao, PaymentTransactionResolver { null })
            .ensurePaymentEvidence(invoice.invoiceId)

        assertNull(result?.paymentTxHash)
        assertNull(result?.paymentBlockHash)
        assertNull(result?.paidAt)
    }

    @Test
    fun `automatic evidence recovery defers without entering an interactive rpc window`() =
        runBlocking {
            val invoice = invoice().copy(receiptPrintedAt = null)
            val dao = mock(InvoiceDao::class.java)
            `when`(dao.getById(invoice.invoiceId)).thenReturn(invoice)
            var resolverCalls = 0
            val coordinator = RpcWorkCoordinator()
            val reservation = coordinator.reserveInteractiveWindow()
            try {
                val result = repository(
                    dao = dao,
                    resolver = PaymentTransactionResolver {
                        resolverCalls += 1
                        evidence()
                    },
                    coordinator = coordinator,
                ).ensurePaymentEvidenceAutomatically(invoice.invoiceId)

                assertSame(AutomaticPaymentEvidenceResult.Deferred, result)
                assertEquals(0, resolverCalls)
            } finally {
                reservation.close()
            }
        }

    @Test
    fun `unsupported automatic evidence is classified once for caller suppression`() = runBlocking {
        val invoice = invoice().copy(receiptPrintedAt = null)
        val dao = mock(InvoiceDao::class.java)
        `when`(dao.getById(invoice.invoiceId)).thenReturn(invoice)

        val result = repository(dao, PaymentTransactionResolver { null })
            .ensurePaymentEvidenceAutomatically(invoice.invoiceId)

        val unsupported = result as AutomaticPaymentEvidenceResult.Unsupported
        assertNull(unsupported.invoice?.paymentTxHash)
        assertNull(unsupported.invoice?.paidAt)
    }

    private fun repository(
        dao: InvoiceDao,
        resolver: PaymentTransactionResolver,
        coordinator: RpcWorkCoordinator = RpcWorkCoordinator(),
    ) = InvoiceRepository(
        invoiceDao = dao,
        settlementEventDao = mock(SettlementEventDao::class.java),
        chainConfig = mock(ChainConfig::class.java),
        operatorWalletStore = mock(OperatorWalletStore::class.java),
        lifecycleGate = TerminalLifecycleGate(),
        rpcWorkCoordinator = coordinator,
        paymentTransactionResolver = resolver,
    )

    private fun invoice(): Invoice {
        val evidence = evidence()
        return Invoice(
            invoiceId = "0x" + "01".repeat(32),
            receiver = "0x" + "02".repeat(20),
            operatorAddress = "0x" + "03".repeat(20),
            token = "0x" + "04".repeat(20),
            tokenSymbol = "AUDD",
            tokenDecimals = 6,
            expectedAmount = "12340000",
            receivedAmount = "12340000",
            status = InvoiceStatus.PAID,
            createdAt = 1,
            chainId = 84532,
            networkName = "Base Sepolia",
            rpcUrl = "https://sepolia.base.org",
            factoryAddress = "0x" + "05".repeat(20),
            receiverImplementationAddress = "0x" + "06".repeat(20),
            vaultAddress = "0x" + "07".repeat(20),
            confirmationBlocks = 1,
            erc681Uri = "ethereum:test",
            publishedAtBlock = 10,
            publishedAtBlockHash = "0x" + "08".repeat(32),
            firstDetectedBlock = 12,
            firstDetectedBlockHash = "0x" + "09".repeat(32),
            lastObservedBlock = 12,
            confirmedAtBlock = 12,
            paymentTxHash = evidence.txHash,
            paymentPayerAddress = evidence.payerAddress,
            paymentBlockNumber = evidence.blockNumber,
            paymentBlockHash = evidence.blockHash,
            paidAt = evidence.blockTimestamp,
            receiptNumber = 9,
            receiptAutoPrintEligible = true,
            receiptPrintedAt = 1_704_067_210,
        )
    }

    private fun evidence(
        marker: String = "ab",
        timestamp: Long = 1_704_067_200,
    ) = PaymentTransactionEvidence(
        txHash = "0x" + marker.repeat(32),
        payerAddress = "0x" + "0a".repeat(20),
        blockNumber = 11,
        blockHash = "0x" + marker.repeat(32),
        blockTimestamp = timestamp,
    )
}
