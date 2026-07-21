package com.openpasskey.terminal.provisioning

import com.openpasskey.erc681.EvmAddress
import com.openpasskey.erc681.NetworkConfig
import com.openpasskey.erc681.NetworkValidation
import com.openpasskey.erc681.ReadOnlyRpcClient
import com.openpasskey.terminal.chain.PaymentToken
import com.openpasskey.terminal.chain.TerminalConfigSnapshot
import com.openpasskey.terminal.chain.TerminalPaymentProfile
import com.openpasskey.terminal.chain.resolvedPaymentProfiles
import com.openpasskey.terminal.chain.selectedPaymentProfile
import com.openpasskey.terminal.chain.upsertingProfile
import com.openpasskey.terminal.lifecycle.TerminalLifecycleGate
import com.openpasskey.terminal.wallet.OperatorWalletAvailability
import com.openpasskey.terminal.wallet.OperatorWalletSnapshot
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.web3j.crypto.Hash
import org.web3j.utils.Numeric
import java.io.Closeable

class VaultRuntimeCodeHashMismatchException(
    val expected: String,
    val actual: String,
) : IllegalArgumentException(
    "Vault runtime bytecode hash $actual does not match trusted OPKBeaconProxy hash $expected",
)

interface ProvisioningChainReader : Closeable {
    fun chainId(): Long
    fun vaultRuntimeCode(vault: EvmAddress): ByteArray
    fun vaultFactory(vault: EvmAddress): EvmAddress
    fun factoryImplementation(factory: EvmAddress): EvmAddress
    fun isPaymentToken(vault: EvmAddress, token: EvmAddress): Boolean
    fun tokenDecimals(token: EvmAddress): Int
    fun tokenSymbol(token: EvmAddress): String
    fun validate(token: EvmAddress): NetworkValidation {
        val decimals = tokenDecimals(token)
        val symbol = tokenSymbol(token)
        return validate(token, decimals, symbol)
    }
    fun validateWithEvidence(token: EvmAddress): ProvisioningValidationEvidence {
        val validation = validate(token)
        return ProvisioningValidationEvidence(
            validation = validation,
            vaultRuntimeCode = vaultRuntimeCode(validation.vault),
        )
    }
    fun validate(token: EvmAddress, expectedDecimals: Int, expectedSymbol: String): NetworkValidation
}

/** Validation plus the exact vault bytes returned by the same provenance proof. */
class ProvisioningValidationEvidence(
    val validation: NetworkValidation,
    vaultRuntimeCode: ByteArray,
) {
    private val retainedVaultRuntimeCode = vaultRuntimeCode.copyOf()
    val vaultRuntimeCode: ByteArray
        get() = retainedVaultRuntimeCode.copyOf()
}

fun interface ProvisioningChainReaderFactory {
    fun create(config: NetworkConfig): ProvisioningChainReader
}

private class RpcProvisioningChainReader(
    private val client: ReadOnlyRpcClient,
) : ProvisioningChainReader {
    override fun chainId(): Long = client.chainId()
    override fun vaultRuntimeCode(vault: EvmAddress): ByteArray = client.codeAt(vault)
    override fun vaultFactory(vault: EvmAddress): EvmAddress = client.vaultFactory(vault)
    override fun factoryImplementation(factory: EvmAddress): EvmAddress =
        client.factoryImplementation(factory)

    override fun isPaymentToken(vault: EvmAddress, token: EvmAddress): Boolean =
        client.isPaymentToken(vault, token)

    override fun tokenDecimals(token: EvmAddress): Int = client.tokenDecimals(token)
    override fun tokenSymbol(token: EvmAddress): String = client.tokenSymbol(token)
    override fun validate(token: EvmAddress): NetworkValidation = client.validate(token)
    override fun validateWithEvidence(token: EvmAddress): ProvisioningValidationEvidence {
        val evidence = client.validateWithEvidence(token)
        return ProvisioningValidationEvidence(
            validation = evidence.validation,
            vaultRuntimeCode = evidence.vaultRuntimeCode,
        )
    }
    override fun validate(
        token: EvmAddress,
        expectedDecimals: Int,
        expectedSymbol: String,
    ): NetworkValidation = client.validate(token, expectedDecimals, expectedSymbol)

    override fun close() = Unit
}

data class ProvisioningResult(
    val configuration: TerminalConfigSnapshot,
    val token: PaymentToken,
    val profile: TerminalPaymentProfile,
)

/** Derives a complete candidate in memory and invokes the store exactly once after every check. */
class TerminalProvisioner(
    private val snapshot: () -> TerminalConfigSnapshot,
    private val compareAndCommit: (TerminalConfigSnapshot, TerminalConfigSnapshot) -> Boolean,
    private val currentWalletSnapshot: () -> OperatorWalletSnapshot,
    private val lifecycleGate: TerminalLifecycleGate,
    private val clientFactory: ProvisioningChainReaderFactory = ProvisioningChainReaderFactory { config ->
        RpcProvisioningChainReader(ReadOnlyRpcClient(config))
    },
) {
    suspend fun provision(
        rawPayload: String,
        wallet: OperatorWalletSnapshot,
        commitWithAuthorization: ((() -> Boolean) -> Boolean),
    ): ProvisioningResult {
        val payload = TerminalProvisioningPayloadCodec.parse(rawPayload)
        check(wallet.availability == OperatorWalletAvailability.READY && wallet.address != null) {
            wallet.error ?: "Create the terminal operator wallet before provisioning"
        }
        val localOperator = EvmAddress.parse(requireNotNull(wallet.address))
        require(payload.operator == localOperator) {
            "This provisioning QR was generated for a different terminal"
        }

        // Unknown chains and invalid shipped fixtures fail before any RPC client is constructed.
        val profile = KnownChainPolicy.requireProfile(payload.chainId)
        profile.requireValidCreate2Fixture()
        val previous = snapshot()
        val rpcUrl = selectRpcUrl(previous, profile, payload.vault)
        val operationalNetwork = NetworkConfig(
            chainId = profile.chainId,
            rpcUrl = rpcUrl,
            factory = profile.factory,
            receiverImplementation = profile.receiverImplementation,
            vault = payload.vault,
        )
        val trustedNetwork = operationalNetwork.copy(rpcUrl = profile.rpcUrl)

        val token = if (rpcUrl == profile.rpcUrl) {
            clientFactory.create(trustedNetwork).use { trusted ->
                // validateWithEvidence anchors and verifies the trusted chain before provenance
                // reads, so a standalone chainId round trip here would be redundant.
                validateTrustedProvenance(trusted, profile, payload)
            }
        } else {
            // A persisted/admin-supplied endpoint is useful operationally, but it is not a trust
            // root. It gets exactly one capability here: proving that it serves the selected chain.
            clientFactory.create(operationalNetwork).use { operational ->
                require(operational.chainId() == profile.chainId) {
                    "Operational RPC chain ID mismatch"
                }
            }
            clientFactory.create(trustedNetwork).use { trusted ->
                validateTrustedProvenance(trusted, profile, payload)
            }
        }

        val paymentProfile = TerminalPaymentProfile(
            networkName = profile.networkName,
            rpcUrl = rpcUrl,
            chainId = profile.chainId,
            factoryAddress = profile.factory.value,
            receiverImplementationAddress = profile.receiverImplementation.value,
            vaultAddress = payload.vault.value,
            confirmationBlocks = profile.defaultConfirmationBlocks,
            token = token,
            protocolVersion = profile.protocolVersion,
        )
        // A v1 portal QR still describes one complete profile. Repeated scans now upsert that
        // profile into the local catalog instead of replacing other vault/token/network choices.
        val candidate = previous.upsertingProfile(paymentProfile, payload.operator.value)
        currentCoroutineContext().ensureActive()
        lifecycleGate.withExclusiveMutation {
            currentCoroutineContext().ensureActive()
            val currentWallet = currentWalletSnapshot()
            check(
                currentWallet.availability == OperatorWalletAvailability.READY &&
                    currentWallet.address != null &&
                    EvmAddress.parse(currentWallet.address) == payload.operator,
            ) {
                "Terminal operator wallet changed during provisioning; scan the portal QR again"
            }
            // The authorization gate wraps the commit itself, making background-lock and commit
            // linearizable instead of leaving a check-then-write race between the two operations.
            check(commitWithAuthorization { compareAndCommit(previous, candidate) }) {
                "Terminal configuration changed during provisioning; scan the portal QR again"
            }
        }
        return ProvisioningResult(
            candidate,
            token,
            requireNotNull(candidate.selectedPaymentProfile()),
        )
    }

    private fun validateTrustedProvenance(
        client: ProvisioningChainReader,
        profile: KnownChainProfile,
        payload: TerminalProvisioningPayload,
    ): PaymentToken {
        val evidence = client.validateWithEvidence(payload.token)
        val actualVaultRuntimeCodeHash = Numeric.toHexString(
            Hash.sha3(evidence.vaultRuntimeCode),
        )
        if (actualVaultRuntimeCodeHash != profile.vaultRuntimeCodeHash) {
            throw VaultRuntimeCodeHashMismatchException(
                expected = profile.vaultRuntimeCodeHash,
                actual = actualVaultRuntimeCodeHash,
            )
        }
        // The validation evidence already contains the vault bytes used above, so provisioning
        // never repeats eth_getCode(vault) after checking the trusted runtime hash.
        val validation = evidence.validation
        require(validation.chainId == profile.chainId)
        require(validation.factory == profile.factory)
        require(validation.receiverImplementation == profile.receiverImplementation)
        require(validation.vault == payload.vault)
        require(validation.token == payload.token && validation.tokenWhitelisted)
        return PaymentToken(
            payload.token.value,
            validation.tokenSymbol,
            validation.tokenDecimals,
        )
    }

    private fun selectRpcUrl(
        previous: TerminalConfigSnapshot,
        profile: KnownChainProfile,
        vault: EvmAddress,
    ): String {
        val existingRpc = previous.resolvedPaymentProfiles()
            .firstOrNull { it.chainId == profile.chainId }
            ?.rpcUrl
            ?: previous.rpcUrl.takeIf { previous.chainId == profile.chainId }
            ?: return profile.rpcUrl
        val overrideIsValid = runCatching {
            NetworkConfig(
                chainId = profile.chainId,
                rpcUrl = existingRpc,
                factory = profile.factory,
                receiverImplementation = profile.receiverImplementation,
                vault = vault,
            )
        }.isSuccess
        return if (overrideIsValid) existingRpc else profile.rpcUrl
    }
}
