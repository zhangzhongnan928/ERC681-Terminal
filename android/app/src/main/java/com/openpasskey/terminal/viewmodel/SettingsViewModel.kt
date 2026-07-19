package com.openpasskey.terminal.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.openpasskey.erc681.EvmAddress
import com.openpasskey.terminal.chain.ChainConfig
import com.openpasskey.terminal.chain.PaymentToken
import com.openpasskey.terminal.settlement.SettlementChainClient
import com.openpasskey.terminal.settlement.Web3jSettlementChainClient
import com.openpasskey.terminal.wallet.OperatorWalletAvailability
import com.openpasskey.terminal.wallet.OperatorWalletSnapshot
import com.openpasskey.terminal.wallet.OperatorWalletStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigInteger
import java.net.URI

data class SettingsState(
    val networkName: String = "",
    val rpcUrl: String = "",
    val chainId: String = "",
    val operatorNetworkChainId: Long = 0,
    val factoryAddress: String = "",
    val receiverImplementationAddress: String = "",
    val vaultAddress: String = "",
    val confirmationBlocks: String = "",
    val terminalIdentifier: String = "",
    val paymentTokens: List<PaymentToken> = emptyList(),
    val operatorWalletAvailability: OperatorWalletAvailability = OperatorWalletAvailability.NOT_CREATED,
    val operatorWalletAddress: String? = null,
    val operatorBalanceWei: String? = null,
    val operatorAuthorized: Boolean? = null,
    val operatorActivated: Boolean = false,
    val walletHardwareBacked: Boolean = false,
    val walletStrongBoxBacked: Boolean = false,
    val walletDeviceAuthenticationRequired: Boolean = false,
    val refreshingOperator: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false
)

class SettingsViewModel(
    private val chainConfig: ChainConfig,
    private val walletStore: OperatorWalletStore,
    private val clientFactory: (String) -> SettlementChainClient = ::Web3jSettlementChainClient
) : ViewModel() {
    private val _state = MutableStateFlow(load())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    init {
        refreshOperatorStatus()
    }

    private fun load(message: String? = null, isError: Boolean = false): SettingsState {
        val wallet = walletStore.snapshot()
        return SettingsState(
        networkName = chainConfig.networkName,
        rpcUrl = chainConfig.rpcUrl,
        chainId = chainConfig.chainId.toString(),
        operatorNetworkChainId = chainConfig.chainId,
        factoryAddress = chainConfig.factoryAddress,
        receiverImplementationAddress = chainConfig.receiverImplementationAddress,
        vaultAddress = chainConfig.vaultAddress,
        confirmationBlocks = chainConfig.confirmationBlocks.toString(),
        terminalIdentifier = chainConfig.terminalIdentifier,
        paymentTokens = chainConfig.paymentTokens,
        operatorWalletAvailability = wallet.availability,
        operatorWalletAddress = wallet.address,
        operatorActivated = wallet.isActivatedFor(chainConfig.chainId, chainConfig.vaultAddress),
        walletHardwareBacked = wallet.hardwareBacked,
        walletStrongBoxBacked = wallet.strongBoxBacked,
        walletDeviceAuthenticationRequired = wallet.deviceAuthenticationRequired,
        message = message ?: wallet.error,
        isError = isError || wallet.availability == OperatorWalletAvailability.UNAVAILABLE
        )
    }

    private fun mutate(block: (SettingsState) -> SettingsState) {
        _state.value = block(_state.value).copy(message = null, isError = false)
    }

    fun updateNetworkName(value: String) = mutate { it.copy(networkName = value) }
    fun updateRpcUrl(value: String) = mutate { it.copy(rpcUrl = value) }
    fun updateChainId(value: String) = mutate { it.copy(chainId = value) }
    fun updateFactoryAddress(value: String) = mutate { it.copy(factoryAddress = value) }
    fun updateReceiverImplementationAddress(value: String) =
        mutate { it.copy(receiverImplementationAddress = value) }
    fun updateVaultAddress(value: String) = mutate { it.copy(vaultAddress = value) }
    fun updateConfirmationBlocks(value: String) = mutate { it.copy(confirmationBlocks = value) }

    fun saveSettings() {
        val current = _state.value
        val validationError = validate(current)
        if (validationError != null) {
            _state.value = current.copy(message = validationError, isError = true)
            return
        }
        chainConfig.networkName = current.networkName
        chainConfig.rpcUrl = current.rpcUrl
        chainConfig.chainId = current.chainId.toLong()
        val factoryAddress = EvmAddress.parse(current.factoryAddress).value
        val receiverImplementationAddress = EvmAddress.parse(current.receiverImplementationAddress).value
        val vaultAddress = EvmAddress.parse(current.vaultAddress).value
        chainConfig.factoryAddress = factoryAddress
        chainConfig.receiverImplementationAddress = receiverImplementationAddress
        chainConfig.vaultAddress = vaultAddress
        chainConfig.confirmationBlocks = current.confirmationBlocks.toInt()
        _state.value = load().copy(message = "Settings saved. They will be checked on-chain before a QR is shown.")
        refreshOperatorStatus()
    }

    fun addPaymentToken(address: String, symbol: String, decimals: Int) {
        val parsedAddress = runCatching { EvmAddress.parse(address) }.getOrElse {
            _state.value = _state.value.copy(message = "Invalid token address.", isError = true)
            return
        }
        if (parsedAddress.isZero) {
            _state.value = _state.value.copy(message = "Token address must not be zero.", isError = true)
            return
        }
        if (symbol.isBlank() || decimals !in 0..255) {
            _state.value = _state.value.copy(message = "Token symbol and decimals are invalid.", isError = true)
            return
        }
        chainConfig.addPaymentToken(
            PaymentToken(parsedAddress.value, symbol.trim().uppercase(), decimals)
        )
        _state.value = load().copy(message = "Token added. Whitelist status is checked before payment.")
    }

    fun removePaymentToken(address: String) {
        chainConfig.removePaymentToken(address)
        _state.value = load().copy(message = "Token removed.")
    }

    /** Returns true when the UI should immediately present the OS authentication prompt. */
    fun prepareWalletCreation(): Boolean = try {
        walletStore.prepareWalletCreation()
        _state.value = _state.value.copy(
            message = "Authenticate to encrypt the new operator key.",
            isError = false
        )
        true
    } catch (error: Exception) {
        _state.value = _state.value.copy(message = error.message, isError = true)
        false
    }

    /** Called only after a successful biometric/device-credential prompt. */
    fun createWalletAuthenticated() {
        viewModelScope.launch {
            try {
                val wallet = withContext(Dispatchers.IO) { walletStore.createWallet() }
                _state.value = load(
                    "Operator wallet ${wallet.address} created. Fund it with native gas, then authorize it on the vault."
                )
                refreshOperatorStatus()
            } catch (error: Exception) {
                _state.value = load(error.message ?: "Unable to create operator wallet", isError = true)
            }
        }
    }

    fun authenticationFailed(message: String) {
        _state.value = _state.value.copy(message = "Authentication failed: $message", isError = true)
    }

    fun refreshOperatorStatus() {
        val wallet = walletStore.snapshot()
        val address = wallet.address
        if (wallet.availability != OperatorWalletAvailability.READY || address == null) {
            _state.value = load()
            return
        }
        val snapshot = chainConfig.snapshot()
        _state.value = _state.value.copy(refreshingOperator = true, message = null, isError = false)
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    clientFactory(snapshot.rpcUrl).use { client ->
                        require(client.chainId() == snapshot.chainId) { "RPC chain ID mismatch" }
                        val owner = runCatching { client.owner(snapshot.vaultAddress) }
                        val listed = runCatching {
                            client.isOperator(snapshot.vaultAddress, address)
                        }
                        val authorized = owner.getOrNull()?.equals(address, true) == true ||
                            listed.getOrNull() == true
                        Triple(client.nativeBalance(address), authorized, owner.exceptionOrNull() ?: listed.exceptionOrNull())
                    }
                }
                if (result.second) {
                    walletStore.activateInvoiceNamespace(snapshot.chainId, snapshot.vaultAddress)
                }
                val refreshedWallet = walletStore.snapshot()
                _state.value = _state.value.copy(
                    operatorWalletAvailability = refreshedWallet.availability,
                    operatorWalletAddress = refreshedWallet.address,
                    operatorActivated = refreshedWallet.isActivatedFor(
                        snapshot.chainId,
                        snapshot.vaultAddress
                    ),
                    walletHardwareBacked = refreshedWallet.hardwareBacked,
                    walletStrongBoxBacked = refreshedWallet.strongBoxBacked,
                    walletDeviceAuthenticationRequired =
                        refreshedWallet.deviceAuthenticationRequired,
                    operatorBalanceWei = result.first.toString(),
                    operatorAuthorized = result.second,
                    refreshingOperator = false,
                    message = if (result.second) null else result.third?.message,
                    isError = false
                )
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    refreshingOperator = false,
                    message = error.message ?: "Unable to inspect operator wallet",
                    isError = true
                )
            }
        }
    }

    private fun OperatorWalletSnapshot.isActivatedFor(chainId: Long, vaultAddress: String): Boolean =
        activatedChainId == chainId && activatedVaultAddress?.equals(vaultAddress, true) == true

    private fun validate(state: SettingsState): String? {
        if (state.networkName.isBlank()) return "Network name is required."
        val uri = runCatching { URI(state.rpcUrl.trim()) }.getOrNull()
        val host = uri?.host?.lowercase()
        val loopback = host in setOf("localhost", "127.0.0.1", "::1", "[::1]")
        val validTransport = uri?.scheme?.lowercase() == "https" ||
            (uri?.scheme?.lowercase() == "http" && loopback)
        if (uri == null || host.isNullOrBlank() || !validTransport ||
            uri.userInfo != null || uri.fragment != null
        ) {
            return "RPC URL must use HTTPS (or loopback HTTP) without credentials or a fragment."
        }
        if (state.chainId.toLongOrNull()?.let { it > 0 } != true) return "Chain ID must be positive."
        if (state.confirmationBlocks.toIntOrNull()?.let { it in 1..64 } != true) {
            return "Confirmation blocks must be between 1 and 64."
        }
        val addresses = listOf(
            "factory" to state.factoryAddress,
            "receiver implementation" to state.receiverImplementationAddress,
            "vault" to state.vaultAddress
        )
        addresses.forEach { (label, value) ->
            val address = runCatching { EvmAddress.parse(value) }.getOrNull()
                ?: return "Invalid $label address."
            if (address.isZero) return "$label address must not be zero."
        }
        return null
    }

    class Factory(
        private val chainConfig: ChainConfig,
        private val walletStore: OperatorWalletStore
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(chainConfig, walletStore) as T
    }
}
