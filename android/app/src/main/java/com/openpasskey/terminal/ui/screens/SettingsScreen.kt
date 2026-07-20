package com.openpasskey.terminal.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.QrCodeScanner
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.openpasskey.terminal.ui.components.AddressScannerDialog
import com.openpasskey.terminal.ui.components.DeviceAuthentication
import com.openpasskey.terminal.ui.components.QRCodeView
import com.openpasskey.terminal.viewmodel.SettingsViewModel
import com.openpasskey.terminal.wallet.OperatorWalletAvailability
import java.math.BigDecimal
import java.math.BigInteger

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {
    val state by viewModel.state.collectAsState()
    var showTokenDialog by remember { mutableStateOf(false) }
    var showWalletCreationDialog by remember { mutableStateOf(false) }
    val activity = LocalContext.current as? FragmentActivity

    if (showTokenDialog) {
        AddTokenDialog(
            onDismiss = { showTokenDialog = false },
            onAdd = { address, symbol, decimals ->
                viewModel.addPaymentToken(address, symbol, decimals)
                showTokenDialog = false
            },
        )
    }

    if (showWalletCreationDialog) {
        AlertDialog(
            onDismissRequest = { showWalletCreationDialog = false },
            title = { Text("Create terminal operator?") },
            text = {
                Text(
                    "This creates a new secp256k1 wallet protected by Android Keystore and device authentication. " +
                        "Its public address identifies new invoices. Fund it with native gas and authorize it on the vault before settlement."
                )
            },
            confirmButton = {
                Button(onClick = {
                    showWalletCreationDialog = false
                    if (viewModel.prepareWalletCreation()) {
                        if (activity == null) {
                            viewModel.authenticationFailed("System authentication is unavailable")
                        } else {
                            DeviceAuthentication.authenticate(
                                activity,
                                "Create operator wallet",
                                "Authenticate to protect the new private key",
                                viewModel::createWalletAuthenticated,
                                viewModel::authenticationFailed
                            )
                        }
                    }
                }) { Text("Authenticate & create") }
            },
            dismissButton = {
                TextButton(onClick = { showWalletCreationDialog = false }) { Text("Cancel") }
            }
        )
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Terminal Settings") }) }) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("Payment and settlement terminal", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Payment observation remains read-only. A separate, encrypted operator wallet can sign only reviewed sweepSessions transactions.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                SettingField("Network name", state.networkName, viewModel::updateNetworkName)
                SettingField("HTTPS RPC URL", state.rpcUrl, viewModel::updateRpcUrl)
                SettingField("Chain ID", state.chainId, viewModel::updateChainId, KeyboardType.Number)
                ScannableAddressField(
                    fieldLabel = "Factory address",
                    address = state.factoryAddress,
                    onAddressChange = viewModel::updateFactoryAddress,
                )
                ScannableAddressField(
                    fieldLabel = "Receiver implementation address",
                    address = state.receiverImplementationAddress,
                    onAddressChange = viewModel::updateReceiverImplementationAddress,
                )
                ScannableAddressField(
                    fieldLabel = "Vault address",
                    address = state.vaultAddress,
                    onAddressChange = viewModel::updateVaultAddress,
                )
                SettingField(
                    "Confirmation blocks",
                    state.confirmationBlocks,
                    viewModel::updateConfirmationBlocks,
                    KeyboardType.Number,
                )
            }
            item {
                OperatorWalletCard(
                    availability = state.operatorWalletAvailability,
                    address = state.operatorWalletAddress,
                    chainId = state.operatorNetworkChainId.takeIf { it > 0 },
                    balanceWei = state.operatorBalanceWei,
                    authorized = state.operatorAuthorized,
                    settlementTargetVerified = state.settlementTargetVerified,
                    hardwareBacked = state.walletHardwareBacked,
                    strongBoxBacked = state.walletStrongBoxBacked,
                    deviceAuthenticationRequired = state.walletDeviceAuthenticationRequired,
                    refreshing = state.refreshingOperator,
                    onCreate = { showWalletCreationDialog = true },
                    onRefresh = viewModel::refreshOperatorStatus
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
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
                            verticalAlignment = Alignment.CenterVertically,
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
                        else MaterialTheme.colorScheme.primary,
                    )
                }
            }
            item {
                Button(
                    onClick = viewModel::saveSettings,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
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
private fun OperatorWalletCard(
    availability: OperatorWalletAvailability,
    address: String?,
    chainId: Long?,
    balanceWei: String?,
    authorized: Boolean?,
    settlementTargetVerified: Boolean,
    hardwareBacked: Boolean,
    strongBoxBacked: Boolean,
    deviceAuthenticationRequired: Boolean,
    refreshing: Boolean,
    onCreate: () -> Unit,
    onRefresh: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Terminal operator wallet", style = MaterialTheme.typography.titleMedium)
            when (availability) {
                OperatorWalletAvailability.NOT_CREATED -> {
                    Text(
                        "Create the device-local wallet used as the terminal identity for new payment QRs and to sign reviewed settlement transactions."
                    )
                    Button(onClick = onCreate, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Security, contentDescription = null)
                        Text(" Create protected wallet")
                    }
                }
                OperatorWalletAvailability.UNAVAILABLE -> {
                    Text(
                        "The stored operator cannot be used safely. Revoke its address on the vault before replacing the app or wallet.",
                        color = MaterialTheme.colorScheme.error
                    )
                    address?.let { Text(it, fontFamily = FontFamily.Monospace) }
                }
                OperatorWalletAvailability.READY -> {
                    val walletAddress = requireNotNull(address)
                    SelectionContainer {
                        Text(walletAddress, fontFamily = FontFamily.Monospace)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            clipboard.setText(AnnotatedString(walletAddress))
                            Toast.makeText(context, "Operator address copied", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null)
                            Text(" Copy full address")
                        }
                        IconButton(onClick = onRefresh, enabled = !refreshing) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh operator status")
                        }
                    }
                    QRCodeView(
                        data = chainId?.let { "ethereum:$walletAddress@$it" } ?: walletAddress,
                        size = 180.dp,
                        contentDescription = "Chain-qualified settlement operator funding QR code",
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                    Text(
                        "The QR is address-only${chainId?.let { " for chain $it" } ?: ""}. Fund this address with native gas only. " +
                            "ERC-20 payment funds go to one-time invoice receivers.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "This public address identifies every new invoice. Vault authorization is checked separately before settlement.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    val balance = balanceWei?.let(::formatNativeBalance) ?: "Not checked"
                    Text("Native balance: $balance")
                    if (balanceWei?.let { BigInteger(it) < MINIMUM_GAS_RESERVE_WEI } == true) {
                        Text(
                            "LOW GAS — add native currency before settlement. Exact gas plus safety/L1 reserve is checked again before signing.",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Text(
                        when (authorized) {
                            true -> "Vault authorization: authorized (owner or operator)"
                            false -> "Vault authorization: NOT AUTHORIZED"
                            null -> "Vault authorization: not checked"
                        },
                        color = if (authorized == false) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "Settlement target: ${if (settlementTargetVerified) "verified for this chain/vault" else "verified again before signing"}"
                    )
                    Text(
                        "Key protection: ${if (strongBoxBacked) "StrongBox" else if (hardwareBacked) "hardware-backed Keystore" else "Keystore"}; " +
                            if (deviceAuthenticationRequired) "device authentication required" else "authentication unavailable",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

private fun formatNativeBalance(wei: String): String = runCatching {
    BigDecimal(wei).movePointLeft(18).stripTrailingZeros().toPlainString() + " native"
}.getOrDefault("Unknown")

private val MINIMUM_GAS_RESERVE_WEI = BigInteger("100000000000000")

@Composable
private fun SettingField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

@Composable
private fun ScannableAddressField(
    fieldLabel: String,
    address: String,
    onAddressChange: (String) -> Unit,
) {
    var showScanner by remember { mutableStateOf(false) }
    if (showScanner) {
        AddressScannerDialog(
            onDismiss = { showScanner = false },
            onAddressScanned = onAddressChange,
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = address,
            onValueChange = onAddressChange,
            label = { Text(fieldLabel) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            trailingIcon = {
                IconButton(onClick = { showScanner = true }) {
                    Icon(
                        Icons.Default.QrCodeScanner,
                        contentDescription = "Scan $fieldLabel QR",
                    )
                }
            },
            supportingText = { Text("Scan a non-zero address-only QR or enter 0x plus 40 hex digits.") },
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        )
    }
}

@Composable
private fun AddTokenDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String, Int) -> Unit,
) {
    var address by remember { mutableStateOf("") }
    var symbol by remember { mutableStateOf("") }
    var decimals by remember { mutableStateOf("6") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add ERC-20 token") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ScannableAddressField(
                    fieldLabel = "Token contract address",
                    address = address,
                    onAddressChange = { address = it },
                )
                SettingField("Symbol", symbol, { symbol = it })
                SettingField("Decimals", decimals, { decimals = it }, KeyboardType.Number)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    decimals.toIntOrNull()?.let { onAdd(address, symbol, it) }
                },
                enabled = address.isNotBlank() && symbol.isNotBlank() && decimals.toIntOrNull() != null,
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
