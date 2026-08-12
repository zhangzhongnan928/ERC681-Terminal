package com.openpasskey.terminal.rpc

import com.openpasskey.erc681.EvmAddress
import com.openpasskey.erc681.NativeAsset
import com.openpasskey.erc681.NetworkConfig
import com.openpasskey.terminal.chain.TerminalConfigSnapshot
import com.openpasskey.terminal.chain.TerminalPaymentProfile
import com.openpasskey.terminal.chain.resolvedPaymentProfiles
import com.openpasskey.terminal.provisioning.KnownChainPolicy
import com.openpasskey.terminal.provisioning.ProvisioningChainReader
import com.openpasskey.terminal.provisioning.ProvisioningChainReaderFactory
import com.openpasskey.terminal.provisioning.RpcProvisioningChainReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.web3j.crypto.Hash
import org.web3j.utils.Numeric

/** Validates an administrator-supplied endpoint before its encrypted override is committed. */
fun interface RpcEndpointVerifier {
    suspend fun verify(
        chainId: Long,
        rpcUrl: String,
        currentConfiguration: TerminalConfigSnapshot,
    )
}

/**
 * Proves the endpoint serves the requested known chain. When the terminal already has a payment
 * route on that chain, the proof also revalidates every configured route and its immutable
 * deployment and token pins before the endpoint can become active.
 */
class PinnedRpcEndpointVerifier(
    private val clientFactory: ProvisioningChainReaderFactory = ProvisioningChainReaderFactory {
        RpcProvisioningChainReader(com.openpasskey.erc681.ReadOnlyRpcClient(it))
    },
) : RpcEndpointVerifier {
    override suspend fun verify(
        chainId: Long,
        rpcUrl: String,
        currentConfiguration: TerminalConfigSnapshot,
    ) = runInterruptible(Dispatchers.IO) {
        val policy = KnownChainPolicy.requireProfile(chainId)
        policy.requireValidCreate2Fixture()
        val configuredRoutes = currentConfiguration.resolvedPaymentProfiles()
            .filter { it.chainId == chainId }
            .distinctBy(TerminalPaymentProfile::id)
            .sortedBy(TerminalPaymentProfile::id)

        if (configuredRoutes.isEmpty()) {
            clientFactory.create(
                NetworkConfig(
                    chainId = policy.chainId,
                    rpcUrl = rpcUrl,
                    factory = policy.factory,
                    receiverImplementation = policy.receiverImplementation,
                    // Only chain/deployment identity is read on an unprovisioned network.
                    vault = policy.fixtureVault,
                ),
            ).use { client ->
                verifyKnownChainEndpoint(
                    client = client,
                    expectedChainId = policy.chainId,
                    expectedFactory = policy.factory,
                    expectedImplementation = policy.receiverImplementation,
                )
            }
            return@runInterruptible
        }

        configuredRoutes.forEach { route -> verifyConfiguredRoute(route, rpcUrl) }
    }

    private fun verifyKnownChainEndpoint(
        client: ProvisioningChainReader,
        expectedChainId: Long,
        expectedFactory: EvmAddress,
        expectedImplementation: EvmAddress,
    ) {
        require(client.chainId() == expectedChainId) {
            "RPC endpoint does not serve the selected Base network"
        }
        require(client.vaultRuntimeCode(expectedFactory).isNotEmpty()) {
            "RPC endpoint did not return the known OPK factory"
        }
        require(client.vaultRuntimeCode(expectedImplementation).isNotEmpty()) {
            "RPC endpoint did not return the known receiver implementation"
        }
        require(client.factoryImplementation(expectedFactory) == expectedImplementation) {
            "RPC endpoint returned a different OPK receiver implementation"
        }
    }

    private fun verifyConfiguredRoute(route: TerminalPaymentProfile, rpcUrl: String) {
        val policy = KnownChainPolicy.requireProfile(route.chainId)
        require(route.factoryAddress.equals(policy.factory.value, ignoreCase = true)) {
            "Stored factory pin does not match the selected Base network"
        }
        require(
            route.receiverImplementationAddress.equals(
                policy.receiverImplementation.value,
                ignoreCase = true,
            ),
        ) { "Stored receiver implementation pin does not match the selected Base network" }

        val token = EvmAddress.parse(route.token.address)
        val evidence = clientFactory.create(
            NetworkConfig(
                chainId = policy.chainId,
                rpcUrl = rpcUrl,
                factory = policy.factory,
                receiverImplementation = policy.receiverImplementation,
                vault = EvmAddress.parse(route.vaultAddress),
            ),
        ).use { client ->
            client.validateWithEvidence(
                token = token,
                expectedDecimals = route.token.decimals,
                expectedSymbol = route.token.symbol,
            )
        }
        val validation = evidence.validation
        val runtimeHash = Numeric.toHexString(Hash.sha3(evidence.vaultRuntimeCode))
        require(runtimeHash.equals(policy.vaultRuntimeCodeHash, ignoreCase = true)) {
            "RPC endpoint returned unexpected vault runtime bytecode"
        }
        require(
            validation.chainId == policy.chainId &&
                validation.factory == policy.factory &&
                validation.receiverImplementation == policy.receiverImplementation &&
                validation.vault == EvmAddress.parse(route.vaultAddress) &&
                validation.token == token &&
                validation.tokenWhitelisted &&
                validation.tokenDecimals == route.token.decimals &&
                validation.tokenSymbol == route.token.symbol,
        ) { "RPC endpoint failed the configured payment-route validation" }
        if (NativeAsset.isNative(token)) {
            require(route.token.decimals == policy.nativeCurrencyDecimals)
            require(route.token.symbol == policy.nativeCurrencySymbol)
        }
    }
}
