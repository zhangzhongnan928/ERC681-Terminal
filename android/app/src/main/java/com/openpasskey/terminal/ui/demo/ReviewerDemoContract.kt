package com.openpasskey.terminal.ui.demo

/**
 * Static, non-secret sample content for the offline product tour.
 *
 * This contract deliberately contains only immutable values and a pure state reducer. It has no
 * Android, repository, persistence, wallet, authentication, signing, or network dependencies.
 */
internal object ReviewerDemoCopy {
    const val BANNER_LABEL =
        "OFFLINE DEMO · BASE MAINNET FORMAT · SIMULATED · NO NETWORK · NO REAL FUNDS"
    const val DEMO_LABEL = "OFFLINE DEMO"
    const val NETWORK_LABEL = "BASE MAINNET FORMAT"
    const val FUNDS_LABEL = "NO REAL FUNDS"
    const val SAFETY_EXPLANATION =
        "Offline product tour. Nothing leaves this screen: no account, wallet, PIN, " +
            "authentication, network request, signing, or transaction is used."
    const val RESET_EXPLANATION =
        "Demo activity is held in memory only and resets when you close this preview."
    const val SAMPLE_AMOUNT = "1.00"
    const val SAMPLE_TOKEN = "USDC"
    const val SAMPLE_CHAIN_ID = 8453L
    const val SAMPLE_RECEIVER = "0x2222222222222222222222222222222222222222"
    const val SAMPLE_VAULT = "0x3333333333333333333333333333333333333333"
    const val SAMPLE_OPERATOR = "0x4444444444444444444444444444444444444444"
    const val SAMPLE_DEMO_MARKER =
        "opk-demo:v1?network=base-mainnet&chainId=8453&simulated=true"
    const val SETTLEMENT_DISABLED_LABEL = "Settlement disabled in demo"
    const val SETTLEMENT_EXPLANATION =
        "Preview only — no authentication, signing, RPC, or broadcast is available."
}

internal enum class ReviewerDemoSection {
    CHECKOUT,
    HISTORY,
    SETTLEMENT,
}

internal enum class ReviewerDemoPaymentStatus {
    WAITING,
    PAID,
}

internal data class ReviewerDemoState(
    val section: ReviewerDemoSection = ReviewerDemoSection.CHECKOUT,
    val paymentStatus: ReviewerDemoPaymentStatus = ReviewerDemoPaymentStatus.WAITING,
)

internal sealed interface ReviewerDemoAction {
    data class Navigate(val section: ReviewerDemoSection) : ReviewerDemoAction
    data object SimulatePayment : ReviewerDemoAction
    data object ResetPayment : ReviewerDemoAction
    data object AttemptSettlement : ReviewerDemoAction
}

internal fun newReviewerDemoState(): ReviewerDemoState = ReviewerDemoState()

/**
 * Purely transforms in-memory demo state. The settlement action is intentionally inert.
 */
internal fun ReviewerDemoState.reduce(action: ReviewerDemoAction): ReviewerDemoState =
    when (action) {
        is ReviewerDemoAction.Navigate -> copy(section = action.section)
        ReviewerDemoAction.SimulatePayment -> copy(
            paymentStatus = ReviewerDemoPaymentStatus.PAID,
        )
        ReviewerDemoAction.ResetPayment -> newReviewerDemoState()
        ReviewerDemoAction.AttemptSettlement -> this
    }
