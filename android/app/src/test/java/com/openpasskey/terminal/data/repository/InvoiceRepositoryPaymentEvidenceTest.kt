package com.openpasskey.terminal.data.repository

import com.openpasskey.erc681.NetworkConfigurationException
import com.openpasskey.terminal.chain.ChainConfig
import com.openpasskey.terminal.data.db.InvoiceDao
import com.openpasskey.terminal.data.db.SettlementEventDao
import com.openpasskey.terminal.data.model.Invoice
import com.openpasskey.terminal.data.model.InvoiceStatus
import com.openpasskey.terminal.lifecycle.TerminalLifecycleGate
import com.openpasskey.terminal.payment.PaymentTransactionEvidence
import com.openpasskey.terminal.payment.PaymentTransactionResolver
import com.openpasskey.terminal.rpc.RpcEndpointOverrideState
import com.openpasskey.terminal.rpc.RpcEndpointResolution
import com.openpasskey.terminal.rpc.RpcEndpointResolver
import com.openpasskey.terminal.rpc.RpcEndpointSnapshot
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
        val resolver = PaymentTransactionResolver { _, _ ->
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

            val result = repository(dao, PaymentTransactionResolver { _, _ -> replacement })
                .ensurePaymentEvidence(original.invoiceId)

            assertEquals(updated, result)
        }

    @Test
    fun `unresolvable direct evidence is removed from the printable snapshot`() = runBlocking {
        val invoice = invoice()
        val dao = mock(InvoiceDao::class.java)
        `when`(dao.getById(invoice.invoiceId)).thenReturn(invoice)

        val result = repository(dao, PaymentTransactionResolver { _, _ -> null })
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
                    resolver = PaymentTransactionResolver { _, _ ->
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

        val result = repository(dao, PaymentTransactionResolver { _, _ -> null })
            .ensurePaymentEvidenceAutomatically(invoice.invoiceId)

        val unsupported = result as AutomaticPaymentEvidenceResult.Unsupported
        assertNull(unsupported.invoice?.paymentTxHash)
        assertNull(unsupported.invoice?.paidAt)
    }

    @Test
    fun `manual evidence from a replaced endpoint generation is not persisted`() = runBlocking {
        val invoice = invoice()
        val replacement = evidence(marker = "66", timestamp = 1_704_067_300)
        val dao = mock(InvoiceDao::class.java)
        `when`(dao.getById(invoice.invoiceId)).thenReturn(invoice)
        val endpointResolver = SwitchingRpcEndpointResolver(ENDPOINT_A)
        val result = repository(
            dao = dao,
            resolver = PaymentTransactionResolver { _, resolvedRpcUrl ->
                assertEquals(ENDPOINT_A, resolvedRpcUrl)
                endpointResolver.switchTo(ENDPOINT_B)
                replacement
            },
            endpointResolver = endpointResolver,
        ).ensurePaymentEvidence(invoice.invoiceId)

        assertNull(result?.paymentTxHash)
        assertNull(result?.paymentBlockHash)
        assertFalse(mockingDetails(dao).invocations.any {
            it.method.name == "persistPaymentEvidence"
        })
    }

    @Test
    fun `automatic evidence from a replaced endpoint defers before retrying its new generation`() =
        runBlocking {
            val invoice = invoice().copy(receiptPrintedAt = null)
            val replacement = evidence(marker = "66", timestamp = 1_704_067_300)
            val updated = invoice.copy(
                paymentTxHash = replacement.txHash,
                paymentPayerAddress = replacement.payerAddress,
                paymentBlockNumber = replacement.blockNumber,
                paymentBlockHash = replacement.blockHash,
                paidAt = replacement.blockTimestamp,
            )
            val dao = mock(InvoiceDao::class.java)
            `when`(dao.getById(invoice.invoiceId)).thenReturn(
                invoice,
                invoice,
                invoice,
                invoice,
                updated,
            )
            `when`(
                dao.persistPaymentEvidence(
                    invoiceId = invoice.invoiceId,
                    fundingCursorBlock = requireNotNull(invoice.firstDetectedBlock),
                    fundingCursorHash = requireNotNull(invoice.firstDetectedBlockHash),
                    expectedPaymentTxHash = invoice.paymentTxHash,
                    paymentTxHash = replacement.txHash,
                    paymentPayerAddress = replacement.payerAddress,
                    paymentBlockNumber = replacement.blockNumber,
                    paymentBlockHash = replacement.blockHash,
                    paidAt = replacement.blockTimestamp,
                ),
            ).thenReturn(1)
            val endpointResolver = SwitchingRpcEndpointResolver(ENDPOINT_A)
            var resolverCalls = 0
            val repository = repository(
                dao = dao,
                resolver = PaymentTransactionResolver { _, resolvedRpcUrl ->
                    resolverCalls += 1
                    if (resolverCalls == 1) {
                        assertEquals(ENDPOINT_A, resolvedRpcUrl)
                        endpointResolver.switchTo(ENDPOINT_B)
                    } else {
                        assertEquals(ENDPOINT_B, resolvedRpcUrl)
                    }
                    replacement
                },
                endpointResolver = endpointResolver,
            )

            val staleAttempt = repository.ensurePaymentEvidenceAutomatically(invoice.invoiceId)
            assertSame(AutomaticPaymentEvidenceResult.Deferred, staleAttempt)
            assertFalse(mockingDetails(dao).invocations.any {
                it.method.name == "persistPaymentEvidence"
            })

            val retry = repository.ensurePaymentEvidenceAutomatically(invoice.invoiceId)

            val available = retry as AutomaticPaymentEvidenceResult.Available
            assertEquals(updated, available.invoice)
            assertEquals(1, mockingDetails(dao).invocations.count {
                it.method.name == "persistPaymentEvidence"
            })
        }

    @Test
    fun `automatic null evidence from a replaced endpoint defers then resolves on new generation`() =
        runBlocking {
            val invoice = invoice().copy(receiptPrintedAt = null)
            val replacement = evidence(marker = "66", timestamp = 1_704_067_300)
            val updated = invoice.copy(
                paymentTxHash = replacement.txHash,
                paymentPayerAddress = replacement.payerAddress,
                paymentBlockNumber = replacement.blockNumber,
                paymentBlockHash = replacement.blockHash,
                paidAt = replacement.blockTimestamp,
            )
            val dao = mock(InvoiceDao::class.java)
            `when`(dao.getById(invoice.invoiceId)).thenReturn(
                invoice,
                invoice,
                invoice,
                updated,
            )
            `when`(
                dao.persistPaymentEvidence(
                    invoiceId = invoice.invoiceId,
                    fundingCursorBlock = requireNotNull(invoice.firstDetectedBlock),
                    fundingCursorHash = requireNotNull(invoice.firstDetectedBlockHash),
                    expectedPaymentTxHash = invoice.paymentTxHash,
                    paymentTxHash = replacement.txHash,
                    paymentPayerAddress = replacement.payerAddress,
                    paymentBlockNumber = replacement.blockNumber,
                    paymentBlockHash = replacement.blockHash,
                    paidAt = replacement.blockTimestamp,
                ),
            ).thenReturn(1)
            val endpointResolver = SwitchingRpcEndpointResolver(ENDPOINT_A)
            var resolverCalls = 0
            val repository = repository(
                dao = dao,
                resolver = PaymentTransactionResolver { _, resolvedRpcUrl ->
                    resolverCalls += 1
                    if (resolverCalls == 1) {
                        assertEquals(ENDPOINT_A, resolvedRpcUrl)
                        endpointResolver.switchTo(ENDPOINT_B)
                        null
                    } else {
                        assertEquals(ENDPOINT_B, resolvedRpcUrl)
                        replacement
                    }
                },
                endpointResolver = endpointResolver,
            )

            val staleAttempt = repository.ensurePaymentEvidenceAutomatically(invoice.invoiceId)
            assertSame(AutomaticPaymentEvidenceResult.Deferred, staleAttempt)
            assertFalse(mockingDetails(dao).invocations.any {
                it.method.name == "persistPaymentEvidence"
            })

            val retry = repository.ensurePaymentEvidenceAutomatically(invoice.invoiceId)

            val available = retry as AutomaticPaymentEvidenceResult.Available
            assertEquals(updated, available.invoice)
            assertEquals(1, mockingDetails(dao).invocations.count {
                it.method.name == "persistPaymentEvidence"
            })
        }

    @Test
    fun `automatic configuration failure from replaced endpoint defers then resolves on new generation`() =
        runBlocking {
            val invoice = invoice().copy(receiptPrintedAt = null)
            val replacement = evidence(marker = "66", timestamp = 1_704_067_300)
            val updated = invoice.copy(
                paymentTxHash = replacement.txHash,
                paymentPayerAddress = replacement.payerAddress,
                paymentBlockNumber = replacement.blockNumber,
                paymentBlockHash = replacement.blockHash,
                paidAt = replacement.blockTimestamp,
            )
            val dao = mock(InvoiceDao::class.java)
            `when`(dao.getById(invoice.invoiceId)).thenReturn(
                invoice,
                invoice,
                invoice,
                updated,
            )
            `when`(
                dao.persistPaymentEvidence(
                    invoiceId = invoice.invoiceId,
                    fundingCursorBlock = requireNotNull(invoice.firstDetectedBlock),
                    fundingCursorHash = requireNotNull(invoice.firstDetectedBlockHash),
                    expectedPaymentTxHash = invoice.paymentTxHash,
                    paymentTxHash = replacement.txHash,
                    paymentPayerAddress = replacement.payerAddress,
                    paymentBlockNumber = replacement.blockNumber,
                    paymentBlockHash = replacement.blockHash,
                    paidAt = replacement.blockTimestamp,
                ),
            ).thenReturn(1)
            val endpointResolver = SwitchingRpcEndpointResolver(ENDPOINT_A)
            var resolverCalls = 0
            val repository = repository(
                dao = dao,
                resolver = PaymentTransactionResolver { _, resolvedRpcUrl ->
                    resolverCalls += 1
                    if (resolverCalls == 1) {
                        assertEquals(ENDPOINT_A, resolvedRpcUrl)
                        endpointResolver.switchTo(ENDPOINT_B)
                        throw NetworkConfigurationException("wrong chain")
                    }
                    assertEquals(ENDPOINT_B, resolvedRpcUrl)
                    replacement
                },
                endpointResolver = endpointResolver,
            )

            val staleAttempt = repository.ensurePaymentEvidenceAutomatically(invoice.invoiceId)
            assertSame(AutomaticPaymentEvidenceResult.Deferred, staleAttempt)
            assertFalse(mockingDetails(dao).invocations.any {
                it.method.name == "persistPaymentEvidence"
            })

            val retry = repository.ensurePaymentEvidenceAutomatically(invoice.invoiceId)

            val available = retry as AutomaticPaymentEvidenceResult.Available
            assertEquals(updated, available.invoice)
            assertEquals(1, mockingDetails(dao).invocations.count {
                it.method.name == "persistPaymentEvidence"
            })
        }

    private fun repository(
        dao: InvoiceDao,
        resolver: PaymentTransactionResolver,
        coordinator: RpcWorkCoordinator = RpcWorkCoordinator(),
        endpointResolver: RpcEndpointResolver = RpcEndpointResolver.PASSTHROUGH,
    ) = InvoiceRepository(
        invoiceDao = dao,
        settlementEventDao = mock(SettlementEventDao::class.java),
        chainConfig = mock(ChainConfig::class.java),
        operatorWalletStore = mock(OperatorWalletStore::class.java),
        lifecycleGate = TerminalLifecycleGate(),
        rpcWorkCoordinator = coordinator,
        rpcEndpointResolver = endpointResolver,
        paymentTransactionResolver = resolver,
    )

    private class SwitchingRpcEndpointResolver(initialEndpoint: String) : RpcEndpointResolver {
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

    private companion object {
        const val ENDPOINT_A = "https://rpc-a.example"
        const val ENDPOINT_B = "https://rpc-b.example"
    }
}
