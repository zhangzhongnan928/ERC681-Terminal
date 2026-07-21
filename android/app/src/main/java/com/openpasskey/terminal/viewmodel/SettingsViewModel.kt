package com.openpasskey.terminal.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.openpasskey.erc681.EvmAddress
import com.openpasskey.erc681.NetworkConfig
import com.openpasskey.erc681.ReadOnlyRpcClient
import com.openpasskey.terminal.admin.AdminPinStore
import com.openpasskey.terminal.admin.AdminPinVerification
import com.openpasskey.terminal.chain.ChainConfig
import com.openpasskey.terminal.chain.PaymentToken
import com.openpasskey.terminal.chain.TerminalConfigSnapshot
import com.openpasskey.terminal.chain.TerminalPaymentProfile
import com.openpasskey.terminal.chain.resolvedPaymentProfiles
import com.openpasskey.terminal.chain.hasCompleteProvisioning
import com.openpasskey.terminal.provisioning.KnownChainPolicy
import com.openpasskey.terminal.provisioning.TerminalProvisioner
import com.openpasskey.terminal.provisioning.TerminalProvisioningPayloadCodec
import com.openpasskey.terminal.settlement.SettlementChainClient
import com.openpasskey.terminal.settlement.Web3jSettlementChainClient
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

internal fun shouldRestartActiveReadinessRefresh(
    trigger: ReadinessRefreshTrigger,
    refreshActive: Boolean,
): Boolean = refreshActive && trigger == ReadinessRefreshTrigger.INVOICE_FAILURE

data class SettingsState(
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
    val isError: Boolean = false,
)

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
    private val clientFactory: (String) -> SettlementChainClient = ::Web3jSettlementChainClient,
) : ViewModel() {
    private val adminSession = AdminSessionGate()
    private var refreshJob: Job? = null
    private var refreshGeneration = 0L
    private var provisioningJob: Job? = null
    private var provisioningGeneration = 0L
    private var walletCreationAuthorizationEpoch: Long? = null
    private var validatedConfiguration: TerminalConfigSnapshot? = null
    private val refreshCompletionCallbacks = mutableListOf<(Boolean) -> Unit>()
    private val _state = MutableStateFlow(load())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    init {
        refreshOperatorStatus()
    }

    private fun load(
        message: String? = null,
        isError: Boolean = false,
        setupStatusOverride: TerminalSetupStatus? = null,
    ): SettingsState {
        val config = chainConfig.snapshot()
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
        if (cancelledProvisioning) {
            provisioningGeneration += 1
            provisioningJob?.cancel()
            provisioningJob = null
        }
        if (wasUnlocked || cancelledProvisioning) {
            _state.value = if (cancelledProvisioning) {
                load("Admin/setup controls locked; provisioning was cancelled.")
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
                val result = withContext(Dispatchers.IO) {
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
                    error.message ?: "Provisioning failed",
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

    fun removePaymentProfile(profileId: String) {
        if (provisioningJob?.isActive == true) {
            _state.value = _state.value.copy(
                message = "Wait for provisioning to finish before removing a payment profile.",
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
                _state.value = load(
                    "Removed ${removing.token.symbol} on ${removing.networkName}. " +
                        "$remaining payment profile(s) remain. Existing invoices and settlements are unchanged.",
                )
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

    fun refreshOperatorStatus() = refreshOperatorStatusInternal()

    fun refreshOperatorStatus(onComplete: (Boolean) -> Unit) =
        refreshOperatorStatusInternal(onComplete)

    fun refreshOperatorStatusAfterInvoiceFailure(onComplete: (Boolean) -> Unit) {
        if (shouldRestartActiveReadinessRefresh(
                ReadinessRefreshTrigger.INVOICE_FAILURE,
                refreshJob?.isActive == true,
            )
        ) {
            invalidateReadinessRefresh()
        }
        refreshOperatorStatusInternal(onComplete)
    }

    fun refreshOperatorStatusAfterProfileSelection(onComplete: (Boolean) -> Unit) {
        invalidateReadinessRefresh()
        refreshOperatorStatusInternal(onComplete)
    }

    private fun refreshOperatorStatusInternal(onComplete: ((Boolean) -> Unit)? = null) {
        onComplete?.let(refreshCompletionCallbacks::add)
        if (refreshJob?.isActive == true) return
        val generation = ++refreshGeneration
        val wallet = walletStore.snapshot()
        val address = wallet.address
        val config = chainConfig.snapshot()
        validatedConfiguration = null
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
        _state.value = load(setupStatusOverride = TerminalSetupStatus.PROVISIONING).copy(
            configurationValidated = false,
            refreshingOperator = true,
            message = null,
            isError = false,
        )
        refreshJob = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
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
                    val network = NetworkConfig(
                        config.chainId,
                        config.rpcUrl,
                        profile.factory,
                        profile.receiverImplementation,
                        EvmAddress.parse(config.vaultAddress),
                    )
                    ReadOnlyRpcClient(network).validate(
                        EvmAddress.parse(token.address),
                        token.decimals,
                        token.symbol,
                    )
                    clientFactory(config.rpcUrl).use { client ->
                        require(client.chainId() == config.chainId) { "RPC chain ID mismatch" }
                        val listed = client.isOperator(config.vaultAddress, address)
                        val ownerMatches = if (listed) false else {
                            client.owner(config.vaultAddress).equals(address, true)
                        }
                        val authorized = listed || ownerMatches
                        OperatorChainStatus(client.nativeBalance(address), authorized)
                    }
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
                            "Authorization confirmed. Fund the operator with at least " +
                                "${networkPolicy.minimumOperatorNativeReserve} wei " +
                                "(${networkPolicy.nativeCurrencySymbol})."
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
                _state.value = load(error.message ?: "Unable to validate terminal readiness", isError = true)
                completeReadinessCallbacks(ready = false)
            } finally {
                if (generation == refreshGeneration) refreshJob = null
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
                    val operatorAddress = checkNotNull(walletStore.snapshot().address) {
                        "No operator wallet exists"
                    }
                    resetCoordinator.reset(operatorAddress) { commit ->
                        adminSession.withAuthorization(authorizationEpoch, commit)
                    }
                }
                adminSession.lock()
                _state.value = load(
                    "Operator wallet and provisioning were removed. The local admin PIN remains configured.",
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
        completeReadinessCallbacks(ready = false)
        refreshJob?.cancel()
        refreshJob = null
    }

    private fun completeReadinessCallbacks(ready: Boolean) {
        if (refreshCompletionCallbacks.isEmpty()) return
        val callbacks = refreshCompletionCallbacks.toList()
        refreshCompletionCallbacks.clear()
        callbacks.forEach { callback -> callback(ready) }
    }

    class Factory(
        private val chainConfig: ChainConfig,
        private val walletStore: OperatorWalletStore,
        private val adminPinStore: AdminPinStore,
        private val provisioner: TerminalProvisioner,
        private val resetCoordinator: TerminalResetCoordinator,
        private val lifecycleGate: TerminalLifecycleGate,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SettingsViewModel(
            chainConfig,
            walletStore,
            adminPinStore,
            provisioner,
            resetCoordinator,
            lifecycleGate,
        ) as T
    }

    private data class OperatorChainStatus(val balance: BigInteger, val authorized: Boolean)

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
