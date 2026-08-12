package com.openpasskey.terminal.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.openpasskey.erc681.EvmAddress
import com.openpasskey.erc681.NetworkConfig
import com.openpasskey.erc681.ReadOnlyRpcClient
import com.openpasskey.erc681.RpcRateLimit
import com.openpasskey.terminal.admin.AdminPinStore
import com.openpasskey.terminal.admin.AdminPinVerification
import com.openpasskey.terminal.chain.ChainConfig
import com.openpasskey.terminal.chain.ChainConfigMigrationNotice
import com.openpasskey.terminal.chain.MerchantReceiptProfile
import com.openpasskey.terminal.chain.PaymentToken
import com.openpasskey.terminal.chain.TerminalConfigSnapshot
import com.openpasskey.terminal.chain.TerminalPaymentProfile
import com.openpasskey.terminal.chain.resolvedPaymentProfiles
import com.openpasskey.terminal.chain.hasCompleteProvisioning
import com.openpasskey.terminal.provisioning.KnownChainPolicy
import com.openpasskey.terminal.provisioning.KnownChainProfile
import com.openpasskey.terminal.provisioning.minimumOperatorNativeReserveDisplay
import com.openpasskey.terminal.provisioning.TerminalProvisioner
import com.openpasskey.terminal.provisioning.TerminalProvisioningPayloadCodec
import com.openpasskey.terminal.rpc.RpcWorkCoordinator
import com.openpasskey.terminal.rpc.RpcEndpointOverrideState
import com.openpasskey.terminal.rpc.RpcEndpointNotConfiguredException
import com.openpasskey.terminal.rpc.RpcEndpointSource
import com.openpasskey.terminal.rpc.RpcEndpointStorageException
import com.openpasskey.terminal.rpc.RpcEndpointStore
import com.openpasskey.terminal.rpc.RpcEndpointVerifier
import com.openpasskey.terminal.rpc.safeReadRpcFailureMessage
import com.openpasskey.terminal.lifecycle.TerminalResetCoordinator
import com.openpasskey.terminal.lifecycle.TerminalLifecycleGate
import com.openpasskey.terminal.wallet.OperatorWalletAvailability
import com.openpasskey.terminal.wallet.OperatorWalletSnapshot
import com.openpasskey.terminal.wallet.OperatorWalletStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.math.BigInteger

enum class TerminalSetupStatus {
    CREATE_WALLET,
    SET_ADMIN_PIN,
    SCAN_PORTAL,
    PROVISIONING,
    AWAITING_AUTHORIZATION,
    AWAITING_GAS,
    READY,
    ERROR,
}

internal enum class ReadinessRefreshTrigger {
    NORMAL,
    INVOICE_FAILURE,
}

internal enum class ReadinessRpcPriority {
    AUTOMATIC,
    INTERACTIVE,
}

/** Automatic lifecycle/Sale refreshes yield to checkout; explicit actions retain cashier priority. */
internal class ReadinessRpcScheduler(
    private val coordinator: RpcWorkCoordinator,
) {
    suspend fun <T : Any> run(
        priority: ReadinessRpcPriority,
        block: suspend () -> T,
    ): T? = when (priority) {
        ReadinessRpcPriority.AUTOMATIC -> coordinator.withBackgroundOperation(block)
        ReadinessRpcPriority.INTERACTIVE -> coordinator.withInteractiveOperation(block)
    }
}

/** User-authorized provisioning/reset publishes interactive priority before lifecycle mutation. */
internal suspend fun <T> runUserRpcMutation(
    coordinator: RpcWorkCoordinator,
    block: suspend () -> T,
): T = coordinator.withInteractiveOperation(block)

/** Endpoint replacement must drain the one possible old-provider background read before commit. */
internal suspend fun <T> runExclusiveRpcEndpointMutation(
    coordinator: RpcWorkCoordinator,
    block: suspend () -> T,
): T = coordinator.withExclusiveInteractiveOperation(block)

internal const val BASE_RPC_BUSY_MESSAGE =
    "The selected Base RPC provider is busy. Wait a moment and try again. If this continues, " +
        "review the configured endpoint credentials and provider quota."

internal fun terminalRpcFailureMessage(error: Exception, fallback: String): String =
    when (error) {
        is RpcRateLimit -> BASE_RPC_BUSY_MESSAGE
        is RpcEndpointNotConfiguredException -> requireNotNull(error.message)
        else -> safeReadRpcFailureMessage(error, fallback)
    }

internal fun retryReadinessOnThrottle(priority: ReadinessRpcPriority): Boolean =
    priority == ReadinessRpcPriority.INTERACTIVE

internal fun shouldRestartActiveReadinessRefresh(
    trigger: ReadinessRefreshTrigger,
    refreshActive: Boolean,
): Boolean = refreshActive && trigger == ReadinessRefreshTrigger.INVOICE_FAILURE

internal fun readinessResultWhenAutomaticRefreshDefers(
    configurationStillValidated: Boolean,
    setupStatus: TerminalSetupStatus,
): Boolean = configurationStillValidated && setupStatus == TerminalSetupStatus.READY

/** Main-thread callback ownership for one readiness generation. Cancellation is a false result. */
internal class ReadinessRefreshCallbacks {
    private val callbacks = mutableListOf<(Boolean) -> Unit>()

    fun add(callback: (Boolean) -> Unit) {
        callbacks += callback
    }

    fun complete(ready: Boolean) {
        if (callbacks.isEmpty()) return
        val pending = callbacks.toList()
        callbacks.clear()
        pending.forEach { callback -> callback(ready) }
    }

    fun cancel() = complete(ready = false)
}

data class SettingsState(
    val merchantReceiptName: String = MerchantReceiptProfile.DEFAULT_NAME,
    val merchantReceiptAbn: String = "",
    val savingMerchantReceiptProfile: Boolean = false,
    val autoSweepEnabled: Boolean = false,
    val savingAutoSweepPreference: Boolean = false,
    val rpcEndpointSettings: List<RpcEndpointSetting> = emptyList(),
    val provisioningRpcEndpointAvailable: Boolean = false,
    val savingRpcEndpointChainId: Long? = null,
    val networkName: String = "",
    val isTestnet: Boolean = false,
    val nativeCurrencySymbol: String = "native currency",
    val nativeCurrencyDecimals: Int = 18,
    val minimumOperatorNativeReserveWei: String = "0",
    val rpcUrl: String = "",
    val chainId: Long = 0,
    val factoryAddress: String = "",
    val receiverImplementationAddress: String = "",
    val vaultAddress: String = "",
    val confirmationBlocks: Int = 0,
    val paymentTokens: List<PaymentToken> = emptyList(),
    val paymentProfiles: List<TerminalPaymentProfile> = emptyList(),
    val selectedProfileId: String? = null,
    val protocolVersion: String = "",
    val provisionedOperatorAddress: String? = null,
    val provisioned: Boolean = false,
    val setupStatus: TerminalSetupStatus = TerminalSetupStatus.CREATE_WALLET,
    val operatorWalletAvailability: OperatorWalletAvailability = OperatorWalletAvailability.NOT_CREATED,
    val operatorWalletAddress: String? = null,
    val operatorPairingPayload: String? = null,
    val operatorFundingPayload: String? = null,
    val operatorBalanceWei: String? = null,
    val operatorAuthorized: Boolean? = null,
    val configurationValidated: Boolean = false,
    val settlementTargetVerified: Boolean = false,
    val walletHardwareBacked: Boolean = false,
    val walletStrongBoxBacked: Boolean = false,
    val walletDeviceAuthenticationRequired: Boolean = false,
    val adminPinConfigured: Boolean = false,
    val adminUnlocked: Boolean = false,
    val adminRetryAfterSeconds: Long = 0,
    val refreshingOperator: Boolean = false,
    val message: String? = null,
    val migrationNotice: String? = null,
    val isError: Boolean = false,
)

enum class RpcEndpointOverrideStatus {
    NOT_CONFIGURED,
    READY,
    UNAVAILABLE,
}

/** Redacted endpoint metadata safe for display. A credential-bearing URL never enters UI state. */
data class RpcEndpointSetting(
    val chainId: Long,
    val networkName: String,
    val isTestnet: Boolean,
    val status: RpcEndpointOverrideStatus,
    val providerLabel: String?,
    val source: RpcEndpointSource,
)

internal data class MerchantReceiptProfileInputValidation(
    val profile: MerchantReceiptProfile?,
    val nameError: String?,
    val abnError: String?,
) {
    val isValid: Boolean get() = profile != null && nameError == null && abnError == null
}

/** Applies the domain validator independently so both fields can show actionable errors at once. */
internal fun validateMerchantReceiptProfileInput(
    name: String,
    abn: String,
): MerchantReceiptProfileInputValidation {
    val nameError = runCatching {
        MerchantReceiptProfile.fromInput(name, "")
    }.exceptionOrNull()?.message
    val abnError = runCatching {
        MerchantReceiptProfile.fromInput(MerchantReceiptProfile.DEFAULT_NAME, abn)
    }.exceptionOrNull()?.message
    val profile = if (nameError == null && abnError == null) {
        MerchantReceiptProfile.fromInput(name, abn)
    } else {
        null
    }
    return MerchantReceiptProfileInputValidation(
        profile = profile,
        nameError = nameError,
        abnError = abnError,
    )
}

/**
 * Process-local admin session boundary. Every new unlock attempt receives an epoch, and locking the
 * session invalidates that epoch even when verification is still running on a background thread.
 */
internal class AdminSessionGate {
    private var epoch = 0L
    private var unlocked = false

    @Synchronized
    fun beginUnlock(): Long {
        epoch += 1
        unlocked = false
        return epoch
    }

    @Synchronized
    fun completeUnlock(attemptEpoch: Long): Boolean {
        if (attemptEpoch != epoch) return false
        unlocked = true
        return true
    }

    @Synchronized
    fun isCurrent(attemptEpoch: Long): Boolean = attemptEpoch == epoch

    @Synchronized
    fun lock(): Boolean {
        epoch += 1
        val wasUnlocked = unlocked
        unlocked = false
        return wasUnlocked
    }

    @Synchronized
    fun isUnlocked(): Boolean = unlocked

    @Synchronized
    fun unlockedEpochOrNull(): Long? = epoch.takeIf { unlocked }

    @Synchronized
    fun isAuthorized(attemptEpoch: Long): Boolean = unlocked && attemptEpoch == epoch

    @Synchronized
    fun <T> withAuthorization(attemptEpoch: Long, block: () -> T): T {
        check(unlocked && attemptEpoch == epoch) {
            "Admin/setup session is locked; unlock it before changing terminal setup"
        }
        return block()
    }
}

internal fun requireAdminAuthorizationEpoch(
    pinConfigured: Boolean,
    session: AdminSessionGate,
    action: String,
): Long? {
    if (!pinConfigured) return null
    return session.unlockedEpochOrNull()
        ?: throw IllegalStateException("Unlock Admin/setup before $action.")
}

class SettingsViewModel(
    private val chainConfig: ChainConfig,
    private val walletStore: OperatorWalletStore,
    private val adminPinStore: AdminPinStore,
    private val provisioner: TerminalProvisioner,
    private val resetCoordinator: TerminalResetCoordinator,
    private val lifecycleGate: TerminalLifecycleGate,
    private val rpcWorkCoordinator: RpcWorkCoordinator,
    private val rpcEndpointStore: RpcEndpointStore,
    private val rpcEndpointVerifier: RpcEndpointVerifier,
) : ViewModel() {
    private val adminSession = AdminSessionGate()
    private var refreshJob: Job? = null
    private var refreshPriority: ReadinessRpcPriority? = null
    private var refreshGeneration = 0L
    private var provisioningJob: Job? = null
    private var provisioningGeneration = 0L
    private var merchantReceiptProfileJob: Job? = null
    private var autoSweepPreferenceJob: Job? = null
    private var rpcEndpointMutationJob: Job? = null
    private var rpcEndpointMutationGeneration = 0L
    private var rpcEndpointMutationChainId: Long? = null
    private var walletCreationAuthorizationEpoch: Long? = null
    private var validatedConfiguration: TerminalConfigSnapshot? = null
    private val refreshCompletionCallbacks = ReadinessRefreshCallbacks()
    private val readinessRpcScheduler = ReadinessRpcScheduler(rpcWorkCoordinator)
    private var migrationNoticeMessage = run {
        // snapshot() performs any v2 -> v3 migration before the notice is queried.
        chainConfig.snapshot()
        chainConfig.pendingMigrationNotice()?.let(::chainConfigMigrationNoticeMessage)
    }
    private val _state = MutableStateFlow(load())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    init {
        refreshOperatorStatusAutomatically()
    }

    fun acknowledgeMigrationNotice() {
        if (migrationNoticeMessage == null) return
        if (!chainConfig.acknowledgeMigrationNotice()) {
            _state.value = _state.value.copy(
                message = "Unable to save acknowledgement. The security update will remain visible.",
                isError = true,
            )
            return
        }
        migrationNoticeMessage = null
        _state.value = _state.value.copy(migrationNotice = null)
    }

    private fun load(
        message: String? = null,
        isError: Boolean = false,
        setupStatusOverride: TerminalSetupStatus? = null,
    ): SettingsState {
        val config = chainConfig.snapshot()
        val merchantReceiptProfile = runCatching { chainConfig.merchantReceiptProfile() }
            .getOrNull()
            ?: MerchantReceiptProfile.DEFAULT
        val autoSweepEnabled = chainConfig.autoSweepEnabled()
        val rpcEndpointSnapshots = KnownChainPolicy.enabledProfiles().associate { profile ->
            profile.chainId to rpcEndpointStore.snapshot(profile.chainId)
        }
        val rpcEndpointSettings = KnownChainPolicy.enabledProfiles().map { profile ->
            val endpoint = rpcEndpointSnapshots.getValue(profile.chainId)
            RpcEndpointSetting(
                chainId = profile.chainId,
                networkName = profile.networkName,
                isTestnet = profile.isTestnet,
                status = when (endpoint.state) {
                    RpcEndpointOverrideState.NOT_CONFIGURED ->
                        RpcEndpointOverrideStatus.NOT_CONFIGURED
                    RpcEndpointOverrideState.READY -> RpcEndpointOverrideStatus.READY
                    RpcEndpointOverrideState.UNAVAILABLE -> RpcEndpointOverrideStatus.UNAVAILABLE
                },
                providerLabel = endpoint.providerLabel,
                source = endpoint.source,
            )
        }
        val wallet = walletStore.snapshot()
        val admin = adminPinStore.snapshot()
        val pairing = wallet.address?.let {
            runCatching { TerminalProvisioningPayloadCodec.encodeOperatorPairing(it) }.getOrNull()
        }
        val networkPolicy = runCatching {
            KnownChainPolicy.requireProfile(config.chainId)
        }.getOrNull()
        val operatorBindingMatches = config.provisioned && wallet.address != null &&
            config.provisionedOperatorAddress?.equals(wallet.address, true) == true
        val status = when {
            wallet.availability == OperatorWalletAvailability.UNAVAILABLE -> TerminalSetupStatus.ERROR
            wallet.availability != OperatorWalletAvailability.READY -> TerminalSetupStatus.CREATE_WALLET
            !admin.configured -> TerminalSetupStatus.SET_ADMIN_PIN
            !config.provisioned -> TerminalSetupStatus.SCAN_PORTAL
            !operatorBindingMatches -> TerminalSetupStatus.ERROR
            setupStatusOverride != null -> setupStatusOverride
            isError -> TerminalSetupStatus.ERROR
            else -> TerminalSetupStatus.AWAITING_AUTHORIZATION
        }
        return SettingsState(
            merchantReceiptName = merchantReceiptProfile.name,
            merchantReceiptAbn = merchantReceiptProfile.abn,
            savingMerchantReceiptProfile = merchantReceiptProfileJob?.isActive == true,
            autoSweepEnabled = autoSweepEnabled,
            savingAutoSweepPreference = autoSweepPreferenceJob?.isActive == true,
            rpcEndpointSettings = rpcEndpointSettings,
            provisioningRpcEndpointAvailable = rpcEndpointSnapshots.values.any { it.available },
            savingRpcEndpointChainId = rpcEndpointMutationChainId
                .takeIf { rpcEndpointMutationJob?.isActive == true },
            networkName = config.networkName,
            isTestnet = networkPolicy?.isTestnet ?: false,
            nativeCurrencySymbol = networkPolicy?.nativeCurrencySymbol ?: "native currency",
            nativeCurrencyDecimals = networkPolicy?.nativeCurrencyDecimals ?: 18,
            minimumOperatorNativeReserveWei = networkPolicy
                ?.minimumOperatorNativeReserve
                ?.toString()
                ?: "0",
            rpcUrl = config.rpcUrl,
            chainId = config.chainId,
            factoryAddress = config.factoryAddress,
            receiverImplementationAddress = config.receiverImplementationAddress,
            vaultAddress = config.vaultAddress,
            confirmationBlocks = config.confirmationBlocks,
            paymentTokens = config.paymentTokens,
            paymentProfiles = config.resolvedPaymentProfiles(),
            selectedProfileId = config.selectedProfileId,
            protocolVersion = config.protocolVersion,
            provisionedOperatorAddress = config.provisionedOperatorAddress,
            provisioned = config.provisioned,
            setupStatus = status,
            operatorWalletAvailability = wallet.availability,
            operatorWalletAddress = wallet.address,
            operatorPairingPayload = pairing,
            operatorFundingPayload = operatorFundingPayload(config, wallet),
            configurationValidated = validatedConfiguration == config,
            settlementTargetVerified = operatorBindingMatches && wallet.isVerifiedFor(
                config.chainId,
                config.vaultAddress,
                requireNotNull(config.provisionedOperatorAddress),
            ),
            walletHardwareBacked = wallet.hardwareBacked,
            walletStrongBoxBacked = wallet.strongBoxBacked,
            walletDeviceAuthenticationRequired = wallet.deviceAuthenticationRequired,
            adminPinConfigured = admin.configured,
            adminUnlocked = adminSession.isUnlocked(),
            adminRetryAfterSeconds = admin.retryAfterSeconds,
            message = message ?: wallet.error ?: if (config.provisioned && !operatorBindingMatches) {
                "Provisioned operator does not match the local terminal wallet"
            } else {
                null
            },
            migrationNotice = migrationNoticeMessage,
            isError = isError || wallet.availability == OperatorWalletAvailability.UNAVAILABLE ||
                (config.provisioned && !operatorBindingMatches),
        )
    }

    /** Returns true when the UI should immediately present the OS authentication prompt. */
    fun prepareWalletCreation(): Boolean = try {
        walletCreationAuthorizationEpoch = requireAdminAuthorizationEpoch(
            adminPinStore.snapshot().configured,
            adminSession,
            "creating a terminal wallet",
        )
        walletStore.prepareWalletCreation()
        _state.value = _state.value.copy(
            message = "Authenticate to encrypt the new operator key.",
            isError = false,
        )
        true
    } catch (error: Exception) {
        walletCreationAuthorizationEpoch = null
        _state.value = _state.value.copy(message = error.message, isError = true)
        false
    }

    /** Called only after a successful biometric/device-credential prompt. */
    fun createWalletAuthenticated() {
        val authorizationEpoch = walletCreationAuthorizationEpoch
        viewModelScope.launch {
            try {
                val wallet = withContext(Dispatchers.IO) {
                    if (adminPinStore.snapshot().configured) {
                        adminSession.withAuthorization(
                            requireNotNull(authorizationEpoch) {
                                "Unlock Admin/setup and restart wallet creation"
                            },
                        ) { walletStore.createWallet() }
                    } else {
                        walletStore.createWallet()
                    }
                }
                walletCreationAuthorizationEpoch = null
                _state.value = load(
                    "Operator wallet ${wallet.address} created. Set a local admin PIN, then pair it in the merchant portal.",
                )
            } catch (error: Exception) {
                walletCreationAuthorizationEpoch = null
                _state.value = load(error.message ?: "Unable to create operator wallet", isError = true)
            }
        }
    }

    fun authenticationFailed(message: String) {
        walletCreationAuthorizationEpoch = null
        _state.value = _state.value.copy(message = "Authentication failed: $message", isError = true)
    }

    fun setInitialAdminPin(pin: String, confirmation: String) {
        if (pin != confirmation) {
            _state.value = _state.value.copy(message = "PIN confirmation does not match.", isError = true)
            return
        }
        val attemptEpoch = adminSession.beginUnlock()
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { adminPinStore.setInitialPin(pin) }
                val unlocked = adminSession.completeUnlock(attemptEpoch)
                _state.value = load(
                    if (unlocked) {
                        "Admin PIN saved and Admin/setup unlocked. Use the secure merchant portal to authorize this terminal."
                    } else {
                        "Admin PIN saved. Unlock Admin/setup to continue."
                    },
                )
            } catch (error: Exception) {
                _state.value = load(error.message ?: "Unable to save the admin PIN", isError = true)
            }
        }
    }

    fun unlockAdmin(pin: String) {
        val existingStatus = _state.value.setupStatus
        val attemptEpoch = adminSession.beginUnlock()
        viewModelScope.launch {
            val result = try {
                withContext(Dispatchers.IO) { adminPinStore.verify(pin) }
            } catch (error: Exception) {
                if (!adminSession.isCurrent(attemptEpoch)) return@launch
                _state.value = load(
                    error.message ?: "Unable to verify the admin PIN",
                    isError = true,
                    setupStatusOverride = existingStatus,
                )
                return@launch
            }
            when (result) {
                AdminPinVerification.ACCEPTED -> {
                    if (!adminSession.completeUnlock(attemptEpoch)) return@launch
                    _state.value = load(
                        "Admin/setup controls unlocked.",
                        setupStatusOverride = existingStatus,
                    )
                }
                AdminPinVerification.REJECTED -> {
                    if (!adminSession.isCurrent(attemptEpoch)) return@launch
                    _state.value = load(
                        "Incorrect admin PIN.",
                        isError = true,
                        setupStatusOverride = existingStatus,
                    )
                }
                AdminPinVerification.LOCKED -> {
                    if (!adminSession.isCurrent(attemptEpoch)) return@launch
                    val retry = adminPinStore.snapshot().retryAfterSeconds
                    _state.value = load(
                        "Too many attempts. Try again in $retry seconds.",
                        isError = true,
                        setupStatusOverride = existingStatus,
                    )
                }
                AdminPinVerification.NOT_CONFIGURED -> {
                    if (!adminSession.isCurrent(attemptEpoch)) return@launch
                    _state.value = load(
                        "Set the local admin PIN first.",
                        isError = true,
                        setupStatusOverride = existingStatus,
                    )
                }
            }
        }
    }

    fun lockAdmin() {
        val wasUnlocked = adminSession.lock()
        walletCreationAuthorizationEpoch = null
        val cancelledProvisioning = provisioningJob?.isActive == true
        val cancelledRpcEndpointMutation = rpcEndpointMutationJob?.isActive == true
        if (cancelledProvisioning) {
            provisioningGeneration += 1
            provisioningJob?.cancel()
            provisioningJob = null
        }
        if (cancelledRpcEndpointMutation) {
            rpcEndpointMutationGeneration += 1
            rpcEndpointMutationJob?.cancel()
            rpcEndpointMutationJob = null
            rpcEndpointMutationChainId = null
        }
        if (wasUnlocked || cancelledProvisioning || cancelledRpcEndpointMutation) {
            _state.value = if (cancelledProvisioning || cancelledRpcEndpointMutation) {
                load(
                    if (cancelledProvisioning) {
                        "Admin/setup controls locked; provisioning was cancelled."
                    } else {
                        "Admin/setup controls locked; the RPC endpoint change was cancelled."
                    },
                )
            } else _state.value.copy(
                adminUnlocked = false,
                message = "Admin/setup controls locked.",
                isError = false,
            )
        }
    }

    fun provision(rawPayload: String) {
        val current = _state.value
        val previousSetupStatus = current.setupStatus
        if (provisioningJob?.isActive == true) {
            _state.value = current.copy(message = "Provisioning is already in progress.", isError = true)
            return
        }
        if (merchantReceiptProfileJob?.isActive == true ||
            autoSweepPreferenceJob?.isActive == true ||
            rpcEndpointMutationJob?.isActive == true
        ) {
            _state.value = current.copy(
                message = "Wait for the current settings change to finish before provisioning.",
                isError = true,
            )
            return
        }
        val admin = adminPinStore.snapshot()
        if (!admin.configured) {
            _state.value = current.copy(message = "Set the local admin PIN before provisioning.", isError = true)
            return
        }
        val authorizationEpoch = runCatching {
            requireNotNull(
                requireAdminAuthorizationEpoch(admin.configured, adminSession, "provisioning this terminal"),
            )
        }.getOrElse { error ->
            _state.value = current.copy(message = error.message, isError = true)
            return
        }
        val provisioningPolicy = runCatching {
            val payload = TerminalProvisioningPayloadCodec.parse(rawPayload)
            KnownChainPolicy.requireProfile(payload.chainId)
        }.getOrElse { error ->
            _state.value = current.copy(
                message = error.message ?: "Invalid terminal provisioning QR.",
                isError = true,
            )
            return
        }
        val rpcPrerequisite = rpcEndpointProvisioningPrerequisiteMessage(
            networkName = provisioningPolicy.networkName,
            source = rpcEndpointStore.snapshot(provisioningPolicy.chainId).source,
        )
        if (rpcPrerequisite != null) {
            _state.value = current.copy(message = rpcPrerequisite, isError = true)
            return
        }
        invalidateReadinessRefresh()
        _state.value = current.copy(
            setupStatus = TerminalSetupStatus.PROVISIONING,
            refreshingOperator = true,
            message = "Validating portal configuration on-chain…",
            isError = false,
        )
        val generation = ++provisioningGeneration
        provisioningJob = viewModelScope.launch {
            try {
                val result = runUserRpcMutation(rpcWorkCoordinator) {
                    provisioner.provision(rawPayload, walletStore.snapshot()) { commit ->
                        adminSession.withAuthorization(authorizationEpoch, commit)
                    }
                }
                adminSession.lock()
                _state.value = load(
                    "Added ${result.token.symbol} on ${result.profile.networkName}. " +
                        "${result.configuration.resolvedPaymentProfiles().size} payment profile(s) configured. " +
                        "Waiting for vault authorization and terminal gas funding.",
                )
                refreshOperatorStatus()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value = load(
                    terminalRpcFailureMessage(error, "Provisioning failed"),
                    isError = true,
                    setupStatusOverride = previousSetupStatus,
                )
            } finally {
                if (generation == provisioningGeneration) provisioningJob = null
            }
        }
    }

    fun provisionManual(chainId: Long, vaultAddress: String, tokenAddress: String) {
        if (!adminSession.isUnlocked()) {
            _state.value = _state.value.copy(
                message = "Unlock Admin/setup before using manual setup.",
                isError = true,
            )
            return
        }
        val operator = walletStore.snapshot().address
        if (operator == null) {
            _state.value = _state.value.copy(message = "Create the operator wallet first.", isError = true)
            return
        }
        val payload = runCatching {
            TerminalProvisioningPayloadCodec.encodeProvisioning(
                chainId = KnownChainPolicy.requireProfile(chainId).chainId,
                vault = vaultAddress,
                token = tokenAddress,
                operator = operator,
            )
        }.getOrElse { error ->
            _state.value = _state.value.copy(
                message = error.message ?: "Invalid manual setup addresses",
                isError = true,
            )
            return
        }
        provision(payload)
    }

    fun updateMerchantReceiptProfile(name: String, abn: String) {
        if (_state.value.savingMerchantReceiptProfile ||
            merchantReceiptProfileJob?.isActive == true
        ) return
        if (provisioningJob?.isActive == true) {
            _state.value = _state.value.copy(
                message = "Wait for provisioning to finish before changing receipt details.",
                isError = true,
            )
            return
        }
        if (autoSweepPreferenceJob?.isActive == true) {
            _state.value = _state.value.copy(
                message = "Wait for the auto-sweep setting to finish saving.",
                isError = true,
            )
            return
        }
        if (rpcEndpointMutationJob?.isActive == true) {
            _state.value = _state.value.copy(
                message = "Wait for the RPC endpoint change to finish.",
                isError = true,
            )
            return
        }
        val validation = validateMerchantReceiptProfileInput(name, abn)
        val profile = validation.profile
        if (profile == null) {
            _state.value = _state.value.copy(
                message = validation.nameError ?: validation.abnError
                    ?: "Invalid merchant receipt details",
                isError = true,
            )
            return
        }
        val authorizationEpoch = runCatching {
            requireNotNull(
                requireAdminAuthorizationEpoch(
                    adminPinStore.snapshot().configured,
                    adminSession,
                    "changing merchant receipt details",
                ),
            )
        }.getOrElse { error ->
            _state.value = _state.value.copy(message = error.message, isError = true)
            return
        }
        val previousSetupStatus = _state.value.setupStatus
        _state.value = _state.value.copy(
            savingMerchantReceiptProfile = true,
            message = null,
            isError = false,
        )
        merchantReceiptProfileJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    updateMerchantReceiptProfileExclusively(
                        lifecycleGate = lifecycleGate,
                        commitWithAuthorization = { commit ->
                            adminSession.withAuthorization(authorizationEpoch, commit)
                        },
                        update = {
                            chainConfig.updateMerchantReceiptProfile(profile.name, profile.abn)
                        },
                    )
                }
                adminSession.lock()
                _state.value = load(
                    message = "Merchant receipt details saved. New invoices use this profile; " +
                        "existing receipts keep their original details.",
                    setupStatusOverride = previousSetupStatus,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value = load(
                    message = error.message ?: "Unable to save merchant receipt details",
                    isError = true,
                    setupStatusOverride = previousSetupStatus,
                )
            } finally {
                merchantReceiptProfileJob = null
                _state.value = _state.value.copy(savingMerchantReceiptProfile = false)
            }
        }
    }

    fun updateAutoSweepEnabled(enabled: Boolean) {
        if (_state.value.savingAutoSweepPreference || autoSweepPreferenceJob?.isActive == true) {
            return
        }
        if (provisioningJob?.isActive == true) {
            _state.value = _state.value.copy(
                message = "Wait for provisioning to finish before changing auto-sweep.",
                isError = true,
            )
            return
        }
        if (merchantReceiptProfileJob?.isActive == true) {
            _state.value = _state.value.copy(
                message = "Wait for the receipt details to finish saving.",
                isError = true,
            )
            return
        }
        if (rpcEndpointMutationJob?.isActive == true) {
            _state.value = _state.value.copy(
                message = "Wait for the RPC endpoint change to finish.",
                isError = true,
            )
            return
        }
        if (enabled == chainConfig.autoSweepEnabled()) return
        val authorizationEpoch = runCatching {
            requireNotNull(
                requireAdminAuthorizationEpoch(
                    adminPinStore.snapshot().configured,
                    adminSession,
                    "changing auto-sweep",
                ),
            )
        }.getOrElse { error ->
            _state.value = _state.value.copy(message = error.message, isError = true)
            return
        }
        val previousSetupStatus = _state.value.setupStatus
        _state.value = _state.value.copy(
            savingAutoSweepPreference = true,
            message = null,
            isError = false,
        )
        autoSweepPreferenceJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    updateAutoSweepPreferenceExclusively(
                        lifecycleGate = lifecycleGate,
                        commitWithAuthorization = { commit ->
                            adminSession.withAuthorization(authorizationEpoch, commit)
                        },
                        update = { chainConfig.updateAutoSweepEnabled(enabled) },
                    )
                }
                adminSession.lock()
                _state.value = load(
                    message = if (enabled) {
                        "Auto-sweep enabled. Newly issued invoices with their own incoming " +
                            "transaction evidence will open the review and device-authenticated " +
                            "settlement flow after canonical confirmation. Late payments remain " +
                            "manual."
                    } else {
                        "Auto-sweep disabled. Ready payments remain available on the Settle screen."
                    },
                    setupStatusOverride = previousSetupStatus,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value = load(
                    message = error.message ?: "Unable to save auto-sweep preference",
                    isError = true,
                    setupStatusOverride = previousSetupStatus,
                )
            } finally {
                autoSweepPreferenceJob = null
                _state.value = _state.value.copy(savingAutoSweepPreference = false)
            }
        }
    }

    fun updateRpcEndpoint(chainId: Long, rawRpcUrl: String) {
        if (rpcEndpointMutationJob?.isActive == true) return
        if (provisioningJob?.isActive == true ||
            merchantReceiptProfileJob?.isActive == true ||
            autoSweepPreferenceJob?.isActive == true
        ) {
            _state.value = _state.value.copy(
                message = "Wait for the current setup change to finish before changing RPC endpoints.",
                isError = true,
            )
            return
        }
        val policy = runCatching { KnownChainPolicy.requireProfile(chainId) }.getOrElse { error ->
            _state.value = _state.value.copy(
                message = error.message ?: "Unsupported RPC endpoint network",
                isError = true,
            )
            return
        }
        val authorizationEpoch = runCatching {
            requireNotNull(
                requireAdminAuthorizationEpoch(
                    adminPinStore.snapshot().configured,
                    adminSession,
                    "changing RPC endpoints",
                ),
            )
        }.getOrElse { error ->
            _state.value = _state.value.copy(message = error.message, isError = true)
            return
        }
        val previousSetupStatus = _state.value.setupStatus
        invalidateReadinessRefresh()
        rpcEndpointMutationChainId = chainId
        _state.value = _state.value.copy(
            savingRpcEndpointChainId = chainId,
            message = "Verifying the ${policy.networkName} RPC endpoint on-chain…",
            isError = false,
        )
        val generation = ++rpcEndpointMutationGeneration
        rpcEndpointMutationJob = viewModelScope.launch {
            try {
                runExclusiveRpcEndpointMutation(rpcWorkCoordinator) {
                    updateRpcEndpointExclusively(
                        lifecycleGate = lifecycleGate,
                        chainId = chainId,
                        rawRpcUrl = rawRpcUrl,
                        currentConfiguration = chainConfig::snapshot,
                        validateCandidate = rpcEndpointStore::validateCandidate,
                        verify = rpcEndpointVerifier::verify,
                        commitWithAuthorization = { commit ->
                            adminSession.withAuthorization(authorizationEpoch, commit)
                        },
                        update = rpcEndpointStore::setOverride,
                    )
                }
                val providerLabel = rpcEndpointStore.snapshot(chainId).providerLabel
                    ?: "dedicated HTTPS provider"
                adminSession.lock()
                _state.value = load(
                    message = "$providerLabel saved for ${policy.networkName}. " +
                        "The credential-bearing URL remains masked.",
                    setupStatusOverride = previousSetupStatus,
                )
                if (chainConfig.snapshot().let { it.provisioned && it.chainId == chainId }) {
                    refreshOperatorStatus()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (generation != rpcEndpointMutationGeneration) return@launch
                _state.value = load(
                    message = rpcEndpointMutationFailureMessage(error),
                    isError = true,
                    setupStatusOverride = previousSetupStatus,
                )
            } finally {
                if (generation == rpcEndpointMutationGeneration) {
                    rpcEndpointMutationJob = null
                    rpcEndpointMutationChainId = null
                    _state.value = _state.value.copy(savingRpcEndpointChainId = null)
                }
            }
        }
    }

    fun clearRpcEndpoint(chainId: Long) {
        if (rpcEndpointMutationJob?.isActive == true) return
        if (provisioningJob?.isActive == true ||
            merchantReceiptProfileJob?.isActive == true ||
            autoSweepPreferenceJob?.isActive == true
        ) {
            _state.value = _state.value.copy(
                message = "Wait for the current setup change to finish before clearing an RPC endpoint.",
                isError = true,
            )
            return
        }
        val policy = runCatching { KnownChainPolicy.requireProfile(chainId) }.getOrElse { error ->
            _state.value = _state.value.copy(
                message = error.message ?: "Unsupported RPC endpoint network",
                isError = true,
            )
            return
        }
        val authorizationEpoch = runCatching {
            requireNotNull(
                requireAdminAuthorizationEpoch(
                    adminPinStore.snapshot().configured,
                    adminSession,
                    "clearing an RPC endpoint",
                ),
            )
        }.getOrElse { error ->
            _state.value = _state.value.copy(message = error.message, isError = true)
            return
        }
        val previousSetupStatus = _state.value.setupStatus
        invalidateReadinessRefresh()
        rpcEndpointMutationChainId = chainId
        _state.value = _state.value.copy(
            savingRpcEndpointChainId = chainId,
            message = "Clearing the ${policy.networkName} RPC endpoint override…",
            isError = false,
        )
        val generation = ++rpcEndpointMutationGeneration
        rpcEndpointMutationJob = viewModelScope.launch {
            try {
                runExclusiveRpcEndpointMutation(rpcWorkCoordinator) {
                    clearRpcEndpointExclusively(
                        lifecycleGate = lifecycleGate,
                        chainId = chainId,
                        commitWithAuthorization = { commit ->
                            adminSession.withAuthorization(authorizationEpoch, commit)
                        },
                        clear = rpcEndpointStore::clearOverride,
                    )
                }
                val fallbackDescription = when (rpcEndpointStore.snapshot(chainId).source) {
                    RpcEndpointSource.BUILD_MANAGED ->
                        "The app build default is active again."
                    RpcEndpointSource.PUBLIC_FALLBACK ->
                        "The development-only Base public RPC fallback is active again."
                    RpcEndpointSource.MISSING ->
                        "No RPC endpoint is active. Configure a replacement before using this network."
                    RpcEndpointSource.UNAVAILABLE ->
                        "The saved endpoint remains unavailable; configure a replacement."
                    RpcEndpointSource.ADMIN_OVERRIDE ->
                        "The administrator override remains active."
                }
                adminSession.lock()
                _state.value = load(
                    message = "RPC override cleared for ${policy.networkName}. " +
                        fallbackDescription,
                    setupStatusOverride = previousSetupStatus,
                )
                if (chainConfig.snapshot().let { it.provisioned && it.chainId == chainId }) {
                    refreshOperatorStatus()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (generation != rpcEndpointMutationGeneration) return@launch
                _state.value = load(
                    message = rpcEndpointMutationFailureMessage(error),
                    isError = true,
                    setupStatusOverride = previousSetupStatus,
                )
            } finally {
                if (generation == rpcEndpointMutationGeneration) {
                    rpcEndpointMutationJob = null
                    rpcEndpointMutationChainId = null
                    _state.value = _state.value.copy(savingRpcEndpointChainId = null)
                }
            }
        }
    }

    /** Synchronizes the Settings toggle after the settlement safety ledger reaches capacity. */
    fun autoSweepDisabledBySafetyCapacity() {
        _state.value = _state.value.copy(
            autoSweepEnabled = false,
            savingAutoSweepPreference = false,
            message = "Auto-sweep was turned off because its dismissal history is full. " +
                "Explicitly re-enable it to start a new review session.",
            isError = true,
        )
    }

    fun updateNetworkConfirmationBlocks(chainId: Long, confirmationBlocks: Int) {
        if (provisioningJob?.isActive == true) {
            _state.value = _state.value.copy(
                message = "Wait for provisioning to finish before changing confirmations.",
                isError = true,
            )
            return
        }
        if (merchantReceiptProfileJob?.isActive == true ||
            autoSweepPreferenceJob?.isActive == true ||
            rpcEndpointMutationJob?.isActive == true
        ) {
            _state.value = _state.value.copy(
                message = "Wait for the current settings change to finish before changing confirmations.",
                isError = true,
            )
            return
        }
        val policy = runCatching { KnownChainPolicy.requireProfile(chainId) }.getOrElse { error ->
            _state.value = _state.value.copy(message = error.message, isError = true)
            return
        }
        if (confirmationBlocks !in policy.minimumConfirmationBlocks..64) {
            _state.value = _state.value.copy(
                message = "Confirmations for ${policy.networkName} must be between " +
                    "${policy.minimumConfirmationBlocks} and 64.",
                isError = true,
            )
            return
        }
        val networkIsConfigured = chainConfig.snapshot().resolvedPaymentProfiles()
            .any { it.chainId == chainId }
        if (!networkIsConfigured) {
            _state.value = _state.value.copy(
                message = "${policy.networkName} is not configured on this terminal.",
                isError = true,
            )
            return
        }
        val authorizationEpoch = runCatching {
            requireNotNull(
                requireAdminAuthorizationEpoch(
                    adminPinStore.snapshot().configured,
                    adminSession,
                    "changing network confirmation requirements",
                ),
            )
        }.getOrElse { error ->
            _state.value = _state.value.copy(message = error.message, isError = true)
            return
        }
        invalidateReadinessRefresh()
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    updateNetworkConfirmationBlocksExclusively(
                        lifecycleGate = lifecycleGate,
                        chainId = chainId,
                        confirmationBlocks = confirmationBlocks,
                        commitWithAuthorization = { commit ->
                            adminSession.withAuthorization(authorizationEpoch, commit)
                        },
                        update = chainConfig::updateNetworkConfirmationBlocks,
                    )
                }
                adminSession.lock()
                _state.value = load(
                    networkConfirmationUpdateSuccessMessage(
                        policy.networkName,
                        confirmationBlocks,
                    ),
                )
                refreshOperatorStatus()
            } catch (error: Exception) {
                _state.value = load(
                    error.message ?: "Unable to update network confirmations",
                    isError = true,
                )
                refreshOperatorStatus()
            }
        }
    }

    fun removePaymentProfile(profileId: String) {
        if (provisioningJob?.isActive == true) {
            _state.value = _state.value.copy(
                message = "Wait for provisioning to finish before removing a payment profile.",
                isError = true,
            )
            return
        }
        if (merchantReceiptProfileJob?.isActive == true ||
            autoSweepPreferenceJob?.isActive == true ||
            rpcEndpointMutationJob?.isActive == true
        ) {
            _state.value = _state.value.copy(
                message = "Wait for the current settings change to finish before removing a payment profile.",
                isError = true,
            )
            return
        }
        val authorizationEpoch = runCatching {
            requireNotNull(
                requireAdminAuthorizationEpoch(
                    adminPinStore.snapshot().configured,
                    adminSession,
                    "removing a payment profile",
                ),
            )
        }.getOrElse { error ->
            _state.value = _state.value.copy(message = error.message, isError = true)
            return
        }
        val removing = chainConfig.snapshot().resolvedPaymentProfiles()
            .firstOrNull { it.id == profileId }
        if (removing == null) {
            _state.value = _state.value.copy(
                message = "Payment profile is no longer configured.",
                isError = true,
            )
            return
        }
        invalidateReadinessRefresh()
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    removePaymentProfileExclusively(
                        lifecycleGate = lifecycleGate,
                        profileId = profileId,
                        commitWithAuthorization = { commit ->
                            adminSession.withAuthorization(authorizationEpoch, commit)
                        },
                        removeProfile = chainConfig::removeProfile,
                    )
                }
                adminSession.lock()
                val remaining = chainConfig.snapshot().resolvedPaymentProfiles().size
                _state.value = load(paymentProfileRemovalSuccessMessage(removing, remaining))
                if (remaining > 0) refreshOperatorStatus()
            } catch (error: Exception) {
                _state.value = load(
                    error.message ?: "Unable to remove the payment profile",
                    isError = true,
                )
                refreshOperatorStatus()
            }
        }
    }

    fun refreshOperatorStatus() = refreshOperatorStatusInternal(
        priority = ReadinessRpcPriority.INTERACTIVE,
    )

    fun refreshOperatorStatus(onComplete: (Boolean) -> Unit) =
        refreshOperatorStatusInternal(onComplete, ReadinessRpcPriority.INTERACTIVE)

    fun refreshOperatorStatusAutomatically() = refreshOperatorStatusInternal(
        priority = ReadinessRpcPriority.AUTOMATIC,
    )

    fun refreshOperatorStatusAutomatically(onComplete: (Boolean) -> Unit) =
        refreshOperatorStatusInternal(onComplete, ReadinessRpcPriority.AUTOMATIC)

    fun refreshOperatorStatusAfterInvoiceFailure(onComplete: (Boolean) -> Unit) {
        if (shouldRestartActiveReadinessRefresh(
                ReadinessRefreshTrigger.INVOICE_FAILURE,
                refreshJob?.isActive == true,
            )
        ) {
            invalidateReadinessRefresh()
        }
        refreshOperatorStatusInternal(onComplete, ReadinessRpcPriority.INTERACTIVE)
    }

    fun refreshOperatorStatusAfterProfileSelection(onComplete: (Boolean) -> Unit) {
        invalidateReadinessRefresh()
        refreshOperatorStatusInternal(onComplete, ReadinessRpcPriority.INTERACTIVE)
    }

    private fun refreshOperatorStatusInternal(
        onComplete: ((Boolean) -> Unit)? = null,
        priority: ReadinessRpcPriority,
    ) {
        if (refreshJob?.isActive == true) {
            if (priority == ReadinessRpcPriority.INTERACTIVE &&
                refreshPriority == ReadinessRpcPriority.AUTOMATIC
            ) {
                invalidateReadinessRefresh()
            } else {
                onComplete?.let(refreshCompletionCallbacks::add)
                return
            }
        }
        onComplete?.let(refreshCompletionCallbacks::add)
        val generation = ++refreshGeneration
        val wallet = walletStore.snapshot()
        val address = wallet.address
        val config = chainConfig.snapshot()
        if (priority == ReadinessRpcPriority.INTERACTIVE) validatedConfiguration = null
        if (wallet.availability != OperatorWalletAvailability.READY || address == null || !config.provisioned) {
            _state.value = load()
            completeReadinessCallbacks(ready = false)
            return
        }
        if (config.provisionedOperatorAddress?.equals(address, true) != true) {
            _state.value = load(
                "Provisioned operator does not match the local terminal wallet",
                isError = true,
            )
            completeReadinessCallbacks(ready = false)
            return
        }
        val networkPolicy = try {
            KnownChainPolicy.requireProfile(config.chainId)
        } catch (error: Exception) {
            _state.value = load(error.message ?: "Unsupported terminal network", isError = true)
            completeReadinessCallbacks(ready = false)
            return
        }
        // Profile selection is owned by ChainConfig and may have changed from Checkout. Reflect
        // the new network/vault/token immediately while its readiness RPC is in flight.
        if (priority == ReadinessRpcPriority.INTERACTIVE) {
            _state.value = load(setupStatusOverride = TerminalSetupStatus.PROVISIONING).copy(
                configurationValidated = false,
                refreshingOperator = true,
                message = null,
                isError = false,
            )
        }
        refreshPriority = priority
        refreshJob = viewModelScope.launch {
            try {
                val result = readinessRpcScheduler.run(priority) {
                    val profile = KnownChainPolicy.requireProfile(config.chainId)
                    profile.requireValidCreate2Fixture()
                    require(config.factoryAddress.equals(profile.factory.value, true)) { "Factory pin mismatch" }
                    require(
                        config.receiverImplementationAddress.equals(
                            profile.receiverImplementation.value,
                            true,
                        ),
                    ) { "Receiver implementation pin mismatch" }
                    val token = config.paymentTokens.single()
                    val retryOnThrottle = retryReadinessOnThrottle(priority)
                    runInterruptible(Dispatchers.IO) {
                        val resolvedRpcUrl = rpcEndpointStore.resolve(
                            config.chainId,
                            config.rpcUrl,
                        )
                        val network = NetworkConfig(
                            config.chainId,
                            resolvedRpcUrl,
                            profile.factory,
                            profile.receiverImplementation,
                            EvmAddress.parse(config.vaultAddress),
                        )
                        val rpc = if (priority == ReadinessRpcPriority.AUTOMATIC) {
                            ReadOnlyRpcClient(
                                network,
                                connectTimeoutMillis = AUTOMATIC_RPC_CONNECT_TIMEOUT_MILLIS,
                                readTimeoutMillis = AUTOMATIC_RPC_READ_TIMEOUT_MILLIS,
                            )
                        } else {
                            ReadOnlyRpcClient(network)
                        }
                        rpc.validate(
                            token = EvmAddress.parse(token.address),
                            expectedDecimals = token.decimals,
                            expectedSymbol = token.symbol,
                            retryOnThrottle = retryOnThrottle,
                        )
                        val operator = EvmAddress.parse(address)
                        val readiness = rpc.operatorReadiness(
                            operator = operator,
                            retryOnThrottle = retryOnThrottle,
                        )
                        OperatorChainStatus(
                            balance = readiness.nativeBalance,
                            authorized = readiness.listedOperator || readiness.vaultOwner == operator,
                        )
                    }
                }
                if (result == null) {
                    // Checkout or another explicit action won priority, or this best-effort pass
                    // exhausted its bounded lease. Preserve the last proven ready state verbatim.
                    if (generation != refreshGeneration) return@launch
                    completeReadinessCallbacks(
                        ready = readinessResultWhenAutomaticRefreshDefers(
                            configurationStillValidated = validatedConfiguration == config,
                            setupStatus = _state.value.setupStatus,
                        ),
                    )
                    return@launch
                }
                if (result.authorized) {
                    walletStore.recordVerifiedSettlementTarget(
                        config.chainId,
                        config.vaultAddress,
                        requireNotNull(config.provisionedOperatorAddress),
                    )
                }
                val refreshedWallet = walletStore.snapshot()
                val status = when {
                    !result.authorized -> TerminalSetupStatus.AWAITING_AUTHORIZATION
                    result.balance < networkPolicy.minimumOperatorNativeReserve ->
                        TerminalSetupStatus.AWAITING_GAS
                    else -> TerminalSetupStatus.READY
                }
                if (generation != refreshGeneration) return@launch
                validatedConfiguration = config
                _state.value = load().copy(
                    setupStatus = status,
                    operatorBalanceWei = result.balance.toString(),
                    operatorAuthorized = result.authorized,
                    settlementTargetVerified = refreshedWallet.isVerifiedFor(
                        config.chainId,
                        config.vaultAddress,
                        requireNotNull(config.provisionedOperatorAddress),
                    ),
                    refreshingOperator = false,
                    message = when (status) {
                        TerminalSetupStatus.AWAITING_AUTHORIZATION ->
                            "Authorize this operator address from the merchant portal."
                        TerminalSetupStatus.AWAITING_GAS ->
                            awaitingGasReadinessMessage(networkPolicy)
                        TerminalSetupStatus.READY -> "Terminal is ready to create payments."
                        else -> null
                    },
                    isError = false,
                )
                completeReadinessCallbacks(ready = status == TerminalSetupStatus.READY)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (generation != refreshGeneration) return@launch
                _state.value = load(
                    terminalRpcFailureMessage(error, "Unable to validate terminal readiness"),
                    isError = true,
                )
                completeReadinessCallbacks(ready = false)
            } finally {
                if (generation == refreshGeneration) {
                    refreshJob = null
                    refreshPriority = null
                }
            }
        }
    }

    fun resetWalletConfirmed() {
        if (provisioningJob?.isActive == true) {
            _state.value = _state.value.copy(
                message = "Wait for provisioning to finish before resetting the wallet.",
                isError = true,
            )
            return
        }
        if (merchantReceiptProfileJob?.isActive == true ||
            autoSweepPreferenceJob?.isActive == true ||
            rpcEndpointMutationJob?.isActive == true
        ) {
            _state.value = _state.value.copy(
                message = "Wait for the current settings change to finish before resetting the wallet.",
                isError = true,
            )
            return
        }
        val authorizationEpoch = runCatching {
            requireNotNull(
                requireAdminAuthorizationEpoch(
                    adminPinStore.snapshot().configured,
                    adminSession,
                    "resetting the terminal wallet",
                ),
            )
        }.getOrElse { error ->
            _state.value = _state.value.copy(message = error.message, isError = true)
            return
        }
        val previousSetupStatus = _state.value.setupStatus
        invalidateReadinessRefresh()
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    runUserRpcMutation(rpcWorkCoordinator) {
                        val operatorAddress = checkNotNull(walletStore.snapshot().address) {
                            "No operator wallet exists"
                        }
                        resetCoordinator.reset(operatorAddress) { commit ->
                            adminSession.withAuthorization(authorizationEpoch, commit)
                        }
                    }
                }
                adminSession.lock()
                _state.value = load(
                    "Operator wallet, provisioning, and auto-sweep preference were removed. " +
                        "The local admin PIN remains configured.",
                )
            } catch (error: Exception) {
                _state.value = load(
                    error.message ?: "Unable to reset the terminal wallet",
                    isError = true,
                    setupStatusOverride = previousSetupStatus,
                )
            }
        }
    }

    private fun OperatorWalletSnapshot.isVerifiedFor(
        chainId: Long,
        vaultAddress: String,
        provisionedOperatorAddress: String,
    ): Boolean = activatedChainId == chainId &&
        activatedVaultAddress?.equals(vaultAddress, true) == true &&
        activatedOperatorAddress?.equals(provisionedOperatorAddress, true) == true &&
        address?.equals(provisionedOperatorAddress, true) == true

    private fun invalidateReadinessRefresh() {
        refreshGeneration += 1
        validatedConfiguration = null
        _state.value = _state.value.copy(configurationValidated = false)
        // A cancelled pass completes as not ready. Generic callbacks cannot release profile
        // selection, while the exact sequence/profile-owned callback can terminate its pending
        // state without making checkout ready.
        refreshCompletionCallbacks.cancel()
        refreshJob?.cancel()
        refreshJob = null
        refreshPriority = null
    }

    private fun completeReadinessCallbacks(ready: Boolean) {
        refreshCompletionCallbacks.complete(ready)
    }

    class Factory(
        private val chainConfig: ChainConfig,
        private val walletStore: OperatorWalletStore,
        private val adminPinStore: AdminPinStore,
        private val provisioner: TerminalProvisioner,
        private val resetCoordinator: TerminalResetCoordinator,
        private val lifecycleGate: TerminalLifecycleGate,
        private val rpcWorkCoordinator: RpcWorkCoordinator,
        private val rpcEndpointStore: RpcEndpointStore,
        private val rpcEndpointVerifier: RpcEndpointVerifier,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SettingsViewModel(
            chainConfig,
            walletStore,
            adminPinStore,
            provisioner,
            resetCoordinator,
            lifecycleGate,
            rpcWorkCoordinator,
            rpcEndpointStore,
            rpcEndpointVerifier,
        ) as T
    }

    private data class OperatorChainStatus(val balance: BigInteger, val authorized: Boolean)

    companion object {
        // Anchored validation is three waves; operator readiness is one more. Automatic refresh is
        // non-retrying, and its total socket budget stays below the coordinator's five-second lease.
        internal const val AUTOMATIC_RPC_WAVES = 4
        internal const val AUTOMATIC_RPC_CONNECT_TIMEOUT_MILLIS = 300
        internal const val AUTOMATIC_RPC_READ_TIMEOUT_MILLIS = 700
    }

}

internal fun paymentProfileRemovalSuccessMessage(
    removed: TerminalPaymentProfile,
    remainingProfileCount: Int,
): String {
    require(remainingProfileCount >= 0) { "Remaining profile count cannot be negative" }
    val prefix = "Removed ${removed.token.symbol} on ${removed.networkName}. "
    return if (remainingProfileCount == 0) {
        prefix + "No payment profiles remain. Checkout is unavailable; add a portal payment " +
            "profile in setup before accepting payments. Existing invoices and settlements are unchanged."
    } else {
        prefix + "$remainingProfileCount payment profile(s) remain. " +
            "Existing invoices and settlements are unchanged."
    }
}

internal fun chainConfigMigrationNoticeMessage(notice: ChainConfigMigrationNotice): String {
    val count = notice.adjustedConfirmationProfileIds.size
    val subject = if (count == 1) "1 existing payment profile" else "$count existing payment profiles"
    return "Confirmation requirements for $subject were increased to the applicable network " +
        "policy during this update. Terminal setup was preserved; review readiness before " +
        "accepting payments."
}

internal fun awaitingGasReadinessMessage(networkPolicy: KnownChainProfile): String =
    "Authorization confirmed. Fund the operator with at least " +
        "${networkPolicy.minimumOperatorNativeReserveDisplay()}."

internal fun networkConfirmationUpdateSuccessMessage(
    networkName: String,
    confirmationBlocks: Int,
): String {
    require(confirmationBlocks in 1..64)
    val confirmations = if (confirmationBlocks == 1) {
        "1 confirmation"
    } else {
        "$confirmationBlocks confirmations"
    }
    return "$networkName now requires $confirmations for all configured payment profiles on " +
        "this network. Existing invoices keep their original policy."
}

internal suspend fun removePaymentProfileExclusively(
    lifecycleGate: TerminalLifecycleGate,
    profileId: String,
    commitWithAuthorization: ((() -> Boolean) -> Boolean),
    removeProfile: (String) -> Boolean,
) = lifecycleGate.withExclusiveMutation {
    check(commitWithAuthorization { removeProfile(profileId) }) {
        "Unable to remove the payment profile"
    }
}

internal suspend fun updateNetworkConfirmationBlocksExclusively(
    lifecycleGate: TerminalLifecycleGate,
    chainId: Long,
    confirmationBlocks: Int,
    commitWithAuthorization: ((() -> Boolean) -> Boolean),
    update: (Long, Int) -> Boolean,
) = lifecycleGate.withExclusiveMutation {
    check(commitWithAuthorization { update(chainId, confirmationBlocks) }) {
        "Unable to update network confirmations"
    }
}

internal suspend fun updateMerchantReceiptProfileExclusively(
    lifecycleGate: TerminalLifecycleGate,
    commitWithAuthorization: ((() -> Boolean) -> Boolean),
    update: () -> Boolean,
) = lifecycleGate.withExclusiveMutation {
    check(commitWithAuthorization(update)) {
        "Unable to save merchant receipt details"
    }
}

internal suspend fun updateAutoSweepPreferenceExclusively(
    lifecycleGate: TerminalLifecycleGate,
    commitWithAuthorization: ((() -> Boolean) -> Boolean),
    update: () -> Boolean,
) = lifecycleGate.withExclusiveMutation {
    check(commitWithAuthorization(update)) {
        "Unable to save auto-sweep preference"
    }
}

internal fun rpcEndpointProvisioningPrerequisiteMessage(
    networkName: String,
    source: RpcEndpointSource,
): String? = when (source) {
    RpcEndpointSource.MISSING ->
        "Configure a dedicated $networkName RPC endpoint in Admin/setup before scanning the " +
            "merchant portal QR."
    RpcEndpointSource.UNAVAILABLE ->
        "Replace the unavailable $networkName RPC endpoint in Admin/setup before scanning the " +
            "merchant portal QR."
    RpcEndpointSource.ADMIN_OVERRIDE,
    RpcEndpointSource.BUILD_MANAGED,
    RpcEndpointSource.PUBLIC_FALLBACK -> null
}

internal suspend fun updateRpcEndpointExclusively(
    lifecycleGate: TerminalLifecycleGate,
    chainId: Long,
    rawRpcUrl: String,
    currentConfiguration: () -> TerminalConfigSnapshot,
    validateCandidate: (String) -> Unit,
    verify: suspend (Long, String, TerminalConfigSnapshot) -> Unit,
    commitWithAuthorization: ((() -> Boolean) -> Boolean),
    update: (Long, String) -> Boolean,
) = lifecycleGate.withExclusiveMutation {
    check(commitWithAuthorization { true }) {
        "Admin/setup authorization expired before RPC endpoint verification."
    }
    validateCandidate(rawRpcUrl)
    verify(chainId, rawRpcUrl, currentConfiguration())
    check(commitWithAuthorization { update(chainId, rawRpcUrl) }) {
        "Unable to save the RPC endpoint securely."
    }
}

internal suspend fun clearRpcEndpointExclusively(
    lifecycleGate: TerminalLifecycleGate,
    chainId: Long,
    commitWithAuthorization: ((() -> Boolean) -> Boolean),
    clear: (Long) -> Boolean,
) = lifecycleGate.withExclusiveMutation {
    check(commitWithAuthorization { clear(chainId) }) {
        "Unable to clear the RPC endpoint securely."
    }
}

internal fun rpcEndpointMutationFailureMessage(error: Exception): String = when {
    error is RpcRateLimit ->
        "The selected RPC provider is busy. Wait a moment and try again."
    error is RpcEndpointStorageException ->
        error.message ?: "Secure RPC endpoint storage is unavailable."
    error is IllegalArgumentException && error.message in SAFE_RPC_ENDPOINT_MESSAGES ->
        requireNotNull(error.message)
    else ->
        "Unable to verify or save the RPC endpoint. Check the URL and client credential, then try again."
}

private val SAFE_RPC_ENDPOINT_MESSAGES = setOf(
    "RPC URL is required.",
    "RPC URL is too long.",
    "RPC URL contains unsupported characters.",
    "Remove spaces before or after the RPC URL.",
    "RPC URL is invalid.",
    "RPC URL must use HTTPS.",
    "RPC URL must include a host.",
    "RPC URL must not use username/password credentials.",
    "RPC URL must not include a fragment.",
    "RPC URL port is invalid.",
    "RPC endpoint does not serve the selected Base network",
    "RPC endpoint did not return the known OPK factory",
    "RPC endpoint did not return the known receiver implementation",
    "RPC endpoint returned a different OPK receiver implementation",
    "Stored factory pin does not match the selected Base network",
    "Stored receiver implementation pin does not match the selected Base network",
    "RPC endpoint returned unexpected vault runtime bytecode",
    "RPC endpoint failed the configured payment-route validation",
)

internal fun operatorFundingPayload(
    config: TerminalConfigSnapshot,
    wallet: OperatorWalletSnapshot,
): String? {
    if (!config.provisioned || !config.hasCompleteProvisioning() ||
        wallet.availability != OperatorWalletAvailability.READY
    ) return null
    val walletAddress = wallet.address ?: return null
    if (config.provisionedOperatorAddress?.equals(walletAddress, true) != true) return null
    return "ethereum:${EvmAddress.parse(walletAddress).value}@${config.chainId}"
}
