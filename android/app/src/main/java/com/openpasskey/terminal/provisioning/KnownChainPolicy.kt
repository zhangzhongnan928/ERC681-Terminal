package com.openpasskey.terminal.provisioning

import com.openpasskey.erc681.Create2ReceiverResolver
import com.openpasskey.erc681.EvmAddress
import com.openpasskey.erc681.InvoiceId
import com.openpasskey.erc681.NativeAsset
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
    fun protocolVersionFor(paymentAsset: EvmAddress): String =
        if (NativeAsset.isNative(paymentAsset)) NativeAsset.PROTOCOL_VERSION else protocolVersion

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
    // and CREATE2 fixture are independently verified. The production default is listed explicitly;
    // callers must never infer it from map or numeric chain ordering.
    const val DEFAULT_CHAIN_ID = 8453L

    private val profiles = mapOf(
        8453L to KnownChainProfile(
            chainId = 8453L,
            networkName = "Base Mainnet",
            isTestnet = false,
            nativeCurrencySymbol = "ETH",
            nativeCurrencyDecimals = 18,
            minimumConfirmationBlocks = 1,
            defaultConfirmationBlocks = 1,
            minimumOperatorNativeReserve = BigInteger("100000000000000"),
            rpcUrl = "https://mainnet.base.org",
            factory = EvmAddress.parse("0x5418ab1790eaf96a20e26146c5b7765cb99328da"),
            receiverImplementation = EvmAddress.parse("0xe6393f6176865cc62cd08d8b8f0c38d35af55254"),
            vaultRuntimeCodeHash =
                "0x8c3a56b5606e44613d50c898acf67a3689afc478b47e9a38326699b0df111cbd",
            protocolVersion = "1.6",
            fixtureVault = EvmAddress.parse("0x1111111111111111111111111111111111111111"),
            fixtureInvoiceId = InvoiceId.parse(
                "0xd5ab0fb2beaa1c3d789ae8a50b9429257b7f830830c8c4e23177a0fb2e116c77",
            ),
            fixtureSalt =
                "0x8b43abe81bab80f024d08540d6ffed9dab76ebd2f0096a53671e7c9aa94462ab",
            fixtureInitCodeHash =
                "0x3b2db354080b627c0b567ce3b408da0bd1ad3c63d0cbe675ee0bfd1a34817f1a",
            fixtureReceiver = EvmAddress.parse("0x3da3df1635ef2334e5b26bee7b87e34d01454d8b"),
        ),
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
            factory = EvmAddress.parse("0x2592fbab9707e65e21ea14d8a9fe298f5e68a37f"),
            receiverImplementation = EvmAddress.parse("0xf2e0d5fc47761cac0eedee6cb1af5f31843a0a18"),
            vaultRuntimeCodeHash =
                "0x32ad6b6076f449fbc39e115afc2645c65071280af2d461dc315544ac0a1d7e58",
            protocolVersion = "1.6",
            fixtureVault = EvmAddress.parse("0x1111111111111111111111111111111111111111"),
            fixtureInvoiceId = InvoiceId.parse(
                "0xd5ab0fb2beaa1c3d789ae8a50b9429257b7f830830c8c4e23177a0fb2e116c77",
            ),
            fixtureSalt =
                "0x8b43abe81bab80f024d08540d6ffed9dab76ebd2f0096a53671e7c9aa94462ab",
            fixtureInitCodeHash =
                "0xd237f12377830073f2b667364b744f01cc0f00724e949159e2665134248ca4ad",
            fixtureReceiver = EvmAddress.parse("0xd7bb9c5f5a337b9d9ebcd65e1f840f782985291d"),
        ),
    )

    fun requireProfile(chainId: Long): KnownChainProfile = profiles[chainId]
        ?: throw IllegalArgumentException("Chain $chainId is not supported by this terminal build")

    fun defaultProfile(): KnownChainProfile = requireProfile(DEFAULT_CHAIN_ID)

    fun enabledChainIds(): Set<Long> = profiles.keys

    fun enabledProfiles(): List<KnownChainProfile> = profiles.values.sortedWith(
        compareBy<KnownChainProfile> { it.chainId != DEFAULT_CHAIN_ID }
            .thenBy { it.chainId },
    )
}
