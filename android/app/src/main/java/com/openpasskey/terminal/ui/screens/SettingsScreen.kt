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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.openpasskey.terminal.ui.components.DeviceAuthentication
import com.openpasskey.terminal.ui.components.AddressScannerDialog
import com.openpasskey.terminal.ui.components.ProvisioningScannerDialog
import com.openpasskey.terminal.ui.components.QRCodeView
import com.openpasskey.terminal.provisioning.KnownChainPolicy
import com.openpasskey.terminal.provisioning.formatNativeCurrencyAmount
import com.openpasskey.terminal.chain.TerminalPaymentProfile
import com.openpasskey.terminal.viewmodel.SettingsState
import com.openpasskey.terminal.viewmodel.SettingsViewModel
import com.openpasskey.terminal.viewmodel.TerminalSetupStatus
import com.openpasskey.terminal.wallet.OperatorWalletAvailability

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {
    val state by viewModel.state.collectAsState()
    val activity = LocalContext.current as? FragmentActivity
    var showWalletCreation by remember { mutableStateOf(false) }
    var showSetPin by remember { mutableStateOf(false) }
    var showUnlock by remember { mutableStateOf(false) }
    var showProvisioningScanner by remember { mutableStateOf(false) }
    var showReset by remember { mutableStateOf(false) }
    var showAdvancedManualSetup by remember { mutableStateOf(false) }
    var profilePendingRemoval by remember { mutableStateOf<TerminalPaymentProfile?>(null) }

    if (showProvisioningScanner) {
        ProvisioningScannerDialog(
            onDismiss = { showProvisioningScanner = false },
            onProvisioningPayloadScanned = viewModel::provision,
        )
    }
    if (showWalletCreation) {
        AlertDialog(
            onDismissRequest = { showWalletCreation = false },
            title = { Text("Create terminal operator?") },
            text = {
                Text(
                    "This creates the device-local EOA protected by Android Keystore and device " +
                        "authentication. The merchant passkey is never stored on this terminal.",
                )
            },
            confirmButton = {
                Button(onClick = {
                    showWalletCreation = false
                    if (viewModel.prepareWalletCreation()) {
                        if (activity == null) {
                            viewModel.authenticationFailed("System authentication is unavailable")
                        } else {
                            DeviceAuthentication.authenticate(
                                activity,
                                "Create operator wallet",
                                "Authenticate to protect the new private key",
                                viewModel::createWalletAuthenticated,
                                viewModel::authenticationFailed,
                            )
                        }
                    }
                }) { Text("Authenticate & create") }
            },
            dismissButton = {
                TextButton(onClick = { showWalletCreation = false }) { Text("Cancel") }
            },
        )
    }
    if (showSetPin) {
        SetAdminPinDialog(
            onDismiss = { showSetPin = false },
            onSave = { pin, confirmation ->
                showSetPin = false
                viewModel.setInitialAdminPin(pin, confirmation)
            },
        )
    }
    if (showUnlock) {
        UnlockAdminDialog(
            retryAfterSeconds = state.adminRetryAfterSeconds,
            onDismiss = { showUnlock = false },
            onUnlock = { pin ->
                showUnlock = false
                viewModel.unlockAdmin(pin)
            },
        )
    }
    if (showReset) {
        AlertDialog(
            onDismissRequest = { showReset = false },
            title = { Text("Permanently reset operator wallet?") },
            text = {
                Text(
                    "This permanently deletes the local private key and current provisioning. " +
                        "Before continuing, revoke ${state.operatorWalletAddress ?: "this operator"} " +
                        "from every configured vault and withdraw native gas on every network " +
                        "supported by this app build. " +
                        "The app checks both latest and pending balances twice on each network and cancels " +
                        "reset unless every balance is exactly zero. This app cannot undo " +
                        "the reset. Funds sent later to this previously shared address cannot be recovered. " +
                        "Reset is available only before this terminal issues its first payment QR. A published " +
                        "receiver remains payable forever, so its signing key cannot later be deleted safely. " +
                        "Invoice history remains available.",
                )
            },
            confirmButton = {
                Button(onClick = {
                    showReset = false
                    viewModel.resetWalletConfirmed()
                }) {
                    Icon(Icons.Default.DeleteForever, contentDescription = null)
                    Text(" I revoked it and withdrew all gas — reset")
                }
            },
            dismissButton = { TextButton(onClick = { showReset = false }) { Text("Cancel") } },
        )
    }
    profilePendingRemoval?.let { profile ->
        AlertDialog(
            onDismissRequest = { profilePendingRemoval = null },
            title = { Text("Remove payment profile?") },
            text = {
                Text(paymentProfileRemovalConfirmationMessage(profile, state.paymentProfiles.size))
            },
            confirmButton = {
                Button(onClick = {
                    profilePendingRemoval = null
                    viewModel.removePaymentProfile(profile.id)
                }) {
                    Icon(Icons.Default.DeleteForever, contentDescription = null)
                    Text(" Remove profile")
                }
            },
            dismissButton = {
                TextButton(onClick = { profilePendingRemoval = null }) { Text("Cancel") }
            },
        )
    }
    if (showAdvancedManualSetup) {
        AdvancedManualSetupDialog(
            onDismiss = { showAdvancedManualSetup = false },
            onProvision = { chainId, vault, token ->
                showAdvancedManualSetup = false
                viewModel.provisionManual(chainId, vault, token)
            },
        )
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SetupStatusCard(state) }
            item {
                OperatorWalletCard(
                    state = state,
                    onCreate = { showWalletCreation = true },
                    onRefresh = viewModel::refreshOperatorStatus,
                )
            }
            if (state.operatorWalletAvailability == OperatorWalletAvailability.READY &&
                !state.adminPinConfigured
            ) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Protect setup", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Set a local 6-digit admin PIN. It hides reprovisioning and wallet reset; " +
                                    "it is not your merchant passkey.",
                            )
                            Button(onClick = { showSetPin = true }, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.Security, contentDescription = null)
                                Text(" Set admin PIN")
                            }
                        }
                    }
                }
            }
            if (state.operatorWalletAvailability == OperatorWalletAvailability.READY &&
                state.adminPinConfigured && state.adminUnlocked && !state.provisioned
            ) {
                item {
                    Button(
                        onClick = { showProvisioningScanner = true },
                        enabled = state.setupStatus != TerminalSetupStatus.PROVISIONING,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                        Text(" Scan merchant portal setup")
                    }
                }
            }
            if (state.provisioned) {
                item {
                    ConfigurationSummary(
                        state = state,
                        onRemove = if (state.adminUnlocked) {
                            { profile -> profilePendingRemoval = profile }
                        } else {
                            null
                        },
                    )
                }
            }
            if (state.adminPinConfigured) {
                item {
                    AdminSetupCard(
                        unlocked = state.adminUnlocked,
                        busy = state.setupStatus == TerminalSetupStatus.PROVISIONING,
                        onUnlock = { showUnlock = true },
                        onLock = viewModel::lockAdmin,
                        onReprovision = { showProvisioningScanner = true },
                        onManualSetup = { showAdvancedManualSetup = true },
                        onReset = { showReset = true },
                    )
                }
            }
            state.migrationNotice?.let { notice ->
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text("Security update applied", style = MaterialTheme.typography.titleMedium)
                            Text(notice, style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = viewModel::acknowledgeMigrationNotice) {
                                Text("Acknowledge security update")
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
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun SetupStatusCard(state: SettingsState) {
    val (title, detail) = when (state.setupStatus) {
        TerminalSetupStatus.CREATE_WALLET -> "Step 1 of 2 · Create terminal wallet" to
            "Create the device EOA used for terminal identity and constrained settlement signing."
        TerminalSetupStatus.SET_ADMIN_PIN -> "Protect setup" to
            "Set a local admin PIN before importing merchant configuration."
        TerminalSetupStatus.SCAN_PORTAL -> "Step 2 of 2 · Connect merchant portal" to
            "On a personal phone or computer, authorize this terminal and show its unified setup QR."
        TerminalSetupStatus.PROVISIONING -> "Validating configuration" to
            "Checking the known chain, vault, deployment pins, token metadata, and whitelist."
        TerminalSetupStatus.AWAITING_AUTHORIZATION -> "Awaiting portal authorization" to
            "Confirm the terminal operator transaction in the merchant portal, then refresh."
        TerminalSetupStatus.AWAITING_GAS -> "Awaiting terminal gas" to
            "Send at least ${formatNativeCurrencyAmount(state.minimumOperatorNativeReserveWei, state.nativeCurrencyDecimals, state.nativeCurrencySymbol)} to the funding address below."
        TerminalSetupStatus.READY -> "Terminal ready" to
            "Configuration, operator authorization, and minimum gas reserve are valid."
        TerminalSetupStatus.ERROR -> "Setup needs attention" to
            "Review the error below. Existing invoices and history remain accessible."
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                configurationValidationLabel(state),
                style = MaterialTheme.typography.labelLarge,
                color = if (state.configurationValidated) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

internal fun configurationValidationLabel(state: SettingsState): String = when {
    state.refreshingOperator -> "On-chain validation in progress"
    state.configurationValidated -> "On-chain validation passed"
    else -> "On-chain validation not complete"
}

@Composable
private fun OperatorWalletCard(
    state: SettingsState,
    onCreate: () -> Unit,
    onRefresh: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Terminal operator wallet", style = MaterialTheme.typography.titleMedium)
            when (state.operatorWalletAvailability) {
                OperatorWalletAvailability.NOT_CREATED -> {
                    Text("No device-local EOA exists yet.")
                    Button(onClick = onCreate, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Security, contentDescription = null)
                        Text(" Create protected wallet")
                    }
                }
                OperatorWalletAvailability.UNAVAILABLE -> {
                    Text(
                        "The stored operator cannot be used safely. Revoke it on the vault before replacement.",
                        color = MaterialTheme.colorScheme.error,
                    )
                    state.operatorWalletAddress?.let { Text(it, fontFamily = FontFamily.Monospace) }
                }
                OperatorWalletAvailability.READY -> {
                    val address = requireNotNull(state.operatorWalletAddress)
                    SelectionContainer { Text(address, fontFamily = FontFamily.Monospace) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            clipboard.setText(AnnotatedString(address))
                            Toast.makeText(context, "Operator address copied", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null)
                            Text(" Copy")
                        }
                        IconButton(onClick = onRefresh, enabled = !state.refreshingOperator) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh setup status")
                        }
                    }
                    state.operatorPairingPayload?.let { payload ->
                        Text("Portal pairing QR", style = MaterialTheme.typography.labelLarge)
                        QRCodeView(
                            data = payload,
                            size = 190.dp,
                            contentDescription = "Terminal operator pairing QR code",
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        )
                        Text(
                            "From Add terminal in the merchant portal, scan this QR and confirm authorization.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    state.operatorFundingPayload?.let { payload ->
                        Text("Gas funding QR", style = MaterialTheme.typography.labelLarge)
                        QRCodeView(
                            data = payload,
                            size = 190.dp,
                            contentDescription = "Chain-qualified terminal gas funding QR code",
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        )
                        Text(
                            "Send ${state.nativeCurrencySymbol} gas only. ERC-20 customer payments go to one-time receiver addresses.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(
                        "${state.nativeCurrencySymbol} balance: ${state.operatorBalanceWei?.let { formatNativeCurrencyAmount(it, state.nativeCurrencyDecimals, state.nativeCurrencySymbol) } ?: "Not checked"}",
                    )
                    Text(
                        "Vault authorization: ${when (state.operatorAuthorized) {
                            true -> "authorized"
                            false -> "not authorized"
                            null -> "not checked"
                        }}",
                    )
                    Text(
                        "Key protection: ${if (state.walletStrongBoxBacked) "StrongBox" else if (state.walletHardwareBacked) "hardware-backed Keystore" else "Keystore"}; " +
                            if (state.walletDeviceAuthenticationRequired) "device authentication required" else "authentication unavailable",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConfigurationSummary(
    state: SettingsState,
    onRemove: ((TerminalPaymentProfile) -> Unit)?,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "Payment profiles (${state.paymentProfiles.size})",
                style = MaterialTheme.typography.titleMedium,
            )
            state.provisionedOperatorAddress?.let { SummaryLine("Bound operator", it) }
            state.paymentProfiles.forEachIndexed { index, profile ->
                val selected = profile.id == state.selectedProfileId
                Text(
                    "${if (selected) "Selected · " else ""}${profile.token.symbol} · " +
                        "${profile.networkName} (${profile.chainId})",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                )
                SummaryLine("Vault", profile.vaultAddress)
                SummaryLine(
                    "Token",
                    "${profile.token.address} · ${profile.token.decimals} decimals",
                )
                SummaryLine("Protocol", profile.protocolVersion)
                SummaryLine("Factory", profile.factoryAddress)
                SummaryLine("Receiver implementation", profile.receiverImplementationAddress)
                if (onRemove != null) {
                    TextButton(
                        onClick = { onRemove(profile) },
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Icon(Icons.Default.DeleteForever, contentDescription = null)
                        Text(" Remove profile")
                    }
                }
                if (index < state.paymentProfiles.lastIndex) {
                    Spacer(Modifier.height(6.dp))
                }
            }
            Text(
                "Profiles are chain-derived and read-only. Unlock Admin/setup to add or refresh a portal profile.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SummaryLine(label: String, value: String) {
    Text(label, style = MaterialTheme.typography.labelMedium)
    SelectionContainer { Text(value, style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun AdminSetupCard(
    unlocked: Boolean,
    busy: Boolean,
    onUnlock: () -> Unit,
    onLock: () -> Unit,
    onReprovision: () -> Unit,
    onManualSetup: () -> Unit,
    onReset: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Admin/setup", style = MaterialTheme.typography.titleMedium)
            if (!unlocked) {
                Text("Reprovisioning and wallet reset are hidden while locked.")
                OutlinedButton(onClick = onUnlock, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Lock, contentDescription = null)
                    Text(" Unlock with local PIN")
                }
            } else {
                Text("Unlocked until setup completes or the app goes to the background.")
                OutlinedButton(
                    onClick = onReprovision,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                    Text(" Add or update portal profile")
                }
                OutlinedButton(
                    onClick = onManualSetup,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Security, contentDescription = null)
                    Text(" Advanced manual setup")
                }
                OutlinedButton(
                    onClick = onReset,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.DeleteForever, contentDescription = null)
                    Text(" Reset operator wallet")
                }
                TextButton(onClick = onLock, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.LockOpen, contentDescription = null)
                    Text(" Lock admin now")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdvancedManualSetupDialog(
    onDismiss: () -> Unit,
    onProvision: (Long, String, String) -> Unit,
) {
    val chains = remember { KnownChainPolicy.enabledProfiles() }
    var selectedChain by remember { mutableStateOf(chains.first()) }
    var chainMenuExpanded by remember { mutableStateOf(false) }
    var vault by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var scanTarget by remember { mutableStateOf<String?>(null) }
    scanTarget?.let { target ->
        AddressScannerDialog(
            onDismiss = { scanTarget = null },
            onAddressScanned = { address ->
                if (target == "vault") vault = address else token = address
                scanTarget = null
            },
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Advanced manual setup") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Choose a verified EVM deployment, then enter or scan the vault and token. " +
                        "Factory, receiver implementation, symbol, and decimals remain chain-derived and pinned.",
                )
                ExposedDropdownMenuBox(
                    expanded = chainMenuExpanded,
                    onExpandedChange = { chainMenuExpanded = it },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedTextField(
                        value = "${selectedChain.networkName} (${selectedChain.chainId})",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Network") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(chainMenuExpanded)
                        },
                        modifier = Modifier.fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(
                        expanded = chainMenuExpanded,
                        onDismissRequest = { chainMenuExpanded = false },
                    ) {
                        chains.forEach { chain ->
                            DropdownMenuItem(
                                text = { Text("${chain.networkName} (${chain.chainId})") },
                                onClick = {
                                    selectedChain = chain
                                    chainMenuExpanded = false
                                },
                            )
                        }
                    }
                }
                ManualAddressField("Vault address", vault, { vault = it }) { scanTarget = "vault" }
                ManualAddressField("Token contract", token, { token = it }) { scanTarget = "token" }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onProvision(selectedChain.chainId, vault, token) },
                enabled = vault.isNotBlank() && token.isNotBlank(),
            ) { Text("Validate & provision") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ManualAddressField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onScan: () -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
        trailingIcon = {
            IconButton(onClick = onScan) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan $label")
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SetAdminPinDialog(
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set local admin PIN") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PinField("6-digit PIN", pin) { pin = it.filter(Char::isDigit).take(6) }
                PinField("Confirm PIN", confirmation) {
                    confirmation = it.filter(Char::isDigit).take(6)
                }
                Text("This PIN protects setup controls only. It is not a merchant credential.")
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(pin, confirmation) },
                enabled = pin.length == 6 && confirmation.length == 6,
            ) { Text("Save PIN") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun UnlockAdminDialog(
    retryAfterSeconds: Long,
    onDismiss: () -> Unit,
    onUnlock: (String) -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Unlock Admin/setup") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PinField("Admin PIN", pin) { pin = it.filter(Char::isDigit).take(6) }
                if (retryAfterSeconds > 0) Text("Try again in $retryAfterSeconds seconds.")
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onUnlock(pin) },
                enabled = pin.length == 6 && retryAfterSeconds == 0L,
            ) { Text("Unlock") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PinField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
}

internal fun paymentProfileRemovalConfirmationMessage(
    profile: TerminalPaymentProfile,
    configuredProfileCount: Int,
): String {
    val consequence = if (configuredProfileCount == 1) {
        " Removing this last payment profile disables Checkout and returns the terminal to setup " +
            "until a portal payment profile is added."
    } else {
        ""
    }
    return "Remove ${profile.token.symbol} on ${profile.networkName} for vault " +
        "${profile.vaultAddress} and token ${profile.token.address} from future checkouts?" +
        consequence + " Existing invoices, payment monitoring, and settlement history keep their " +
        "immutable network, vault, and token snapshots."
}
