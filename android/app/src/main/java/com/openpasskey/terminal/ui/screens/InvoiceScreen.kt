package com.openpasskey.terminal.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openpasskey.terminal.chain.TerminalPaymentProfile
import com.openpasskey.terminal.provisioning.KnownChainPolicy
import com.openpasskey.terminal.viewmodel.CheckoutKey
import com.openpasskey.terminal.viewmodel.CLEAR_AMOUNT_ACCESSIBILITY_LABEL
import com.openpasskey.terminal.viewmodel.InvoiceViewModel
import com.openpasskey.terminal.viewmodel.TerminalSetupStatus
import com.openpasskey.terminal.viewmodel.applyCheckoutKey
import com.openpasskey.terminal.viewmodel.accessibilityLabel
import com.openpasskey.terminal.viewmodel.checkoutActionCopy
import com.openpasskey.terminal.viewmodel.checkoutAmountDisplay
import com.openpasskey.terminal.viewmodel.isCheckoutReady
import com.openpasskey.terminal.viewmodel.isSubmittableCheckoutAmount

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InvoiceScreen(
    viewModel: InvoiceViewModel,
    terminalStatus: TerminalSetupStatus,
    terminalStatusMessage: String?,
    terminalRefreshing: Boolean,
    terminalConfigurationValidated: Boolean,
    onRefreshTerminalStatus: () -> Unit,
    onProfileSelection: () -> Unit,
    onRecoverFromInvoiceFailure: () -> Unit,
    onOpenSettings: () -> Unit,
    onInvoiceCreated: (String) -> Unit,
) {
    val state by viewModel.createState.collectAsState()
    LaunchedEffect(Unit) {
        viewModel.refreshConfiguration()
        onRefreshTerminalStatus()
    }
    LaunchedEffect(state.readinessFailureSequence) {
        if (state.readinessFailureSequence > 0) {
            viewModel.refreshConfiguration()
            onRecoverFromInvoiceFailure()
        }
    }
    LaunchedEffect(state.profileSelectionSequence) {
        if (state.profileSelectionSequence > 0) onProfileSelection()
    }
    LaunchedEffect(state.createdInvoice) {
        state.createdInvoice?.let {
            onInvoiceCreated(it.invoiceId)
            viewModel.consumeCreatedInvoice()
        }
    }

    val selectedProfile = state.selectedProfile
    val ready = isCheckoutReady(
        terminalStatus = terminalStatus,
        configurationValidated = terminalConfigurationValidated,
        refreshing = terminalRefreshing,
        readinessInvalidated = state.readinessInvalidated || state.profileSelectionPending,
        operatorWalletReady = state.operatorWalletReady,
        hasSelectedToken = selectedProfile != null,
    )
    if (!ready) {
        Scaffold(topBar = { TopAppBar(title = { Text("Checkout") }) }) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // A newly selected profile can be unready without trapping the cashier. Keep the
                // full profile picker available so another ready currency/network can be chosen.
                if (state.profiles.size > 1 && selectedProfile != null) {
                    ProfileSelector(
                        state.profiles,
                        selectedProfile,
                        enabled = !state.isCreating && !state.profileSelectionPending,
                        onSelected = viewModel::selectProfile,
                    )
                }
                CheckoutBlocker(
                    status = when {
                        state.profileSelectionPending ->
                            TerminalSetupStatus.READY
                        state.readinessInvalidated -> TerminalSetupStatus.ERROR
                        terminalStatus == TerminalSetupStatus.READY &&
                            (terminalRefreshing || !terminalConfigurationValidated) ->
                            TerminalSetupStatus.READY
                        terminalStatus == TerminalSetupStatus.READY -> TerminalSetupStatus.ERROR
                        else -> terminalStatus
                    },
                    statusMessage = when {
                        state.profileSelectionPending -> null
                        state.readinessInvalidated -> state.repositoryFailure
                        terminalStatus == TerminalSetupStatus.READY -> null
                        else -> terminalStatusMessage
                    },
                    selectedProfile = selectedProfile,
                    onOpenSettings = onOpenSettings,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
        }
        return
    }
    CheckoutReadyScreen(
        amount = state.amount,
        profile = requireNotNull(selectedProfile),
        profiles = state.profiles,
        error = state.error,
        isCreating = state.isCreating,
        onAmountChanged = viewModel::updateAmount,
        onProfileSelected = viewModel::selectProfile,
        onCreateInvoice = viewModel::createInvoice,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CheckoutReadyScreen(
    amount: String,
    profile: TerminalPaymentProfile,
    profiles: List<TerminalPaymentProfile>,
    error: String?,
    isCreating: Boolean,
    onAmountChanged: (String) -> Unit,
    onProfileSelected: (TerminalPaymentProfile) -> Unit,
    onCreateInvoice: () -> Unit,
) {
    val token = profile.token
    val displayAmount = checkoutAmountDisplay(amount, token.decimals)
    val amountValid = isSubmittableCheckoutAmount(amount, token.decimals)
    Scaffold(
        topBar = { TopAppBar(title = { Text("Checkout") }) },
        bottomBar = {
            CheckoutActionBar(
                amount = displayAmount,
                tokenSymbol = token.symbol,
                isCreating = isCreating,
                enabled = amountValid && !isCreating,
                onClick = onCreateInvoice,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CheckoutStatusRow(
                profile.networkName,
                isTestNetwork = runCatching {
                    KnownChainPolicy.requireProfile(profile.chainId).isTestnet
                }.getOrDefault(false),
            )
            AmountDisplay(
                amount = displayAmount,
                tokenSymbol = token.symbol,
                showFullReviewHint = checkoutActionCopy(
                    displayAmount,
                    token.symbol,
                ).amountIsCondensed,
                canClear = amount.isNotEmpty() && !isCreating,
                onClear = { onAmountChanged("") },
            )
            if (profiles.size > 1) {
                ProfileSelector(
                    profiles,
                    profile,
                    enabled = !isCreating,
                    onSelected = onProfileSelected,
                )
            }
            error?.let { CheckoutError(it) }
            CheckoutKeypad(
                amount = amount,
                tokenDecimals = token.decimals,
                enabled = !isCreating,
                onAmountChanged = onAmountChanged,
                modifier = Modifier.fillMaxWidth().height(304.dp).padding(bottom = 10.dp),
            )
        }
    }
}

@Composable
private fun CheckoutStatusRow(networkName: String, isTestNetwork: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = RoundedCornerShape(50),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Ready", style = MaterialTheme.typography.labelLarge)
            }
        }
        Spacer(Modifier.weight(1f))
        Text(
            networkName.ifBlank { "Configured network" },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (isTestNetwork) {
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                shape = RoundedCornerShape(50),
            ) {
                Text(
                    "TESTNET",
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun AmountDisplay(
    amount: String,
    tokenSymbol: String,
    showFullReviewHint: Boolean,
    canClear: Boolean,
    onClear: () -> Unit,
) {
    val amountScrollState = rememberScrollState()
    LaunchedEffect(amount) {
        withFrameNanos { }
        amountScrollState.scrollTo(Int.MAX_VALUE)
    }
    val amountSize = when {
        amount.length <= 9 -> 64.sp
        amount.length <= 14 -> 52.sp
        amount.length <= 20 -> 42.sp
        else -> 32.sp
    }
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 112.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .testTag("checkout_amount")
                .clearAndSetSemantics {
                    contentDescription = "Checkout amount, $amount $tokenSymbol"
                },
            horizontalAlignment = Alignment.End,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(amountScrollState),
                horizontalArrangement = Arrangement.End,
            ) {
                Text(
                    amount,
                    textAlign = TextAlign.End,
                    fontSize = amountSize,
                    lineHeight = amountSize,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            Text(
                tokenSymbol,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (showFullReviewHint) {
                Text(
                    "Swipe horizontally to review the full amount",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        TextButton(
            onClick = onClear,
            enabled = canClear,
            modifier = Modifier.semantics {
                contentDescription = CLEAR_AMOUNT_ACCESSIBILITY_LABEL
            }.testTag("checkout_clear"),
        ) {
            Text("Clear")
        }
    }
}

@Composable
private fun CheckoutKeypad(
    amount: String,
    tokenDecimals: Int,
    enabled: Boolean,
    onAmountChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = listOf(
        listOf(CheckoutKey.ONE, CheckoutKey.TWO, CheckoutKey.THREE),
        listOf(CheckoutKey.FOUR, CheckoutKey.FIVE, CheckoutKey.SIX),
        listOf(CheckoutKey.SEVEN, CheckoutKey.EIGHT, CheckoutKey.NINE),
        listOf(CheckoutKey.DECIMAL, CheckoutKey.ZERO, CheckoutKey.BACKSPACE),
    )
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { keys ->
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                keys.forEach { key ->
                    val next = applyCheckoutKey(amount, key, tokenDecimals)
                    val keyEnabled = enabled && next != amount
                    OutlinedButton(
                        onClick = { onAmountChanged(next) },
                        enabled = keyEnabled,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .testTag("checkout_key_${key.name.lowercase()}")
                            .semantics {
                                key.accessibilityLabel()?.let { contentDescription = it }
                            },
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    ) {
                        if (key == CheckoutKey.BACKSPACE) {
                            Icon(
                                Icons.AutoMirrored.Filled.Backspace,
                                contentDescription = null,
                                modifier = Modifier.size(26.dp),
                            )
                        } else {
                            Text(
                                key.label,
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckoutActionBar(
    amount: String,
    tokenSymbol: String,
    isCreating: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val actionCopy = checkoutActionCopy(amount, tokenSymbol)
    Surface(shadowElevation = 4.dp) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .heightIn(min = 56.dp)
                .testTag("checkout_cta"),
            shape = RoundedCornerShape(18.dp),
        ) {
            Row(
                modifier = Modifier.clearAndSetSemantics {
                    contentDescription = actionCopy.accessibilityLabel
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isCreating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Text("  Validating chain…")
                } else {
                    Icon(Icons.Default.QrCode, contentDescription = null)
                    Text("  ${actionCopy.visibleLabel}")
                }
            }
        }
    }
}

@Composable
internal fun CheckoutBlocker(
    status: TerminalSetupStatus,
    statusMessage: String?,
    selectedProfile: TerminalPaymentProfile? = null,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val copy = checkoutBlockerCopy(status, statusMessage, selectedProfile)
    Box(
        modifier = modifier
            .testTag("checkout_blocker_scroll")
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center,
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.Start,
            ) {
                Icon(
                    Icons.Default.ErrorOutline,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
                Text(copy.title, style = MaterialTheme.typography.headlineSmall)
                Text(copy.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(
                    onClick = onOpenSettings,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .testTag("checkout_blocker_action"),
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null)
                    Text("  ${copy.actionLabel}")
                }
            }
        }
    }
}

internal data class CheckoutBlockerCopy(
    val title: String,
    val detail: String,
    val actionLabel: String,
)

internal fun checkoutBlockerCopy(
    status: TerminalSetupStatus,
    statusMessage: String? = null,
    selectedProfile: TerminalPaymentProfile? = null,
): CheckoutBlockerCopy {
    val network = selectedProfile?.let { profile ->
        runCatching { KnownChainPolicy.requireProfile(profile.chainId) }.getOrNull()
    }
    val (title, detail) = when (status) {
        TerminalSetupStatus.CREATE_WALLET -> "Create terminal wallet" to
            "Create the device-local operator wallet before accepting payments."
        TerminalSetupStatus.SET_ADMIN_PIN -> "Set an admin PIN" to
            "Protect setup controls with the terminal's local admin PIN."
        TerminalSetupStatus.SCAN_PORTAL -> "Connect the merchant portal" to
            "Authorize this terminal, then scan the unified setup QR."
        TerminalSetupStatus.PROVISIONING -> "Validating terminal setup" to
            "Wait while the portal configuration is checked on-chain."
        TerminalSetupStatus.AWAITING_AUTHORIZATION -> "Authorize this terminal" to
            "Confirm the operator authorization in the merchant portal."
        TerminalSetupStatus.AWAITING_GAS -> "Fund terminal gas" to
            if (network == null) {
                "Send the required native gas reserve to the operator funding address."
            } else {
                "Send at least ${network.minimumOperatorNativeReserve} wei " +
                    "(${network.nativeCurrencySymbol}) to the operator funding address."
            }
        TerminalSetupStatus.READY -> "Refreshing terminal status" to
            "Checkout will unlock after the fresh on-chain validation completes."
        TerminalSetupStatus.ERROR -> "Terminal setup needs attention" to
            (statusMessage ?: "Open Settings to review the terminal status and resolve the issue.")
    }
    val actionLabel = when (status) {
        TerminalSetupStatus.CREATE_WALLET,
        TerminalSetupStatus.SET_ADMIN_PIN,
        TerminalSetupStatus.SCAN_PORTAL,
        -> "Finish terminal setup"
        else -> "Open Settings"
    }
    return CheckoutBlockerCopy(title, detail, actionLabel)
}

@Composable
private fun CheckoutError(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(
            message,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileSelector(
    profiles: List<TerminalPaymentProfile>,
    selected: TerminalPaymentProfile?,
    enabled: Boolean,
    onSelected: (TerminalPaymentProfile) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = selected?.let {
                "${it.token.symbol} · ${it.networkName} · " +
                    "${short(it.vaultAddress)} · ${short(it.token.address)}"
            } ?: "",
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text("Currency and destination") },
            placeholder = { Text("Select payment profile") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .testTag("checkout_profile_selector"),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            profiles.forEach { profile ->
                DropdownMenuItem(
                    text = {
                        Text(
                            "${profile.token.symbol} · ${profile.networkName}\n" +
                                "Vault ${short(profile.vaultAddress)} · Token ${short(profile.token.address)}",
                        )
                    },
                    onClick = { onSelected(profile); expanded = false },
                )
            }
        }
    }
}

private fun short(address: String): String =
    if (address.length <= 12) address else "${address.take(6)}…${address.takeLast(4)}"
