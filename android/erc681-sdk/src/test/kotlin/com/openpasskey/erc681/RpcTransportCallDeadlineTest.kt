// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Victor Zhang

package com.openpasskey.erc681

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
 * Socket read timeouts bound each blocking read but not their sum: a peer releasing one byte at a
 * time keeps every individual read fast while the call runs forever. The whole-call transport
 * deadline must stop exactly that.
 */
class RpcTransportCallDeadlineTest {
    @Test
    fun `whole-call deadline stops a dribbling response that never trips the read timeout`() {
        DribblingHttpServer().use { server ->
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

    /** Sends valid headers, then one body byte per interval, each well inside the read timeout. */
    private class DribblingHttpServer : AutoCloseable {
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
                        var remaining = contentLength
                        while (remaining > 0) {
                            val skipped = input.read(ByteArray(remaining))
                            check(skipped >= 0) { "Request ended before its declared body" }
                            remaining -= skipped
                        }
                        val output = socket.getOutputStream()
                        output.write(
                            (
                                "HTTP/1.1 200 OK\r\n" +
                                    "Content-Type: application/json\r\n" +
                                    "Content-Length: 1000000\r\n\r\n"
                                ).toByteArray(StandardCharsets.US_ASCII),
                        )
                        output.flush()
                        var dribbled = 0
                        while (!closed.get() && dribbled < MAX_DRIBBLED_BYTES) {
                            output.write('x'.code)
                            output.flush()
                            dribbled += 1
                            Thread.sleep(DRIBBLE_INTERVAL_MILLIS)
                        }
                    }
                } catch (_: Exception) {
                    // The client aborting mid-body is this test's success path.
                }
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
            // Generous runaway cap: the client's 400 ms deadline aborts the call long before this.
            const val MAX_DRIBBLED_BYTES = 2_000
        }
    }

    private companion object {
        val FACTORY = EvmAddress.parse("0x1111111111111111111111111111111111111111")
        val IMPLEMENTATION = EvmAddress.parse("0x2222222222222222222222222222222222222222")
        val VAULT = EvmAddress.parse("0x3333333333333333333333333333333333333333")
    }
}
