// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Victor Zhang

package com.openpasskey.erc681

import com.google.gson.JsonParser
import java.io.BufferedInputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Socket read timeouts are idle timeouts: a peer releasing one byte at a time — during headers or
 * during the body — keeps every individual read fast while the call runs far past any per-read
 * bound. The whole-call watchdog deadline must stop both shapes. Each dribbled response here is a
 * complete, valid chainId reply, so if deadline enforcement ever regressed the call would simply
 * succeed and `assertFailsWith` would fail the test.
 */
class RpcTransportCallDeadlineTest {
    @Test
    fun `whole-call deadline stops a dribbling body that never trips the read timeout`() {
        assertDeadlineStopsCall(DribblingHttpServer.Mode.DRIBBLE_BODY)
    }

    @Test
    fun `whole-call deadline stops dribbling headers blocking inside responseCode`() {
        assertDeadlineStopsCall(DribblingHttpServer.Mode.DRIBBLE_HEADERS_AND_BODY)
    }

    private fun assertDeadlineStopsCall(mode: DribblingHttpServer.Mode) {
        DribblingHttpServer(mode).use { server ->
            val client = ReadOnlyRpcClient(
                NetworkConfig(
                    chainId = 84_532,
                    rpcUrl = server.url,
                    factory = FACTORY,
                    receiverImplementation = IMPLEMENTATION,
                    vault = VAULT,
                ),
                connectTimeoutMillis = 1_000,
                readTimeoutMillis = 500,
                callTimeoutMillis = 400,
            )

            val error = assertFailsWith<RpcException> { client.chainId() }

            assertEquals("RPC HTTP call exceeded its deadline", error.message)
        }
    }

    /**
     * Serves one valid chainId response, releasing bytes on a cadence well inside the client's
     * idle read timeout — either only the body, or every byte from the status line onward.
     */
    private class DribblingHttpServer(private val mode: Mode) : AutoCloseable {
        enum class Mode { DRIBBLE_BODY, DRIBBLE_HEADERS_AND_BODY }

        private val server = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress("127.0.0.1", 0))
        }
        private val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "dribbling-rpc-server").apply { isDaemon = true }
        }
        private val closed = AtomicBoolean()
        val url: String = "http://127.0.0.1:${server.localPort}/"

        init {
            executor.execute {
                try {
                    server.accept().use { socket ->
                        val input = BufferedInputStream(socket.getInputStream())
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
                            check(count >= 0) { "Request ended before its declared body" }
                            offset += count
                        }
                        val requestId = JsonParser
                            .parseString(String(requestBytes, StandardCharsets.UTF_8))
                            .asJsonObject
                            .get("id")
                            .asLong
                        val responseBody =
                            """{"jsonrpc":"2.0","id":$requestId,"result":"0x14a34"}"""
                                .toByteArray(StandardCharsets.UTF_8)
                        val headers = (
                            "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: application/json\r\n" +
                                "Content-Length: ${responseBody.size}\r\n\r\n"
                            ).toByteArray(StandardCharsets.US_ASCII)
                        val output = socket.getOutputStream()
                        when (mode) {
                            Mode.DRIBBLE_BODY -> {
                                output.write(headers)
                                output.flush()
                                dribble(output, responseBody)
                            }
                            Mode.DRIBBLE_HEADERS_AND_BODY -> {
                                dribble(output, headers + responseBody)
                            }
                        }
                    }
                } catch (_: Exception) {
                    // The client aborting the connection mid-response is this test's success path.
                }
            }
        }

        private fun dribble(output: java.io.OutputStream, bytes: ByteArray) {
            for (byte in bytes) {
                if (closed.get()) return
                output.write(byte.toInt())
                output.flush()
                Thread.sleep(DRIBBLE_INTERVAL_MILLIS)
            }
        }

        override fun close() {
            closed.set(true)
            server.close()
            executor.shutdownNow()
        }

        private fun readAsciiLine(input: BufferedInputStream): String {
            val bytes = ArrayList<Byte>()
            while (true) {
                val next = input.read()
                check(next >= 0) { "Request ended before its headers" }
                if (next == '\r'.code) {
                    check(input.read() == '\n'.code) { "Malformed HTTP line ending" }
                    return bytes.toByteArray().toString(StandardCharsets.US_ASCII)
                }
                bytes += next.toByte()
            }
        }

        private companion object {
            const val DRIBBLE_INTERVAL_MILLIS = 25L
        }
    }

    private companion object {
        val FACTORY = EvmAddress.parse("0x1111111111111111111111111111111111111111")
        val IMPLEMENTATION = EvmAddress.parse("0x2222222222222222222222222222222222222222")
        val VAULT = EvmAddress.parse("0x3333333333333333333333333333333333333333")
    }
}
