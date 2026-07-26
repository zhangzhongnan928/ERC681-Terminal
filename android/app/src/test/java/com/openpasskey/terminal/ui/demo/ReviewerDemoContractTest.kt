package com.openpasskey.terminal.ui.demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewerDemoContractTest {
    @Test
    fun safetyLabelsAndSampleUriAreExplicit() {
        assertEquals("OFFLINE DEMO", ReviewerDemoCopy.DEMO_LABEL)
        assertEquals("BASE SEPOLIA TESTNET", ReviewerDemoCopy.NETWORK_LABEL)
        assertEquals("NO REAL FUNDS", ReviewerDemoCopy.FUNDS_LABEL)
        assertEquals(
            "OFFLINE DEMO · BASE SEPOLIA TESTNET · SIMULATED · NO NETWORK · NO REAL FUNDS",
            ReviewerDemoCopy.BANNER_LABEL,
        )
        assertTrue(ReviewerDemoCopy.SAFETY_EXPLANATION.contains("Nothing leaves this screen"))
        assertTrue(ReviewerDemoCopy.RESET_EXPLANATION.contains("memory only"))
        assertEquals("1.00", ReviewerDemoCopy.SAMPLE_AMOUNT)
        assertEquals("USDC", ReviewerDemoCopy.SAMPLE_TOKEN)
        assertEquals(
            "ethereum:0x1111111111111111111111111111111111111111@84532/transfer" +
                "?address=0x2222222222222222222222222222222222222222&uint256=1000000",
            ReviewerDemoCopy.SAMPLE_ERC681_URI,
        )
    }

    @Test
    fun paymentSimulationMovesOnlyInMemoryStateFromWaitingToPaid() {
        val initial = newReviewerDemoState()

        val paid = initial.reduce(ReviewerDemoAction.SimulatePayment)

        assertEquals(ReviewerDemoPaymentStatus.WAITING, initial.paymentStatus)
        assertEquals(ReviewerDemoPaymentStatus.PAID, paid.paymentStatus)
        assertEquals(ReviewerDemoSection.CHECKOUT, paid.section)
    }

    @Test
    fun aNewSessionResetsAfterThePreviousSessionChanged() {
        val changed = newReviewerDemoState()
            .reduce(ReviewerDemoAction.SimulatePayment)
            .reduce(ReviewerDemoAction.Navigate(ReviewerDemoSection.SETTLEMENT))

        val reopened = newReviewerDemoState()

        assertEquals(ReviewerDemoPaymentStatus.PAID, changed.paymentStatus)
        assertEquals(ReviewerDemoSection.SETTLEMENT, changed.section)
        assertEquals(ReviewerDemoPaymentStatus.WAITING, reopened.paymentStatus)
        assertEquals(ReviewerDemoSection.CHECKOUT, reopened.section)
    }

    @Test
    fun settlementAttemptIsTheSameObjectAndCannotMutateDemoState() {
        val state = ReviewerDemoState(
            section = ReviewerDemoSection.SETTLEMENT,
            paymentStatus = ReviewerDemoPaymentStatus.PAID,
        )

        val afterAttempt = state.reduce(ReviewerDemoAction.AttemptSettlement)

        assertSame(state, afterAttempt)
        assertEquals("Settlement disabled in demo", ReviewerDemoCopy.SETTLEMENT_DISABLED_LABEL)
    }

    @Test
    fun resetReturnsTheExactInitialState() {
        val changed = ReviewerDemoState(
            section = ReviewerDemoSection.SETTLEMENT,
            paymentStatus = ReviewerDemoPaymentStatus.PAID,
        )

        assertEquals(
            newReviewerDemoState(),
            changed.reduce(ReviewerDemoAction.ResetPayment),
        )
    }
}
