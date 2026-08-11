package com.openpasskey.terminal.ui.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.openpasskey.terminal.ui.components.QRCodeView

/**
 * A self-contained offline product tour. Its only input is a close callback, and its state is held
 * with [remember] rather than saveable or persistent storage so every closed/relaunched session
 * starts at Checkout / Waiting.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReviewerDemoScreen(onClose: () -> Unit) {
    var state by remember { mutableStateOf(newReviewerDemoState()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Offline product tour") },
                navigationIcon = {
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.testTag("reviewer_demo_close"),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Close product tour",
                        )
                    }
                },
            )
        },
        bottomBar = {
            ReviewerDemoNavigation(
                selected = state.section,
                onSelected = { section ->
                    state = state.reduce(ReviewerDemoAction.Navigate(section))
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            ReviewerDemoSafetyBanner()
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    when (state.section) {
                        ReviewerDemoSection.CHECKOUT -> ReviewerDemoCheckout(
                            paymentStatus = state.paymentStatus,
                            onSimulatePayment = {
                                state = state.reduce(ReviewerDemoAction.SimulatePayment)
                            },
                            onResetPayment = {
                                state = state.reduce(ReviewerDemoAction.ResetPayment)
                            },
                        )
                        ReviewerDemoSection.HISTORY -> ReviewerDemoHistory(
                            paymentStatus = state.paymentStatus,
                        )
                        ReviewerDemoSection.SETTLEMENT -> ReviewerDemoSettlement(
                            paymentStatus = state.paymentStatus,
                            onAttemptSettlement = {
                                state = state.reduce(ReviewerDemoAction.AttemptSettlement)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewerDemoSafetyBanner() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .testTag("reviewer_demo_safety"),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                ReviewerDemoCopy.BANNER_LABEL,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Black,
            )
            Text(
                ReviewerDemoCopy.SAFETY_EXPLANATION,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                ReviewerDemoCopy.RESET_EXPLANATION,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ReviewerDemoCheckout(
    paymentStatus: ReviewerDemoPaymentStatus,
    onSimulatePayment: () -> Unit,
    onResetPayment: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Checkout preview", style = MaterialTheme.typography.headlineSmall)
        Text(
            "${ReviewerDemoCopy.SAMPLE_AMOUNT} ${ReviewerDemoCopy.SAMPLE_TOKEN}",
            style = MaterialTheme.typography.displaySmall,
            textAlign = TextAlign.Center,
        )
        DemoPaymentStatusCard(paymentStatus)
        QRCodeView(
            data = ReviewerDemoCopy.SAMPLE_DEMO_MARKER,
            size = 220.dp,
            contentDescription = "Non-payment demo marker QR code",
            modifier = Modifier.testTag("reviewer_demo_qr"),
        )
        Text(
            "Non-payment simulation marker generated locally for this demo",
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            ReviewerDemoCopy.SAMPLE_DEMO_MARKER,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
        if (paymentStatus == ReviewerDemoPaymentStatus.WAITING) {
            Button(
                onClick = onSimulatePayment,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("reviewer_demo_simulate_payment"),
            ) {
                Text("Simulate payment received")
            }
        } else {
            Button(
                onClick = onResetPayment,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("reviewer_demo_reset_payment"),
            ) {
                Text("Reset demo")
            }
        }
    }
}

@Composable
private fun DemoPaymentStatusCard(paymentStatus: ReviewerDemoPaymentStatus) {
    val paid = paymentStatus == ReviewerDemoPaymentStatus.PAID
    Card(Modifier.fillMaxWidth().testTag("reviewer_demo_payment_status")) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (paid) Icons.Default.CheckCircle else Icons.Default.HourglassTop,
                contentDescription = null,
                tint = if (paid) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.tertiary,
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (paid) "Paid" else "Waiting",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    if (paid) {
                        "Sample payment confirmed locally"
                    } else {
                        "No sample payment detected"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ReviewerDemoHistory(paymentStatus: ReviewerDemoPaymentStatus) {
    val paid = paymentStatus == ReviewerDemoPaymentStatus.PAID
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Payment history preview", style = MaterialTheme.typography.headlineSmall)
        Card(Modifier.fillMaxWidth().testTag("reviewer_demo_history_card")) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "${ReviewerDemoCopy.SAMPLE_AMOUNT} ${ReviewerDemoCopy.SAMPLE_TOKEN}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(if (paid) "Paid" else "Waiting")
                }
                Text("Base Mainnet format · chain ${ReviewerDemoCopy.SAMPLE_CHAIN_ID}")
                Text(
                    if (paid) {
                        "Received ${ReviewerDemoCopy.SAMPLE_AMOUNT} " +
                            ReviewerDemoCopy.SAMPLE_TOKEN
                    } else {
                        "Received 0 ${ReviewerDemoCopy.SAMPLE_TOKEN}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Demo session only · never saved",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ReviewerDemoSettlement(
    paymentStatus: ReviewerDemoPaymentStatus,
    onAttemptSettlement: () -> Unit,
) {
    val paid = paymentStatus == ReviewerDemoPaymentStatus.PAID
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Settlement preview", style = MaterialTheme.typography.headlineSmall)
        Card(Modifier.fillMaxWidth().testTag("reviewer_demo_settlement_card")) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    if (paid) "Ready to preview" else "Awaiting demo payment",
                    style = MaterialTheme.typography.titleLarge,
                )
                DemoDetail(
                    "Amount",
                    "${ReviewerDemoCopy.SAMPLE_AMOUNT} ${ReviewerDemoCopy.SAMPLE_TOKEN}",
                )
                DemoDetail("Network", "Base Mainnet format (${ReviewerDemoCopy.SAMPLE_CHAIN_ID})")
                DemoDetail("Receiver", ReviewerDemoCopy.SAMPLE_RECEIVER)
                DemoDetail("Vault", ReviewerDemoCopy.SAMPLE_VAULT)
                DemoDetail("Operator", ReviewerDemoCopy.SAMPLE_OPERATOR)
                Button(
                    onClick = onAttemptSettlement,
                    enabled = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("reviewer_demo_settlement_action"),
                ) {
                    Text(ReviewerDemoCopy.SETTLEMENT_DISABLED_LABEL)
                }
                Text(
                    ReviewerDemoCopy.SETTLEMENT_EXPLANATION,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DemoDetail(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
    }
}

@Composable
private fun ReviewerDemoNavigation(
    selected: ReviewerDemoSection,
    onSelected: (ReviewerDemoSection) -> Unit,
) {
    NavigationBar(modifier = Modifier.testTag("reviewer_demo_navigation")) {
        NavigationBarItem(
            selected = selected == ReviewerDemoSection.CHECKOUT,
            onClick = { onSelected(ReviewerDemoSection.CHECKOUT) },
            icon = { Icon(Icons.Default.PointOfSale, contentDescription = null) },
            label = { Text("Checkout") },
            modifier = Modifier.testTag("reviewer_demo_nav_checkout"),
        )
        NavigationBarItem(
            selected = selected == ReviewerDemoSection.HISTORY,
            onClick = { onSelected(ReviewerDemoSection.HISTORY) },
            icon = { Icon(Icons.Default.History, contentDescription = null) },
            label = { Text("History") },
            modifier = Modifier.testTag("reviewer_demo_nav_history"),
        )
        NavigationBarItem(
            selected = selected == ReviewerDemoSection.SETTLEMENT,
            onClick = { onSelected(ReviewerDemoSection.SETTLEMENT) },
            icon = { Icon(Icons.Default.SyncAlt, contentDescription = null) },
            label = { Text("Settlement") },
            modifier = Modifier.testTag("reviewer_demo_nav_settlement"),
        )
    }
}
