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
            minimumConfirmationBlocks = 2,
            defaultConfirmationBlocks = 2,
            minimumOperatorNativeReserve = BigInteger("100000000000000"),
            rpcUrl = "https://sepolia.base.org",
            factory = EvmAddress.parse("0x062e3b5d3107e4d1b8dDA314E16b9F8cA6EB63D5"),
            receiverImplementation = EvmAddress.parse("0xDAa292B1bf533737C5cE5d27F220273971Db3Bdc"),
            vaultRuntimeCodeHash =
                "0xe7310159a3c109346b137a989bfd213e65fe48ded6eb84dbe57a37d7a047513e",
            protocolVersion = "1.4.1",
            fixtureVault = EvmAddress.parse("0x1ed67E540E6AB92dC3537A7bba3BcAb6FdD69Da1"),
            fixtureInvoiceId = InvoiceId.parse(
                "0x294d3b9eb0136d18f2a3e8aa9c10224029893c244e822a15902256d778f7f729",
            ),
            fixtureSalt =
                "0x87810f9819659ca2d4dd62a4e7b43c87f611148a2ea26782b9b8da39a63353ce",
            fixtureInitCodeHash =
                "0x59a3a359c30137feff57a746e7430ee4aef036fe41906d52b4f60a78948a2051",
            fixtureReceiver = EvmAddress.parse("0x9107decd2cb06c57c40a663648e19cde1d52f606"),
        ),
    )

    fun requireProfile(chainId: Long): KnownChainProfile = profiles[chainId]
        ?: throw IllegalArgumentException("Chain $chainId is not supported by this terminal build")

    fun enabledChainIds(): Set<Long> = profiles.keys

    fun enabledProfiles(): List<KnownChainProfile> = profiles.values.sortedBy { it.chainId }
}
