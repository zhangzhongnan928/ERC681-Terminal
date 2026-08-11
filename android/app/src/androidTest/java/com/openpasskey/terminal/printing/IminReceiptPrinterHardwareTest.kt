package com.openpasskey.terminal.printing

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertSame
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in physical printer check for an attached iMin Swift 2.
 *
 * The instrumentation argument prevents normal CI and full device suites from consuming paper.
 * Run only with `-e opkHardwarePrinter true` while a test operator is present at the terminal.
 */
@RunWith(AndroidJUnit4::class)
class IminReceiptPrinterHardwareTest {
    @Test
    fun printsOneBufferedReceiptAndReceivesPhysicalCompletion() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString(HARDWARE_ARGUMENT) == "true")

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val printer = IminReceiptPrinter(context)
        try {
            val result = printer.print(
                ReceiptDocument(
                    merchantName = "OPK Hardware Test",
                    merchantAbn = "61 695 642 285",
                    displayAmount = "0.00",
                    tokenSymbol = "TEST",
                    networkName = "Base Sepolia",
                    terminalAddress = "0x0000000000000000000000000000000000000001",
                    paymentTxHash = "0x" + "00".repeat(32),
                    receiptNumber = 0,
                    paidAtEpochSeconds = Instant.parse("2026-08-11T00:00:00Z").epochSecond,
                    explorerUrl = "https://sepolia.basescan.org/tx/0x${"00".repeat(32)}",
                ),
            )

            assertSame(ReceiptPrintResult.Success, result)
        } finally {
            printer.close()
        }
    }

    private companion object {
        const val HARDWARE_ARGUMENT = "opkHardwarePrinter"
    }
}
