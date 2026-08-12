package com.openpasskey.terminal.lifecycle

import com.openpasskey.terminal.chain.TerminalConfigSnapshot
import com.openpasskey.terminal.chain.resolvedPaymentProfiles
import com.openpasskey.terminal.provisioning.KnownChainPolicy
import com.openpasskey.terminal.rpc.RpcEndpointResolver
import com.openpasskey.terminal.settlement.OperatorResetGuard
import com.openpasskey.terminal.settlement.SettlementChainClient
import com.openpasskey.terminal.settlement.Web3jSettlementChainClient
import com.openpasskey.terminal.settlement.requireOperatorResetAllowed
import kotlinx.coroutines.CancellationException
import java.math.BigInteger

data class OperatorNativeBalances(
    val networkName: String,
    val chainId: Long,
    val nativeCurrencySymbol: String,
    val latest: BigInteger,
    val pending: BigInteger,
)

fun interface OperatorNativeBalanceReader {
    suspend fun read(operatorAddress: String): List<OperatorNativeBalances>
}

private class OperatorBalanceReadException(message: String) : IllegalStateException(message)

class RpcOperatorNativeBalanceReader(
    private val configSnapshot: () -> TerminalConfigSnapshot,
    private val clientFactory: (String) -> SettlementChainClient = ::Web3jSettlementChainClient,
    private val rpcEndpointResolver: RpcEndpointResolver = RpcEndpointResolver.PASSTHROUGH,
) : OperatorNativeBalanceReader {
    override suspend fun read(operatorAddress: String): List<OperatorNativeBalances> {
        val config = configSnapshot()
        // A removed profile must not erase knowledge of a chain where this same EOA may still
        // hold gas. Check every network enabled by this app build, plus every configured chain,
        // before allowing irreversible key deletion.
        val chainIds = (
            config.resolvedPaymentProfiles().map { it.chainId } +
                KnownChainPolicy.enabledChainIds()
            ).distinct().sorted()
        return chainIds.map { chainId ->
            val profile = KnownChainPolicy.requireProfile(chainId)
            try {
                clientFactory(
                    rpcEndpointResolver.resolve(profile.chainId, profile.rpcUrl),
                ).use { client ->
                    val actualChainId = client.chainId()
                    if (actualChainId != profile.chainId) {
                        throw OperatorBalanceReadException(
                            "Unable to verify operator balance on ${profile.networkName} " +
                                "(chain ${profile.chainId}); reset was cancelled. " +
                                "RPC reported chain $actualChainId instead of ${profile.chainId}",
                        )
                    }
                    OperatorNativeBalances(
                        networkName = profile.networkName,
                        chainId = profile.chainId,
                        nativeCurrencySymbol = profile.nativeCurrencySymbol,
                        latest = client.latestNativeBalance(operatorAddress),
                        pending = client.nativeBalance(operatorAddress),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: OperatorBalanceReadException) {
                throw error
            } catch (error: Exception) {
                throw OperatorBalanceReadException(
                    "Unable to verify operator balance on ${profile.networkName} " +
                        "(chain ${profile.chainId}); reset was cancelled. RPC balance read failed",
                )
            }
        }
    }
}

/** Fail-closed reset order: prove empty twice, block issued work, clear, then delete the wallet. */
class TerminalResetCoordinator(
    private val lifecycleGate: TerminalLifecycleGate,
    private val resetGuard: OperatorResetGuard,
    private val nativeBalanceReader: OperatorNativeBalanceReader,
    private val clearProvisioning: () -> Boolean,
    private val deleteWallet: () -> Unit,
) {
    suspend fun reset(
        operatorAddress: String,
        commitWithAuthorization: ((() -> Unit) -> Unit),
    ) {
        // A preliminary read gives the operator a clear failure before any local mutation. The
        // second read under the process gate closes the in-app race immediately before deletion.
        requireOperatorNativeBalancesEmpty(readBalancesFailClosed(operatorAddress))
        lifecycleGate.withExclusiveMutation {
            requireOperatorResetAllowed(resetGuard.hasBlockingState(operatorAddress))
            requireOperatorNativeBalancesEmpty(readBalancesFailClosed(operatorAddress))
            // Authorization wraps the destructive commit itself. A background lock that wins
            // during either RPC read therefore prevents both configuration clear and key deletion;
            // if the commit wins, clear+delete complete before that lock can take effect.
            commitWithAuthorization {
                check(clearProvisioning()) { "Unable to clear terminal provisioning" }
                // If this throws or the process dies, provisioning is already durably cleared.
                deleteWallet()
            }
        }
    }

    private suspend fun readBalancesFailClosed(operatorAddress: String): List<OperatorNativeBalances> =
        try {
            nativeBalanceReader.read(operatorAddress)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val safeDetail = if (error is OperatorBalanceReadException) {
                error.message ?: "Native-balance read failed"
            } else {
                "Native-balance read failed"
            }
            throw IllegalStateException(
                "Unable to prove that the operator wallet is empty; reset was cancelled. " +
                    safeDetail,
            )
        }
}

internal fun requireOperatorNativeBalancesEmpty(balances: List<OperatorNativeBalances>) {
    check(balances.isNotEmpty()) {
        "No trusted network balances were returned; reset was cancelled"
    }
    balances.forEach { balance ->
        val network = "${balance.networkName} (chain ${balance.chainId})"
        check(balance.latest.signum() >= 0 && balance.pending.signum() >= 0) {
            "Operator native balance response is invalid on $network; reset was cancelled"
        }
        check(balance.latest == BigInteger.ZERO && balance.pending == BigInteger.ZERO) {
            "Withdraw all ${balance.nativeCurrencySymbol} from the operator on $network before " +
                "reset (latest ${balance.latest} wei, pending ${balance.pending} wei)"
        }
    }
}
