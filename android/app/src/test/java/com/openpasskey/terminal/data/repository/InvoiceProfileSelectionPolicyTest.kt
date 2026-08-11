package com.openpasskey.terminal.data.repository

import com.openpasskey.erc681.EvmAddress
import com.openpasskey.erc681.InvoiceId
import com.openpasskey.erc681.NetworkConfig
import com.openpasskey.erc681.PaymentInvoiceFactory
import com.openpasskey.erc681.TokenAmount
import com.openpasskey.terminal.chain.PaymentToken
import com.openpasskey.terminal.chain.TerminalConfigSnapshot
import com.openpasskey.terminal.chain.MerchantReceiptProfile
import com.openpasskey.terminal.chain.TerminalPaymentProfile
import com.openpasskey.terminal.chain.selectedPaymentProfile
import com.openpasskey.terminal.chain.selectingProfile
import com.openpasskey.terminal.lifecycle.TerminalLifecycleGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

class InvoiceProfileSelectionPolicyTest {
    @Test
    fun staleUiProfileIdIsRejectedBeforeAmountInterpretationOrQrPublication() {
        val profileA = profile(
            networkName = "Base Sepolia",
            rpcUrl = "https://sepolia.base.org",
            chainId = 84_532,
            factory = "0x2592fbab9707e65e21ea14d8a9fe298f5e68a37f",
            implementation = "0xf2e0d5fc47761cac0eedee6cb1af5f31843a0a18",
            vault = "0x1111111111111111111111111111111111111111",
            token = "0x2222222222222222222222222222222222222222",
            symbol = "AUDM",
            decimals = 18,
            confirmations = 2,
        )
        val profileB = profile(
            networkName = "Base Sepolia",
            rpcUrl = "https://sepolia.base.org",
            chainId = 84_532,
            factory = profileA.factoryAddress,
            implementation = profileA.receiverImplementationAddress,
            vault = "0x3333333333333333333333333333333333333333",
            token = "0x4444444444444444444444444444444444444444",
            symbol = "USDC",
            decimals = 6,
            confirmations = 2,
        )
        val selectedA = snapshot(profileA, listOf(profileA, profileB))

        assertEquals(profileA, requireSelectedPaymentProfile(selectedA, profileA.id))
        val error = assertThrows(IllegalArgumentException::class.java) {
            requireSelectedPaymentProfile(selectedA, profileB.id)
        }
        assertEquals(
            "Selected payment profile changed; review the currency and try again",
            error.message,
        )
    }

    @Test
    fun publishedInvoicePersistsCompleteSelectedProfileBAndIgnoresLaterSelection() {
        val profileA = profile(
            networkName = "Base Sepolia",
            rpcUrl = "https://sepolia.base.org",
            chainId = 84_532,
            factory = "0x2592fbab9707e65e21ea14d8a9fe298f5e68a37f",
            implementation = "0xf2e0d5fc47761cac0eedee6cb1af5f31843a0a18",
            vault = "0x1111111111111111111111111111111111111111",
            token = "0x2222222222222222222222222222222222222222",
            symbol = "AUDM",
            decimals = 18,
            confirmations = 2,
        )
        val profileB = profile(
            networkName = "Merchant EVM",
            rpcUrl = "https://rpc.merchant.example",
            chainId = 9_999,
            factory = "0x3333333333333333333333333333333333333333",
            implementation = "0x4444444444444444444444444444444444444444",
            vault = "0x5555555555555555555555555555555555555555",
            token = "0x6666666666666666666666666666666666666666",
            symbol = "USDC",
            decimals = 6,
            confirmations = 7,
        )
        val operator = EvmAddress.parse("0x7777777777777777777777777777777777777777")
        val selectedB = snapshot(profileA, listOf(profileA, profileB))
            .selectingProfile(profileB.id)
        val capturedProfile = requireNotNull(selectedB.selectedPaymentProfile())
        val network = NetworkConfig(
            chainId = capturedProfile.chainId,
            rpcUrl = capturedProfile.rpcUrl,
            factory = EvmAddress.parse(capturedProfile.factoryAddress),
            receiverImplementation = EvmAddress.parse(
                capturedProfile.receiverImplementationAddress,
            ),
            vault = EvmAddress.parse(capturedProfile.vaultAddress),
        )
        val protocolInvoice = PaymentInvoiceFactory.create(
            network = network,
            token = EvmAddress.parse(capturedProfile.token.address),
            amount = TokenAmount.ofRaw(BigInteger("1234567"), capturedProfile.token.decimals),
            invoiceId = InvoiceId.parse("0x${"ab".repeat(32)}"),
        )

        val persisted = buildPublishedInvoiceSnapshot(
            protocolInvoice = protocolInvoice,
            selectedProfile = capturedProfile,
            operatorAddress = operator,
            createdAt = 1_234,
            publishedAtBlock = 99,
            publishedAtBlockHash = "0x${"12".repeat(32)}",
            receiptNumber = 7,
            merchantReceiptProfile = MerchantReceiptProfile.fromInput(
                name = "Blue Brew",
                abn = "61 695 642 285",
            ),
        )
        val laterSelection = selectedB.selectingProfile(profileA.id)

        assertEquals(profileA.id, laterSelection.selectedPaymentProfile()?.id)
        assertEquals(profileB.chainId, persisted.chainId)
        assertEquals(profileB.networkName, persisted.networkName)
        assertEquals(profileB.rpcUrl, persisted.rpcUrl)
        assertEquals(profileB.factoryAddress, persisted.factoryAddress)
        assertEquals(profileB.receiverImplementationAddress, persisted.receiverImplementationAddress)
        assertEquals(profileB.vaultAddress, persisted.vaultAddress)
        assertEquals(profileB.confirmationBlocks, persisted.confirmationBlocks)
        assertEquals(profileB.token.address, persisted.token)
        assertEquals(profileB.token.symbol, persisted.tokenSymbol)
        assertEquals(profileB.token.decimals, persisted.tokenDecimals)
        assertEquals(operator.value, persisted.operatorAddress)
        assertEquals("1234567", persisted.expectedAmount)
        assertEquals(99L, persisted.publishedAtBlock)
        assertEquals(7L, persisted.receiptNumber)
        assertEquals("Blue Brew", persisted.receiptMerchantName)
        assertEquals("61 695 642 285", persisted.receiptMerchantAbn)
        assertEquals(true, persisted.receiptAutoPrintEligible)
        assertEquals(profileB.chainId, protocolInvoice.request.chainId)
    }

    @Test
    fun selectionWaitsUntilInvoicePublicationLeavesLifecycleGate() = runBlocking {
        val gate = TerminalLifecycleGate()
        val invoiceReachedFinalCheck = CompletableDeferred<Unit>()
        val allowInvoicePublication = CompletableDeferred<Unit>()
        val selected = AtomicBoolean(false)
        val events = Collections.synchronizedList(mutableListOf<String>())

        val invoice = async(Dispatchers.Default) {
            gate.withExclusiveMutation {
                events += "invoice-final-check"
                invoiceReachedFinalCheck.complete(Unit)
                allowInvoicePublication.await()
                events += "invoice-published"
            }
        }
        invoiceReachedFinalCheck.await()

        val selection = async(Dispatchers.Default) {
            selectPaymentProfileExclusively(
                lifecycleGate = gate,
                profileId = "profile-b",
                selectProfile = {
                    selected.set(true)
                    events += "profile-selected"
                    true
                },
                snapshot = ::emptySnapshot,
            )
        }

        delay(20)
        assertFalse(selected.get())
        allowInvoicePublication.complete(Unit)
        invoice.await()
        selection.await()

        assertTrue(selected.get())
        assertEquals(
            listOf("invoice-final-check", "invoice-published", "profile-selected"),
            events,
        )
    }

    private fun emptySnapshot() = TerminalConfigSnapshot(
        networkName = "Base Sepolia",
        rpcUrl = "https://sepolia.base.org",
        chainId = 84532,
        factoryAddress = "0x2592fbab9707e65e21ea14d8a9fe298f5e68a37f",
        receiverImplementationAddress = "0xf2e0d5fc47761cac0eedee6cb1af5f31843a0a18",
        vaultAddress = "",
        confirmationBlocks = 2,
        paymentTokens = emptyList(),
        protocolVersion = "",
        provisionedOperatorAddress = null,
        provisioned = false,
    )

    private fun profile(
        networkName: String,
        rpcUrl: String,
        chainId: Long,
        factory: String,
        implementation: String,
        vault: String,
        token: String,
        symbol: String,
        decimals: Int,
        confirmations: Int,
    ) = TerminalPaymentProfile(
        networkName = networkName,
        rpcUrl = rpcUrl,
        chainId = chainId,
        factoryAddress = factory,
        receiverImplementationAddress = implementation,
        vaultAddress = vault,
        confirmationBlocks = confirmations,
        token = PaymentToken(token, symbol, decimals),
        protocolVersion = "1.6",
    )

    private fun snapshot(
        selected: TerminalPaymentProfile,
        profiles: List<TerminalPaymentProfile>,
    ) = TerminalConfigSnapshot(
        networkName = selected.networkName,
        rpcUrl = selected.rpcUrl,
        chainId = selected.chainId,
        factoryAddress = selected.factoryAddress,
        receiverImplementationAddress = selected.receiverImplementationAddress,
        vaultAddress = selected.vaultAddress,
        confirmationBlocks = selected.confirmationBlocks,
        paymentTokens = listOf(selected.token),
        protocolVersion = selected.protocolVersion,
        provisionedOperatorAddress = "0x7777777777777777777777777777777777777777",
        provisioned = true,
        paymentProfiles = profiles,
        selectedProfileId = selected.id,
    )
}
