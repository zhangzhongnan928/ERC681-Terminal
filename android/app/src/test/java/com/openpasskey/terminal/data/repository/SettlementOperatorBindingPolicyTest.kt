package com.openpasskey.terminal.data.repository

import com.openpasskey.terminal.chain.PaymentToken
import com.openpasskey.terminal.chain.TerminalConfigSnapshot
import com.openpasskey.terminal.data.model.Invoice
import com.openpasskey.terminal.data.model.InvoiceStatus
import com.openpasskey.terminal.wallet.OperatorWalletAvailability
import com.openpasskey.terminal.wallet.OperatorWalletSnapshot
import org.junit.Assert.assertThrows
import org.junit.Test

class SettlementOperatorBindingPolicyTest {
    @Test
    fun invoiceOperatorSnapshotsMustMatchEachOtherAndTheCurrentWallet() {
        requireInvoiceOperatorSnapshots(
            invoices = listOf(invoice(OPERATOR), invoice(OPERATOR)),
            currentOperatorAddress = OPERATOR,
        )

        assertThrows(IllegalArgumentException::class.java) {
            requireInvoiceOperatorSnapshots(
                invoices = listOf(invoice(OPERATOR), invoice(OTHER)),
                currentOperatorAddress = OPERATOR,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            requireInvoiceOperatorSnapshots(
                invoices = listOf(invoice(OTHER)),
                currentOperatorAddress = OPERATOR,
            )
        }
    }

    @Test
    fun blankLegacyOperatorSnapshotsKeepFreshAuthorizationFallback() {
        requireInvoiceOperatorSnapshots(
            invoices = listOf(invoice(""), invoice(OPERATOR)),
            currentOperatorAddress = OPERATOR,
        )
        requireInvoiceOperatorSnapshots(
            invoices = listOf(invoice(""), invoice("")),
            currentOperatorAddress = OPERATOR,
        )
    }

    @Test
    fun removedLastCheckoutProfileDoesNotBlockHistoricalSettlementWallet() {
        requireSettlementOperatorBinding(
            config = config(provisioned = false, boundOperator = null),
            wallet = readyWallet(OPERATOR),
            operatorAddress = OPERATOR,
        )
    }

    @Test
    fun activeProvisioningAndLocalWalletMustStillMatchOperator() {
        assertThrows(IllegalStateException::class.java) {
            requireSettlementOperatorBinding(
                config = config(provisioned = true, boundOperator = OTHER),
                wallet = readyWallet(OPERATOR),
                operatorAddress = OPERATOR,
            )
        }
        assertThrows(IllegalStateException::class.java) {
            requireSettlementOperatorBinding(
                config = config(provisioned = false, boundOperator = null),
                wallet = readyWallet(OTHER),
                operatorAddress = OPERATOR,
            )
        }
    }

    private fun readyWallet(address: String) = OperatorWalletSnapshot(
        availability = OperatorWalletAvailability.READY,
        address = address,
    )

    private fun config(provisioned: Boolean, boundOperator: String?) = TerminalConfigSnapshot(
        networkName = "Base Sepolia",
        rpcUrl = "https://sepolia.base.org",
        chainId = 84532,
        factoryAddress = "0x2592fbab9707e65e21ea14d8a9fe298f5e68a37f",
        receiverImplementationAddress = "0xf2e0d5fc47761cac0eedee6cb1af5f31843a0a18",
        vaultAddress = if (provisioned) {
            "0x1111111111111111111111111111111111111111"
        } else {
            ""
        },
        confirmationBlocks = 2,
        paymentTokens = if (provisioned) {
            listOf(PaymentToken("0x7ffba642bc902880a737cb1c18a4e9540879e211", "AUD", 18))
        } else {
            emptyList()
        },
        protocolVersion = if (provisioned) "1.6" else "",
        provisionedOperatorAddress = boundOperator,
        provisioned = provisioned,
    )

    private fun invoice(operatorAddress: String) = Invoice(
        invoiceId = "0x${"ab".repeat(32)}",
        receiver = "0x3333333333333333333333333333333333333333",
        operatorAddress = operatorAddress,
        token = "0x4444444444444444444444444444444444444444",
        tokenSymbol = "USDC",
        tokenDecimals = 6,
        expectedAmount = "1000000",
        status = InvoiceStatus.PAID,
        createdAt = 1,
    )

    private companion object {
        const val OPERATOR = "0x1111111111111111111111111111111111111111"
        const val OTHER = "0x2222222222222222222222222222222222222222"
    }
}
