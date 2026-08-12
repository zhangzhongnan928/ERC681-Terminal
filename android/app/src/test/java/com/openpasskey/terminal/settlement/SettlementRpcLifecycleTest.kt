package com.openpasskey.terminal.settlement

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class SettlementRpcLifecycleTest {
    @Test
    fun `settlement transport never installs a request body logger`() {
        val client = settlementHttpClientBuilder().build()
        try {
            assertFalse(
                client.interceptors.any { interceptor ->
                    interceptor.javaClass.name == "okhttp3.logging.HttpLoggingInterceptor"
                },
            )
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    @Test
    fun `closed client cannot reach old URL and replacement uses only new URL`() {
        LocalRpcServer("0x14a34").use { oldServer ->
            LocalRpcServer("0x2105").use { replacementServer ->
                val oldClient = Web3jSettlementChainClient(oldServer.url)
                assertEquals(84_532L, oldClient.chainId())
                assertEquals(1, oldServer.requestCount.get())

                oldClient.close()
                oldClient.close()
                assertThrows(SettlementRpcException::class.java) {
                    oldClient.chainId()
                }

                Web3jSettlementChainClient(replacementServer.url).use { replacementClient ->
                    assertEquals(8_453L, replacementClient.chainId())
                }

                assertEquals(1, oldServer.requestCount.get())
                assertEquals(1, replacementServer.requestCount.get())
            }
        }
    }

    @Test
    fun `provider body and credential URL are removed from web3j transport failures`() {
        val sentinel = "provider-secret-body"
        LocalRpcServer(
            chainId = "0x14a34",
            status = "429 Too Many Requests",
            responseBody = "{\"error\":\"$sentinel\"}",
        ).use { server ->
            val client = Web3jSettlementChainClient("${server.url}credential-path")

            val error = assertThrows(SettlementRpcException::class.java) {
                client.use { it.chainId() }
            }

            assertEquals("RPC transport failed", error.message)
            assertNull(error.cause)
            assertFalse(error.toString().contains(sentinel))
            assertFalse(error.toString().contains("credential-path"))
        }
    }

    private class LocalRpcServer(
        private val chainId: String,
        private val status: String = "200 OK",
        private val responseBody: String? = null,
    ) : AutoCloseable {
        private val server = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress("127.0.0.1", 0))
        }
        private val executor = Executors.newSingleThreadExecutor()
        private val closed = AtomicBoolean()
        private val failure = AtomicReference<Throwable?>()
        val requestCount = AtomicInteger()
        val url: String = "http://127.0.0.1:${server.localPort}/"

        init {
            executor.execute {
                while (!closed.get()) {
                    try {
                        server.accept().use { socket ->
                            val input = BufferedInputStream(socket.getInputStream())
                            val output = BufferedOutputStream(socket.getOutputStream())
                            readAsciiLine(input)
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
                                check(count >= 0) { "RPC request ended before its declared body" }
                                offset += count
                            }
                            requestCount.incrementAndGet()
                            val request = JsonParser.parseString(
                                String(requestBytes, StandardCharsets.UTF_8),
                            ).asJsonObject
                            val responseBytes = (responseBody ?: JsonObject().apply {
                                addProperty("jsonrpc", "2.0")
                                add("id", request.get("id"))
                                addProperty("result", chainId)
                            }.toString()).toByteArray(StandardCharsets.UTF_8)
                            output.write(
                                (
                                    "HTTP/1.1 $status\r\n" +
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
                check(next >= 0) { "RPC request ended before its headers" }
                if (next == '\r'.code) {
                    check(input.read() == '\n'.code) { "Malformed RPC HTTP line ending" }
                    return bytes.toByteArray().toString(StandardCharsets.US_ASCII)
                }
                bytes += next.toByte()
            }
        }
    }
}
