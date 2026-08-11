package com.openpasskey.terminal.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openpasskey.terminal.data.model.Invoice
import com.openpasskey.terminal.data.model.InvoiceStatus
import com.openpasskey.terminal.viewmodel.InvoiceViewModel
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(viewModel: InvoiceViewModel, onInvoice: (String) -> Unit) {
    val invoices by viewModel.recentInvoices.collectAsState()
    val receiptState by viewModel.receiptState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(receiptState.sequence) {
        val message = receiptState.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.consumeReceiptMessage(receiptState.sequence)
    }
    Scaffold(
        topBar = { TopAppBar(title = { Text("Payment History") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (invoices.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text("No invoices yet", style = MaterialTheme.typography.headlineSmall)
                Text("Created ERC-681 requests and observed payments will appear here.")
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(invoices, key = { it.invoiceId }) { invoice ->
                    HistoryCard(
                        invoice = invoice,
                        printing = receiptState.printingInvoiceId == invoice.invoiceId,
                        printerBusy = receiptState.printingInvoiceId != null,
                        onClick = { onInvoice(invoice.invoiceId) },
                        onPrint = { viewModel.reprintReceipt(invoice.invoiceId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryCard(
    invoice: Invoice,
    printing: Boolean,
    printerBusy: Boolean,
    onClick: () -> Unit,
    onPrint: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "${formatRaw(invoice.expectedAmount, invoice.tokenDecimals)} ${invoice.tokenSymbol}",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(invoice.status.name.lowercase().replaceFirstChar { it.uppercase() })
            }
            Text("${invoice.networkName} · chain ${invoice.chainId}")
            Text(
                "Received ${formatRaw(invoice.receivedAmount, invoice.tokenDecimals)} ${invoice.tokenSymbol}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(formatTime(invoice.createdAt), style = MaterialTheme.typography.bodySmall)
            invoice.paymentTxHash?.let { hash ->
                Text(
                    "Payment tx ${hash.take(10)}...${hash.takeLast(8)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (invoice.canPrintPrimaryReceipt()) {
                OutlinedButton(
                    onClick = onPrint,
                    enabled = !printerBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (printing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp).padding(end = 4.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    Text(if (invoice.receiptPrintedAt == null) "Print receipt" else "Reprint receipt")
                }
            }
        }
    }
}

internal fun Invoice.canPrintPrimaryReceipt(): Boolean =
    receiptAutoPrintEligible && receiptNumber > 0 && firstDetectedBlock != null &&
        status !in setOf(
            InvoiceStatus.WAITING,
            InvoiceStatus.PARTIAL,
            InvoiceStatus.CONFIRMING,
            InvoiceStatus.EXPIRED,
        )

internal fun formatRaw(raw: String, decimals: Int): String = runCatching {
    BigDecimal(raw).movePointLeft(decimals).stripTrailingZeros().toPlainString()
}.getOrDefault(raw)

private fun formatTime(epochSeconds: Long): String = runCatching {
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochSecond(epochSeconds))
}.getOrDefault(epochSeconds.toString())
