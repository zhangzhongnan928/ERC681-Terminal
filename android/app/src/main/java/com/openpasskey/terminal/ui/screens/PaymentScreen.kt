package com.openpasskey.terminal.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.openpasskey.terminal.data.model.Invoice
import com.openpasskey.terminal.data.model.InvoiceStatus
import com.openpasskey.terminal.ui.components.QRCodeView
import com.openpasskey.terminal.viewmodel.InvoiceViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaymentScreen(invoiceId: String, viewModel: InvoiceViewModel, onBack: () -> Unit) {
    val state by viewModel.paymentState.collectAsState()
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(invoiceId) { viewModel.startPaymentMonitoring(invoiceId) }
    DisposableEffect(invoiceId) { onDispose { viewModel.stopPaymentMonitoring() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Payment") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        val invoice = state.invoice
        if (invoice == null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (state.error == null) CircularProgressIndicator()
                Text(state.error ?: "Loading invoice…")
            }
            return@Scaffold
        }
        PaymentContent(
            invoice = invoice,
            error = state.error,
            onCopyUri = { clipboard.setText(AnnotatedString(invoice.erc681Uri)) },
            onCancel = { viewModel.cancelInvoice(); onBack() },
            modifier = Modifier.padding(padding)
        )
    }
}

@Composable
private fun PaymentContent(
    invoice: Invoice,
    error: String?,
    onCopyUri: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val open = invoice.status in listOf(
        InvoiceStatus.WAITING,
        InvoiceStatus.PARTIAL,
        InvoiceStatus.CONFIRMING
    )
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            "${formatRaw(invoice.expectedAmount, invoice.tokenDecimals)} ${invoice.tokenSymbol}",
            style = MaterialTheme.typography.displayMedium,
            textAlign = TextAlign.Center
        )
        StatusCard(invoice)
        if (open) QRCodeView(invoice.erc681Uri, size = 260.dp)
        Text(
            if (open) "Scan with a compatible wallet" else "Payment observation complete",
            style = MaterialTheme.typography.titleMedium
        )
        DetailCard(invoice)
        error?.let {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "RPC status unknown: $it",
                    modifier = Modifier.padding(14.dp),
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        OutlinedButton(onClick = onCopyUri, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.ContentCopy, contentDescription = null)
            Text(" Copy ERC-681 URI")
        }
        if (open) {
            Button(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Close invoice") }
            Text(
                "Closing is local and cannot revoke a QR already scanned.",
                style = MaterialTheme.typography.bodySmall
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StatusCard(invoice: Invoice) {
    val (title, detail) = when (invoice.status) {
        InvoiceStatus.WAITING -> "Waiting" to "No payment detected"
        InvoiceStatus.PARTIAL -> "Partial payment" to "More funds are required"
        InvoiceStatus.CONFIRMING -> "Confirming" to "Amount received; waiting for confirmation blocks"
        InvoiceStatus.PAID -> "Paid" to "Expected amount confirmed"
        InvoiceStatus.OVERPAID -> "Overpaid" to "More than the requested amount was confirmed"
        InvoiceStatus.PARTIALLY_SETTLED -> "Partially settled" to
            "A sweep moved less than expected; this receiver remains under reconciliation"
        InvoiceStatus.LATE_PAYMENT_CONFIRMING -> if (invoice.settlementAmbiguous) {
            "Recovery payment confirming" to
                "Value is present, but its exact balance must remain stable through confirmation"
        } else {
            "Late payment confirming" to
                "New value arrived after a prior sweep and is waiting for confirmation blocks"
        }
        InvoiceStatus.LATE_PAYMENT_READY -> if (invoice.settlementAmbiguous) {
            "Recovery payment ready" to
                "Confirmed positive value can be swept; review remains until canonical proof covers the invoice"
        } else {
            "Late payment ready" to
                "Confirmed value arrived after a prior sweep and is ready to sweep again"
        }
        InvoiceStatus.SETTLED -> "Settled" to "A confirmed receipt proves the funds were swept"
        InvoiceStatus.SETTLEMENT_REVIEW_REQUIRED -> "Settlement review" to
            "Receipt evidence is ambiguous. This receiver stays monitored and confirmed value can be recovered"
        InvoiceStatus.EXPIRED -> "Closed" to "This invoice was closed locally"
    }
    val observedAmount = if (
        invoice.status == InvoiceStatus.LATE_PAYMENT_CONFIRMING ||
        invoice.status == InvoiceStatus.LATE_PAYMENT_READY
    ) {
        invoice.pendingLateAmount
    } else {
        invoice.receivedAmount
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text(detail)
            Text(
                "Received ${formatRaw(observedAmount, invoice.tokenDecimals)} ${invoice.tokenSymbol}",
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}

@Composable
private fun DetailCard(invoice: Invoice) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Detail("Network", "${invoice.networkName} (${invoice.chainId})")
            Detail("Token", "${invoice.tokenSymbol} · ${invoice.token}")
            Detail("Receiver", invoice.receiver)
            Detail("Invoice", invoice.invoiceId)
            Text("ERC-681 URI", style = MaterialTheme.typography.labelMedium)
            Text(invoice.erc681Uri, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(
            if (value.length > 30) "${value.take(12)}…${value.takeLast(10)}" else value,
            style = MaterialTheme.typography.bodySmall
        )
    }
}
