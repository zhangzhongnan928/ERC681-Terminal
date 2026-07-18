package com.openpasskey.terminal.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.openpasskey.terminal.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {
    val state by viewModel.state.collectAsState()
    var showTokenDialog by remember { mutableStateOf(false) }

    if (showTokenDialog) {
        AddTokenDialog(
            onDismiss = { showTokenDialog = false },
            onAdd = { address, symbol, decimals ->
                viewModel.addPaymentToken(address, symbol, decimals)
                showTokenDialog = false
            }
        )
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Terminal Settings") }) }) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("QR-only read-only terminal", style = MaterialTheme.typography.titleMedium)
                Text(
                    "This app stores no wallet key and cannot sign, broadcast, sweep, or settle transactions.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item {
                SettingField("Network name", state.networkName, viewModel::updateNetworkName)
                SettingField("HTTPS RPC URL", state.rpcUrl, viewModel::updateRpcUrl)
                SettingField("Chain ID", state.chainId, viewModel::updateChainId, KeyboardType.Number)
                SettingField("Factory address", state.factoryAddress, viewModel::updateFactoryAddress)
                SettingField(
                    "Receiver implementation",
                    state.receiverImplementationAddress,
                    viewModel::updateReceiverImplementationAddress
                )
                SettingField("Vault address", state.vaultAddress, viewModel::updateVaultAddress)
                SettingField(
                    "Confirmation blocks",
                    state.confirmationBlocks,
                    viewModel::updateConfirmationBlocks,
                    KeyboardType.Number
                )
            }
            item {
                Text("Terminal identifier", style = MaterialTheme.typography.labelLarge)
                Text(
                    state.terminalIdentifier.chunked(8).joinToString(" "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Random non-secret installation namespace; it is not an Ethereum key.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Payment tokens", style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(onClick = { showTokenDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Text(" Add")
                    }
                }
            }
            if (state.paymentTokens.isEmpty()) {
                item { Text("Add at least one vault-whitelisted ERC-20 token.") }
            }
            state.paymentTokens.forEach { token ->
                item(key = token.address) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("${token.symbol} · ${token.decimals} decimals")
                                Text(token.address, style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(onClick = { viewModel.removePaymentToken(token.address) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Remove token")
                            }
                        }
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
            item {
                Button(
                    onClick = viewModel::saveSettings,
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    Icon(Icons.Default.Save, contentDescription = null)
                    Text(" Save settings")
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun SettingField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    )
}

@Composable
private fun AddTokenDialog(onDismiss: () -> Unit, onAdd: (String, String, Int) -> Unit) {
    var address by remember { mutableStateOf("") }
    var symbol by remember { mutableStateOf("") }
    var decimals by remember { mutableStateOf("6") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add ERC-20 token") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingField("Token address", address, { address = it })
                SettingField("Symbol", symbol, { symbol = it })
                SettingField("Decimals", decimals, { decimals = it }, KeyboardType.Number)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { decimals.toIntOrNull()?.let { onAdd(address, symbol, it) } },
                enabled = address.isNotBlank() && symbol.isNotBlank() && decimals.toIntOrNull() != null
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
