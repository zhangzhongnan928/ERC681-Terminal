package com.openpasskey.terminal.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.openpasskey.terminal.data.model.Invoice
import com.openpasskey.terminal.data.model.SettlementTransaction
import com.openpasskey.terminal.data.repository.PreparedSettlement
import com.openpasskey.terminal.settlement.SettlementAbi
import com.openpasskey.terminal.ui.components.DeviceAuthentication
import com.openpasskey.terminal.viewmodel.SettlementViewModel
import java.math.BigDecimal
import java.math.BigInteger

private data class SettlementGroupKey(
    val chainId: Long,
    val rpcUrl: String,
    val vault: String,
    val token: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettlementScreen(viewModel: SettlementViewModel) {
    val state by viewModel.state.collectAsState()
    val activity = LocalContext.current as? FragmentActivity
    val groups = state.readyInvoices
        .groupBy { SettlementGroupKey(it.chainId, it.rpcUrl, it.vaultAddress.lowercase(), it.token.lowercase()) }
        .values
        .flatMap { it.chunked(SettlementAbi.MAX_BATCH_SIZE) }

    state.prepared?.let { prepared ->
        SettlementReviewDialog(
            prepared = prepared,
            submitting = state.submitting,
            onDismiss = viewModel::dismissReview,
            onConfirm = {
                if (activity == null) {
                    viewModel.authenticationFailed("Unable to access the system authentication prompt")
                } else {
                    DeviceAuthentication.authenticate(
                        activity = activity,
                        title = "Authorize settlement",
                        subtitle = "Confirm the sweep transaction you just reviewed",
                        onAuthenticated = viewModel::submitAuthenticated,
                        onError = viewModel::authenticationFailed
                    )
                }
            }
        )
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Settle") }) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("Ready to sweep", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Every transaction is simulated, reviewed, device-authenticated, and verified from its confirmed receipt.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (groups.isEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Text(
                            "No confirmed invoices are ready. A proven partial invoice becomes retryable only when its receiver has a new positive balance; ambiguous proof stays in History for review.",
                            Modifier.padding(16.dp)
                        )
                    }
                }
            }
            items(groups, key = { group -> group.joinToString(":") { it.invoiceId } }) { group ->
                SettlementGroupCard(
                    invoices = group,
                    busy = state.preparing || state.submitting,
                    onPrepare = viewModel::prepare
                )
            }
            if (state.preparing) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
            state.message?.let { message ->
                item {
                    Text(
                        message,
                        color = if (state.isError) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary
                    )
                }
            }
            if (state.recentTransactions.isNotEmpty()) {
                item { Text("Settlement activity", style = MaterialTheme.typography.titleLarge) }
                items(state.recentTransactions, key = SettlementTransaction::id) { transaction ->
                    SettlementTransactionCard(transaction)
                }
            }
        }
    }
}

@Composable
private fun SettlementGroupCard(
    invoices: List<Invoice>,
    busy: Boolean,
    onPrepare: (List<String>) -> Unit
) {
    val first = invoices.first()
    val total = invoices.fold(BigInteger.ZERO) { sum, invoice ->
        sum + BigInteger(invoice.expectedAmount)
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${first.tokenSymbol} · ${first.networkName}", style = MaterialTheme.typography.titleMedium)
            Text("${invoices.size} invoice(s) · ${formatRaw(total.toString(), first.tokenDecimals)} ${first.tokenSymbol}")
            Text(
                "Vault ${compact(first.vaultAddress)}",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            )
            invoices.forEach { invoice ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(formatRaw(invoice.expectedAmount, invoice.tokenDecimals) + " " + invoice.tokenSymbol)
                        if (invoice.status.name == "PARTIALLY_SETTLED") {
                            Text("Partial retry · original expected amount", color = MaterialTheme.colorScheme.error)
                        }
                        Text(compact(invoice.invoiceId), style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(
                        enabled = !busy,
                        onClick = { onPrepare(listOf(invoice.invoiceId)) }
                    ) { Text("Review one") }
                }
            }
            if (invoices.size > 1) {
                Button(
                    enabled = !busy,
                    onClick = { onPrepare(invoices.map(Invoice::invoiceId)) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Review batch (${invoices.size})") }
            }
        }
    }
}

@Composable
private fun SettlementReviewDialog(
    prepared: PreparedSettlement,
    submitting: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text("Confirm settlement") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("${prepared.invoiceIds.size} invoice(s) · ${prepared.tokenSymbol}")
                ReviewLine("Network", "${prepared.networkName} (${prepared.chainId})")
                ReviewLine("Operator", compact(prepared.operatorAddress))
                ReviewLine("Vault", compact(prepared.vaultAddress))
                ReviewLine(
                    "Expected total",
                    formatRaw(prepared.totalExpectedAmount.toString(), prepared.tokenDecimals) + " " + prepared.tokenSymbol
                )
                ReviewLine(
                    "Observed total",
                    formatRaw(prepared.totalObservedAmount.toString(), prepared.tokenDecimals) + " " + prepared.tokenSymbol
                )
                ReviewLine("Nonce", prepared.nonce.toString())
                ReviewLine("Gas limit", prepared.gasLimit.toString())
                ReviewLine("Maximum fee", formatNative(prepared.maximumGasCost))
                ReviewLine("Safety/L1 reserve", formatNative(prepared.safetyReserve))
                ReviewLine("Required balance", formatNative(prepared.requiredBalance))
                ReviewLine("Current balance", formatNative(prepared.currentBalance))
                Text(
                    "Confirming will sign and broadcast sweepSessions. Fees are rechecked and an increase above 20% requires a new review. Settlement is only recorded after ${prepared.requiredConfirmations} confirmation(s) and matching Swept proof.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = !submitting) {
                if (submitting) CircularProgressIndicator() else Text("Authenticate & settle")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !submitting) { Text("Cancel") }
        }
    )
}

@Composable
private fun ReviewLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SettlementTransactionCard(transaction: SettlementTransaction) {
    val needsReview = transaction.status.name.contains("PARTIAL") ||
        transaction.status.name.contains("VERIFICATION_FAILED")
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(transaction.status.name.replace('_', ' '), style = MaterialTheme.typography.titleMedium)
            Text("${transaction.tokenSymbol} · ${compact(transaction.txHash)}", fontFamily = FontFamily.Monospace)
            if (needsReview) {
                Text(
                    "Needs review — this record does not prove every invoice was fully swept.",
                    color = MaterialTheme.colorScheme.error
                )
            }
            transaction.error?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private fun formatNative(wei: BigInteger): String =
    BigDecimal(wei).movePointLeft(18).stripTrailingZeros().toPlainString() + " native"

private fun compact(value: String): String =
    if (value.length <= 24) value else value.take(10) + "…" + value.takeLast(8)
