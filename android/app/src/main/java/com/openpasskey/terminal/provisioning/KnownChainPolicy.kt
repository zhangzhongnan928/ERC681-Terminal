package com.openpasskey.terminal.provisioning

import com.openpasskey.erc681.Create2ReceiverResolver
import com.openpasskey.erc681.EvmAddress
import com.openpasskey.erc681.InvoiceId
import java.math.BigInteger

data class KnownChainProfile(
    val chainId: Long,
    val networkName: String,
    val isTestnet: Boolean,
    val nativeCurrencySymbol: String,
    val nativeCurrencyDecimals: Int,
    val minimumConfirmationBlocks: Int,
    val defaultConfirmationBlocks: Int,
    val minimumOperatorNativeReserve: BigInteger,
    val rpcUrl: String,
    val factory: EvmAddress,
    val receiverImplementation: EvmAddress,
    val vaultRuntimeCodeHash: String,
    val protocolVersion: String,
    val fixtureVault: EvmAddress,
    val fixtureInvoiceId: InvoiceId,
    val fixtureSalt: String,
    val fixtureInitCodeHash: String,
    val fixtureReceiver: EvmAddress,
) {
    /** Runtime guard against accidentally shipping pins that disagree with the local CREATE2 rail. */
    fun requireValidCreate2Fixture() {
        val actual = Create2ReceiverResolver(factory, receiverImplementation)
            .derive(fixtureVault, fixtureInvoiceId)
        check(actual.salt == fixtureSalt) { "Built-in CREATE2 salt fixture does not match" }
        check(actual.initCodeHash == fixtureInitCodeHash) {
            "Built-in CREATE2 init-code hash fixture does not match"
        }
        check(actual.receiver == fixtureReceiver) { "Built-in CREATE2 receiver fixture does not match" }
    }
}

object KnownChainPolicy {
    // Add a chain only after its deployed factory, receiver implementation, runtime bytecode hash,
    // and CREATE2 fixture are independently verified. The SDK catalog supports many EVM networks;
    // only Base Sepolia is currently enabled in the production app.
    private val profiles = mapOf(
        84532L to KnownChainProfile(
            chainId = 84532L,
            networkName = "Base Sepolia",
            isTestnet = true,
            nativeCurrencySymbol = "ETH",
            nativeCurrencyDecimals = 18,
            minimumConfirmationBlocks = 1,
            defaultConfirmationBlocks = 1,
            minimumOperatorNativeReserve = BigInteger("100000000000000"),
            rpcUrl = "https://sepolia.base.org",
            factory = EvmAddress.parse("0xb69f725999266c6757284ca4169275c3ebde491a"),
            receiverImplementation = EvmAddress.parse("0x8ba9739741ecc79b5d69fe5580d2966092e6f77f"),
            vaultRuntimeCodeHash =
                "0x2ceea713f7225b17e43487b8652d8582dadd5aabefc5b9f78d231777958655b9",
            protocolVersion = "1.5",
            fixtureVault = EvmAddress.parse("0x1111111111111111111111111111111111111111"),
            fixtureInvoiceId = InvoiceId.parse(
                "0x474614682f1d5e8e24396c2394a98425d4e8617fe699872c96182b89368e50d4",
            ),
            fixtureSalt =
                "0x6ebed91ff26055c5762437f3fe8f834dde34b0dae39fd3df75dcfc1d1e064e1d",
            fixtureInitCodeHash =
                "0xad563722da414e51edc3d8195e2f225d872f79ea5b511cb2c3a62d6fa1a66b02",
            fixtureReceiver = EvmAddress.parse("0x8128e3A86962519877186c5F4F0920Ba7240f5B1"),
        ),
    )

    fun requireProfile(chainId: Long): KnownChainProfile = profiles[chainId]
        ?: throw IllegalArgumentException("Chain $chainId is not supported by this terminal build")

    fun enabledChainIds(): Set<Long> = profiles.keys

    fun enabledProfiles(): List<KnownChainProfile> = profiles.values.sortedBy { it.chainId }
}
