package com.openpasskey.terminal.data.repository

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.openpasskey.erc681.NetworkConfigurationException
import com.openpasskey.terminal.chain.ChainConfig
import com.openpasskey.terminal.data.db.InvoiceDao
import com.openpasskey.terminal.data.db.SettlementEventDao
import com.openpasskey.terminal.data.model.Invoice
import com.openpasskey.terminal.data.model.InvoiceStatus
import com.openpasskey.terminal.lifecycle.TerminalLifecycleGate
import com.openpasskey.terminal.rpc.RpcWorkCoordinator
import com.openpasskey.terminal.wallet.OperatorWalletStore
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.Mockito.mock
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.ServerSocket
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class InvoiceRepositoryMonitorIntegrationTest {
    @Test
    fun `transient transport failure is retried and next observation is persisted`() = runBlocking {
        val requestCount = AtomicInteger()
        rpcServer { requestBody ->
            if (requestCount.incrementAndGet() == 1) {
                "not-json"
            } else {
                successfulObservationResponse(requestBody, remoteChainId = CHAIN_ID)
            }
        }.use { server ->
            val fixture = repositoryFixture(invoice(server.url))

            val persisted = fixture.repository.observePayment(INVOICE_ID)
                .drop(1)
                .first { it.receivedAmount == EXPECTED_AMOUNT }

            assertEquals(4, requestCount.get())
            assertEquals(1, fixture.observationWrites.get())
            assertEquals(EXPECTED_AMOUNT, persisted.receivedAmount)
            assertEquals(InvoiceStatus.CONFIRMING, persisted.status)
            assertEquals(BLOCK_NUMBER, persisted.firstDetectedBlock)
            assertEquals(BLOCK_HASH, persisted.firstDetectedBlockHash)
            assertEquals(BLOCK_NUMBER, persisted.lastObservedBlock)
            assertEquals(persisted, fixture.invoice.get())
        }
    }

    @Test
    fun `wrong chain is terminal at repository boundary and is not retried`() {
        val requestCount = AtomicInteger()
        rpcServer { requestBody ->
            requestCount.incrementAndGet()
            successfulObservationResponse(requestBody, remoteChainId = 1)
        }.use { server ->
            val fixture = repositoryFixture(invoice(server.url))

            assertThrows(NetworkConfigurationException::class.java) {
                runBlocking {
                    fixture.repository.observePayment(INVOICE_ID).drop(1).first()
                }
            }

            assertEquals(1, requestCount.get())
            assertEquals(0, fixture.observationWrites.get())
            assertEquals(InvoiceStatus.WAITING, fixture.invoice.get().status)
        }
    }

    private fun repositoryFixture(initial: Invoice): RepositoryFixture {
        val invoice = AtomicReference(initial)
        val observationWrites = AtomicInteger()
        val invoiceDao = proxy<InvoiceDao> { method, args ->
            when (method) {
                "getById" -> invoice.get().takeIf { it.invoiceId == args[0] }
                "updateObservation" -> {
                    val current = invoice.get()
                    if (current.invoiceId != args[0] || current.status !in OPEN_STATUSES) {
                        0
                    } else {
                        val updated = current.copy(
                            receivedAmount = args[1] as String,
                            status = args[2] as InvoiceStatus,
                            firstDetectedBlock = args[3] as Long?,
                            firstDetectedBlockHash = args[4] as String?,
                            lastObservedBlock = args[5] as Long?,
                            confirmedAtBlock = args[6] as Long?,
                        )
                        invoice.set(updated)
                        observationWrites.incrementAndGet()
                        1
                    }
                }
                else -> error("Unexpected InvoiceDao call: $method")
            }
        }
        val settlementEventDao = proxy<SettlementEventDao> { method, _ ->
            error("Unexpected SettlementEventDao call: $method")
        }
        return RepositoryFixture(
            repository = InvoiceRepository(
                invoiceDao = invoiceDao,
                settlementEventDao = settlementEventDao,
                chainConfig = mock(ChainConfig::class.java),
                operatorWalletStore = mock(OperatorWalletStore::class.java),
                lifecycleGate = TerminalLifecycleGate(),
                rpcWorkCoordinator = RpcWorkCoordinator(),
            ),
            invoice = invoice,
            observationWrites = observationWrites,
        )
    }

    private fun invoice(rpcUrl: String) = Invoice(
        invoiceId = INVOICE_ID,
        receiver = RECEIVER,
        operatorAddress = OPERATOR,
        token = TOKEN,
        tokenSymbol = "AUD",
        tokenDecimals = 18,
        expectedAmount = EXPECTED_AMOUNT,
        status = InvoiceStatus.WAITING,
        createdAt = 1,
        chainId = CHAIN_ID,
        networkName = "Base Sepolia",
        rpcUrl = rpcUrl,
        factoryAddress = FACTORY,
        receiverImplementationAddress = IMPLEMENTATION,
        vaultAddress = VAULT,
        confirmationBlocks = 2,
        erc681Uri = "ethereum:$RECEIVER@$CHAIN_ID/transfer?address=$TOKEN&uint256=$EXPECTED_AMOUNT",
    )

    private fun successfulObservationResponse(requestBody: String, remoteChainId: Long): String {
        val root = JsonParser.parseString(requestBody)
        val requests = if (root.isJsonArray) root.asJsonArray.toList() else listOf(root)
        val responses = requests.map { element ->
            val request = element.asJsonObject
            val method = request.get("method").asString
            val result = when (method) {
                "eth_chainId" -> "0x${remoteChainId.toString(16)}"
                "eth_getBlockByNumber" -> blockResult()
                "eth_call" -> "0x${EXPECTED_AMOUNT.toBigInteger().toString(16).padStart(64, '0')}"
                else -> error("Unexpected RPC method: $method")
            }
            JsonObject().apply {
                addProperty("jsonrpc", "2.0")
                add("id", request.get("id"))
                if (result is JsonObject) add("result", result) else addProperty("result", result as String)
            }
        }
        return if (root.isJsonArray) JsonArray().apply { responses.forEach(::add) }.toString()
        else responses.single().toString()
    }

    private fun blockResult() = JsonObject().apply {
        addProperty("number", "0x${BLOCK_NUMBER.toString(16)}")
        addProperty("hash", BLOCK_HASH)
    }

    private fun rpcServer(response: (String) -> String): LocalRpcServer {
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress("127.0.0.1", 0))
        return LocalRpcServer(server, response)
    }

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> proxy(
        crossinline handler: (String, Array<out Any?>) -> Any?,
    ): T = Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java),
        InvocationHandler { proxy, method, arguments ->
            when (method.name) {
                "toString" -> "${T::class.java.simpleName}Fake"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.firstOrNull()
                else -> handler(method.name, arguments.orEmpty())
            }
        },
    ) as T

    private data class RepositoryFixture(
        val repository: InvoiceRepository,
        val invoice: AtomicReference<Invoice>,
        val observationWrites: AtomicInteger,
    )

    private class LocalRpcServer(
        private val server: ServerSocket,
        private val response: (String) -> String,
    ) : AutoCloseable {
        private val executor = Executors.newSingleThreadExecutor()
        private val closed = AtomicBoolean()
        private val failure = AtomicReference<Throwable?>()
        val url: String = "http://127.0.0.1:${server.localPort}/"

        init {
            executor.execute {
                while (!closed.get()) {
                    try {
                        server.accept().use { socket ->
                            val input = BufferedInputStream(socket.getInputStream())
                            val output = BufferedOutputStream(socket.getOutputStream())
                            readAsciiLine(input) // request line
                            var contentLength = 0
                            while (true) {
                                val line = readAsciiLine(input)
                                if (line.isEmpty()) break
                                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                                    contentLength = line.substringAfter(':').trim().toInt()
                                }
                            }
                            val requestBytes = ByteArray(contentLength)
                            var offset = 0
                            while (offset < contentLength) {
                                val count = input.read(requestBytes, offset, contentLength - offset)
                                check(count >= 0) { "RPC test request ended before its declared body" }
                                offset += count
                            }
                            val responseBytes = response(
                                String(requestBytes, StandardCharsets.UTF_8),
                            ).toByteArray(StandardCharsets.UTF_8)
                            output.write(
                                (
                                    "HTTP/1.1 200 OK\r\n" +
                                        "Content-Type: application/json\r\n" +
                                        "Content-Length: ${responseBytes.size}\r\n" +
                                        "Connection: close\r\n\r\n"
                                    ).toByteArray(StandardCharsets.US_ASCII),
                            )
                            output.write(responseBytes)
                            output.flush()
                        }
                    } catch (error: Throwable) {
                        if (!closed.get()) failure.compareAndSet(null, error)
                    }
                }
            }
        }

        override fun close() {
            closed.set(true)
            server.close()
            executor.shutdownNow()
            failure.get()?.let { throw AssertionError("Local RPC server failed", it) }
        }

        private fun readAsciiLine(input: BufferedInputStream): String {
            val bytes = ArrayList<Byte>()
            while (true) {
                val next = input.read()
                check(next >= 0) { "RPC test request ended before its headers" }
                if (next == '\r'.code) {
                    check(input.read() == '\n'.code) { "Malformed RPC test HTTP line ending" }
                    return bytes.toByteArray().toString(StandardCharsets.US_ASCII)
                }
                bytes += next.toByte()
            }
        }
    }

    private companion object {
        const val CHAIN_ID = 84_532L
        const val BLOCK_NUMBER = 100L
        const val EXPECTED_AMOUNT = "10"
        val INVOICE_ID = "0x" + "11".repeat(32)
        val FACTORY = "0x" + "22".repeat(20)
        val IMPLEMENTATION = "0x" + "33".repeat(20)
        val VAULT = "0x" + "44".repeat(20)
        val TOKEN = "0x" + "55".repeat(20)
        val RECEIVER = "0x" + "66".repeat(20)
        val OPERATOR = "0x" + "77".repeat(20)
        val BLOCK_HASH = "0x" + "aa".repeat(32)
        val OPEN_STATUSES = setOf(InvoiceStatus.WAITING, InvoiceStatus.PARTIAL, InvoiceStatus.CONFIRMING)
    }
}
