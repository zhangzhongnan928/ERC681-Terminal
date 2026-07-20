package com.openpasskey.terminal.provisioning

import com.openpasskey.erc681.Create2ReceiverResolver
import com.openpasskey.erc681.EvmAddress
import com.openpasskey.erc681.InvoiceId

data class KnownChainProfile(
    val chainId: Long,
    val networkName: String,
    val rpcUrl: String,
    val factory: EvmAddress,
    val receiverImplementation: EvmAddress,
    val vaultRuntimeCodeHash: String,
    val protocolVersion: String,
    val fixtureVault: EvmAddress,
    val fixtureInvoiceId: InvoiceId,
    val fixtureReceiver: EvmAddress,
) {
    /** Runtime guard against accidentally shipping pins that disagree with the local CREATE2 rail. */
    fun requireValidCreate2Fixture() {
        val actual = Create2ReceiverResolver(factory, receiverImplementation)
            .resolve(fixtureVault, fixtureInvoiceId)
        check(actual == fixtureReceiver) { "Built-in CREATE2 deployment fixture does not match" }
    }
}

object KnownChainPolicy {
    private val profiles = mapOf(
        84532L to KnownChainProfile(
            chainId = 84532L,
            networkName = "Base Sepolia",
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
            fixtureReceiver = EvmAddress.parse("0x9107decd2cb06c57c40a663648e19cde1d52f606"),
        ),
    )

    fun requireProfile(chainId: Long): KnownChainProfile = profiles[chainId]
        ?: throw IllegalArgumentException("Chain $chainId is not supported by this terminal build")
}
