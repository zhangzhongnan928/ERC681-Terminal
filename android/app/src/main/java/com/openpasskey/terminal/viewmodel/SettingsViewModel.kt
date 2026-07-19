package com.openpasskey.terminal.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.openpasskey.erc681.EvmAddress
import com.openpasskey.terminal.chain.ChainConfig
import com.openpasskey.terminal.chain.PaymentToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URI

data class SettingsState(
    val networkName: String = "",
    val rpcUrl: String = "",
    val chainId: String = "",
    val factoryAddress: String = "",
    val receiverImplementationAddress: String = "",
    val vaultAddress: String = "",
    val confirmationBlocks: String = "",
    val terminalIdentifier: String = "",
    val paymentTokens: List<PaymentToken> = emptyList(),
    val message: String? = null,
    val isError: Boolean = false
)

class SettingsViewModel(private val chainConfig: ChainConfig) : ViewModel() {
    private val _state = MutableStateFlow(load())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    private fun load() = SettingsState(
        networkName = chainConfig.networkName,
        rpcUrl = chainConfig.rpcUrl,
        chainId = chainConfig.chainId.toString(),
        factoryAddress = chainConfig.factoryAddress,
        receiverImplementationAddress = chainConfig.receiverImplementationAddress,
        vaultAddress = chainConfig.vaultAddress,
        confirmationBlocks = chainConfig.confirmationBlocks.toString(),
        terminalIdentifier = chainConfig.terminalIdentifier,
        paymentTokens = chainConfig.paymentTokens,
    )

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

    class Factory(private val chainConfig: ChainConfig) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(chainConfig) as T
    }
}
