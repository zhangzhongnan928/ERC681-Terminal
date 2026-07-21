package com.openpasskey.terminal.lifecycle

import com.openpasskey.terminal.chain.TerminalConfigSnapshot
import com.openpasskey.terminal.chain.resolvedPaymentProfiles
import com.openpasskey.terminal.provisioning.KnownChainPolicy
import com.openpasskey.terminal.settlement.OperatorResetGuard
import com.openpasskey.terminal.settlement.SettlementChainClient
import com.openpasskey.terminal.settlement.Web3jSettlementChainClient
import com.openpasskey.terminal.settlement.requireOperatorResetAllowed
import kotlinx.coroutines.CancellationException
import java.math.BigInteger

data class OperatorNativeBalances(
    val latest: BigInteger,
    val pending: BigInteger,
)

fun interface OperatorNativeBalanceReader {
    suspend fun read(operatorAddress: String): OperatorNativeBalances
}

class RpcOperatorNativeBalanceReader(
    private val configSnapshot: () -> TerminalConfigSnapshot,
    private val clientFactory: (String) -> SettlementChainClient = ::Web3jSettlementChainClient,
) : OperatorNativeBalanceReader {
    override suspend fun read(operatorAddress: String): OperatorNativeBalances {
        val config = configSnapshot()
        // A removed profile must not erase knowledge of a chain where this same EOA may still
        // hold gas. Check every network enabled by this app build, plus every configured chain,
        // before allowing irreversible key deletion.
        val chainIds = (
            config.resolvedPaymentProfiles().map { it.chainId } +
                KnownChainPolicy.enabledChainIds()
            ).distinct().sorted()
        return chainIds.fold(
            OperatorNativeBalances(BigInteger.ZERO, BigInteger.ZERO),
        ) { total, chainId ->
            val profile = KnownChainPolicy.requireProfile(chainId)
            val balance = clientFactory(profile.rpcUrl).use { client ->
                check(client.chainId() == profile.chainId) {
                    "Unable to verify operator balance: RPC chain ID mismatch"
                }
                OperatorNativeBalances(
                    latest = client.latestNativeBalance(operatorAddress),
                    pending = client.nativeBalance(operatorAddress),
                )
            }
            OperatorNativeBalances(
                latest = total.latest + balance.latest,
                pending = total.pending + balance.pending,
            )
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

    private suspend fun readBalancesFailClosed(operatorAddress: String): OperatorNativeBalances =
        try {
            nativeBalanceReader.read(operatorAddress)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw IllegalStateException(
                "Unable to prove that the operator wallet is empty; reset was cancelled",
                error,
            )
        }
}

internal fun requireOperatorNativeBalancesEmpty(balances: OperatorNativeBalances) {
    check(balances.latest.signum() >= 0 && balances.pending.signum() >= 0) {
        "Operator native balance response is invalid; reset was cancelled"
    }
    check(balances.latest == BigInteger.ZERO && balances.pending == BigInteger.ZERO) {
        "Withdraw all native gas before reset (latest ${balances.latest} wei, " +
            "pending ${balances.pending} wei)"
    }
}
