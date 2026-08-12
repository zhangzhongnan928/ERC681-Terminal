package com.openpasskey.terminal.viewmodel

import com.openpasskey.erc681.RpcHttpRateLimitException
import com.openpasskey.terminal.chain.TerminalConfigSnapshot
import com.openpasskey.terminal.lifecycle.TerminalLifecycleGate
import com.openpasskey.terminal.rpc.RpcEndpointNotConfiguredException
import com.openpasskey.terminal.rpc.RpcEndpointSource
import com.openpasskey.terminal.rpc.RpcEndpointStorageException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RpcEndpointSettingsPolicyTest {
    @Test
    fun endpointIsLocallyValidatedAndProvedBeforeAuthorizedStorageWrite() = runBlocking {
        val session = AdminSessionGate()
        val unlockEpoch = session.beginUnlock()
        assertTrue(session.completeUnlock(unlockEpoch))
        val authorizationEpoch = requireNotNull(session.unlockedEpochOrNull())
        val events = mutableListOf<String>()

        updateRpcEndpointExclusively(
            lifecycleGate = TerminalLifecycleGate(),
            chainId = 8453,
            rawRpcUrl = ENDPOINT,
            currentConfiguration = {
                events += "snapshot"
                unprovisionedConfig()
            },
            validateCandidate = { candidate ->
                assertEquals(ENDPOINT, candidate)
                events += "local-validation"
            },
            verify = { chainId, candidate, configuration ->
                assertEquals(8453, chainId)
                assertEquals(ENDPOINT, candidate)
                assertFalse(configuration.provisioned)
                events += "on-chain-proof"
            },
            commitWithAuthorization = { commit ->
                events += "authorization"
                session.withAuthorization(authorizationEpoch, commit)
            },
            update = { chainId, candidate ->
                assertEquals(8453, chainId)
                assertEquals(ENDPOINT, candidate)
                events += "encrypted-write"
                true
            },
        )

        assertEquals(
            listOf(
                "authorization",
                "local-validation",
                "snapshot",
                "on-chain-proof",
                "authorization",
                "encrypted-write",
            ),
            events,
        )
    }

    @Test
    fun localValidationFailureNeverContactsEndpointOrWritesStorage() = runBlocking {
        var verified = false
        var written = false

        val failure = runCatching {
            updateRpcEndpointExclusively(
                lifecycleGate = TerminalLifecycleGate(),
                chainId = 8453,
                rawRpcUrl = "http://provider.invalid/secret",
                currentConfiguration = ::unprovisionedConfig,
                validateCandidate = { throw IllegalArgumentException("RPC URL must use HTTPS.") },
                verify = { _, _, _ -> verified = true },
                commitWithAuthorization = { commit -> commit() },
                update = { _, _ ->
                    written = true
                    true
                },
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertFalse(verified)
        assertFalse(written)
    }

    @Test
    fun expiredAdminEpochRejectsEndpointBeforeValidationOrNetworkUse() = runBlocking {
        val session = AdminSessionGate()
        val unlockEpoch = session.beginUnlock()
        assertTrue(session.completeUnlock(unlockEpoch))
        val authorizationEpoch = requireNotNull(session.unlockedEpochOrNull())
        session.lock()
        var locallyValidated = false
        var verified = false

        val failure = runCatching {
            updateRpcEndpointExclusively(
                lifecycleGate = TerminalLifecycleGate(),
                chainId = 8453,
                rawRpcUrl = ENDPOINT,
                currentConfiguration = ::unprovisionedConfig,
                validateCandidate = { locallyValidated = true },
                verify = { _, _, _ -> verified = true },
                commitWithAuthorization = { commit ->
                    session.withAuthorization(authorizationEpoch, commit)
                },
                update = { _, _ -> true },
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertFalse(locallyValidated)
        assertFalse(verified)
    }

    @Test
    fun clearingOverrideAlsoRequiresCurrentAdminAuthorization() = runBlocking {
        val session = AdminSessionGate()
        val unlockEpoch = session.beginUnlock()
        assertTrue(session.completeUnlock(unlockEpoch))
        val authorizationEpoch = requireNotNull(session.unlockedEpochOrNull())
        var clears = 0

        clearRpcEndpointExclusively(
            lifecycleGate = TerminalLifecycleGate(),
            chainId = 84532,
            commitWithAuthorization = { commit ->
                session.withAuthorization(authorizationEpoch, commit)
            },
            clear = {
                clears += 1
                true
            },
        )
        assertEquals(1, clears)

        session.lock()
        val failure = runCatching {
            clearRpcEndpointExclusively(
                lifecycleGate = TerminalLifecycleGate(),
                chainId = 84532,
                commitWithAuthorization = { commit ->
                    session.withAuthorization(authorizationEpoch, commit)
                },
                clear = {
                    clears += 1
                    true
                },
            )
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals(1, clears)
    }

    @Test
    fun endpointErrorsNeverEchoUnknownCredentialBearingMessages() {
        val secret = "https://provider.example/v2/client-secret-value"

        val unknown = rpcEndpointMutationFailureMessage(IllegalStateException(secret))
        assertFalse(unknown.contains("client-secret-value"))
        assertEquals(
            "Unable to verify or save the RPC endpoint. Check the URL and client credential, then try again.",
            unknown,
        )
        assertEquals(
            "RPC URL must use HTTPS.",
            rpcEndpointMutationFailureMessage(
                IllegalArgumentException("RPC URL must use HTTPS."),
            ),
        )
        assertEquals(
            "Unable to protect the RPC endpoint on this device.",
            rpcEndpointMutationFailureMessage(
                RpcEndpointStorageException("Unable to protect the RPC endpoint on this device."),
            ),
        )
        assertEquals(
            "The selected RPC provider is busy. Wait a moment and try again.",
            rpcEndpointMutationFailureMessage(RpcHttpRateLimitException()),
        )
        assertEquals(
            "Configure a dedicated HTTPS RPC endpoint in Admin/setup before using this Base network.",
            terminalRpcFailureMessage(
                RpcEndpointNotConfiguredException(),
                "Unable to validate terminal readiness",
            ),
        )
    }

    @Test
    fun provisioningRequiresAUsableEndpointForTheQrNetwork() {
        assertEquals(
            "Configure a dedicated Base Mainnet RPC endpoint in Admin/setup before scanning the " +
                "merchant portal QR.",
            rpcEndpointProvisioningPrerequisiteMessage(
                networkName = "Base Mainnet",
                source = RpcEndpointSource.MISSING,
            ),
        )
        assertEquals(
            "Replace the unavailable Base Sepolia RPC endpoint in Admin/setup before scanning the " +
                "merchant portal QR.",
            rpcEndpointProvisioningPrerequisiteMessage(
                networkName = "Base Sepolia",
                source = RpcEndpointSource.UNAVAILABLE,
            ),
        )
        assertEquals(
            null,
            rpcEndpointProvisioningPrerequisiteMessage(
                networkName = "Base Mainnet",
                source = RpcEndpointSource.ADMIN_OVERRIDE,
            ),
        )
    }

    private fun unprovisionedConfig() = TerminalConfigSnapshot(
        networkName = "Base Mainnet",
        rpcUrl = "https://mainnet.base.org",
        chainId = 8453,
        factoryAddress = "0x5418ab1790eaf96a20e26146c5b7765cb99328da",
        receiverImplementationAddress = "0xe6393f6176865cc62cd08d8b8f0c38d35af55254",
        vaultAddress = "",
        confirmationBlocks = 1,
        paymentTokens = emptyList(),
        protocolVersion = "",
        provisionedOperatorAddress = null,
        provisioned = false,
    )

    private companion object {
        const val ENDPOINT = "https://api.developer.coinbase.com/rpc/v1/base/client-key"
    }
}
