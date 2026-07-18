package com.openpasskey.terminal.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.openpasskey.terminal.chain.PaymentToken
import com.openpasskey.terminal.viewmodel.InvoiceViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InvoiceScreen(viewModel: InvoiceViewModel, onInvoiceCreated: (String) -> Unit) {
    val state by viewModel.createState.collectAsState()
    LaunchedEffect(Unit) { viewModel.refreshConfiguration() }
    LaunchedEffect(state.createdInvoice) {
        state.createdInvoice?.let {
            onInvoiceCreated(it.invoiceId)
            viewModel.consumeCreatedInvoice()
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("New ERC-681 Payment") }) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                if (state.amount.isBlank()) "0.00" else state.amount,
                style = MaterialTheme.typography.displayLarge
            )
            Spacer(Modifier.height(24.dp))
            TokenSelector(state.tokens, state.selectedToken, viewModel::selectToken)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = state.amount,
                onValueChange = viewModel::updateAmount,
                label = { Text("Amount") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "Before displaying a QR, the app checks RPC chain ID, deployment bytecode, " +
                    "vault factory, token whitelist, and an empty receiver balance.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            state.error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = viewModel::createInvoice,
                enabled = !state.isCreating && state.selectedToken != null && state.amount.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                if (state.isCreating) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                    Text("  Validating chain…")
                } else {
                    Icon(Icons.Default.QrCode, contentDescription = null)
                    Text("  Create payment QR")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TokenSelector(
    tokens: List<PaymentToken>,
    selected: PaymentToken?,
    onSelected: (PaymentToken) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = selected?.let { "${it.symbol} · ${short(it.address)}" } ?: "",
            onValueChange = {},
            readOnly = true,
            label = { Text("ERC-20 token") },
            placeholder = { Text(if (tokens.isEmpty()) "Add a token in Settings" else "Select token") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            tokens.forEach { token ->
                DropdownMenuItem(
                    text = { Text("${token.symbol} (${token.decimals}) · ${short(token.address)}") },
                    onClick = { onSelected(token); expanded = false }
                )
            }
        }
    }
}

private fun short(address: String): String =
    if (address.length <= 12) address else "${address.take(6)}…${address.takeLast(4)}"
