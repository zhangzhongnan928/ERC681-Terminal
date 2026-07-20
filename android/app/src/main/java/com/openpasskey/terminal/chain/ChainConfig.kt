package com.openpasskey.terminal.chain

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class PaymentToken(
    val address: String,
    val symbol: String,
    val decimals: Int
)

data class TerminalConfigSnapshot(
    val networkName: String,
    val rpcUrl: String,
    val chainId: Long,
    val factoryAddress: String,
    val receiverImplementationAddress: String,
    val vaultAddress: String,
    val confirmationBlocks: Int,
    val paymentTokens: List<PaymentToken>
)

/**
 * Local, non-secret merchant configuration. No wallet credentials or signing material are stored.
 */
class ChainConfig(context: Context) {
    companion object {
        // Preserve the original non-secret chain/token preferences on app upgrade.
        private const val PREFS_NAME = "opk_chain_config"
        private const val KEY_NETWORK_NAME = "network_name"
        private const val KEY_RPC_URL = "rpc_url"
        private const val KEY_CHAIN_ID = "chain_id"
        private const val KEY_FACTORY_ADDRESS = "factory_address"
        private const val KEY_RECEIVER_IMPLEMENTATION = "receiver_implementation"
        private const val KEY_VAULT_ADDRESS = "vault_address"
        private const val KEY_CONFIRMATION_BLOCKS = "confirmation_blocks"
        private const val KEY_PAYMENT_TOKENS = "payment_tokens"

        const val DEFAULT_NETWORK_NAME = "Base Sepolia"
        const val DEFAULT_RPC_URL = "https://sepolia.base.org"
        const val DEFAULT_CHAIN_ID = 84532L
        const val DEFAULT_FACTORY_ADDRESS = "0x062e3b5d3107e4d1b8dDA314E16b9F8cA6EB63D5"
        const val DEFAULT_RECEIVER_IMPLEMENTATION = "0xDAa292B1bf533737C5cE5d27F220273971Db3Bdc"
        const val DEFAULT_VAULT_ADDRESS = "0x1ed67E540E6AB92dC3537A7bba3BcAb6FdD69Da1"
        const val DEFAULT_CONFIRMATION_BLOCKS = 2

        val DEFAULT_PAYMENT_TOKENS = listOf(
            PaymentToken("0x7ffba642bc902880a737cb1c18a4e9540879e211", "AUD", 18),
            PaymentToken("0xc6813d7bf21c9c6747ef231da80bb8625d5607a3", "OPK2", 18)
        )
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    var networkName: String
        get() = prefs.getString(KEY_NETWORK_NAME, DEFAULT_NETWORK_NAME) ?: DEFAULT_NETWORK_NAME
        set(value) = prefs.edit().putString(KEY_NETWORK_NAME, value.trim()).apply()

    var rpcUrl: String
        get() = prefs.getString(KEY_RPC_URL, DEFAULT_RPC_URL) ?: DEFAULT_RPC_URL
        set(value) = prefs.edit().putString(KEY_RPC_URL, value.trim()).apply()

    var chainId: Long
        get() = prefs.getLong(KEY_CHAIN_ID, DEFAULT_CHAIN_ID)
        set(value) = prefs.edit().putLong(KEY_CHAIN_ID, value).apply()

    var factoryAddress: String
        get() = prefs.getString(KEY_FACTORY_ADDRESS, DEFAULT_FACTORY_ADDRESS) ?: DEFAULT_FACTORY_ADDRESS
        set(value) = prefs.edit().putString(KEY_FACTORY_ADDRESS, value.trim()).apply()

    var receiverImplementationAddress: String
        get() = prefs.getString(KEY_RECEIVER_IMPLEMENTATION, DEFAULT_RECEIVER_IMPLEMENTATION)
            ?: DEFAULT_RECEIVER_IMPLEMENTATION
        set(value) = prefs.edit().putString(KEY_RECEIVER_IMPLEMENTATION, value.trim()).apply()

    var vaultAddress: String
        get() = prefs.getString(KEY_VAULT_ADDRESS, DEFAULT_VAULT_ADDRESS) ?: DEFAULT_VAULT_ADDRESS
        set(value) = prefs.edit().putString(KEY_VAULT_ADDRESS, value.trim()).apply()

    var confirmationBlocks: Int
        get() = prefs.getInt(KEY_CONFIRMATION_BLOCKS, DEFAULT_CONFIRMATION_BLOCKS)
        set(value) = prefs.edit().putInt(KEY_CONFIRMATION_BLOCKS, value.coerceIn(1, 64)).apply()

    var paymentTokens: List<PaymentToken>
        get() {
            val json = prefs.getString(KEY_PAYMENT_TOKENS, null) ?: return DEFAULT_PAYMENT_TOKENS
            val type = object : TypeToken<List<PaymentToken>>() {}.type
            return runCatching { gson.fromJson<List<PaymentToken>>(json, type) }
                .getOrDefault(emptyList())
        }
        set(value) {
            prefs.edit().putString(KEY_PAYMENT_TOKENS, gson.toJson(value)).apply()
        }

    fun addPaymentToken(token: PaymentToken) {
        paymentTokens = paymentTokens
            .filterNot { it.address.equals(token.address, ignoreCase = true) } + token
    }

    fun removePaymentToken(address: String) {
        paymentTokens = paymentTokens.filterNot { it.address.equals(address, ignoreCase = true) }
    }

    fun snapshot(): TerminalConfigSnapshot = TerminalConfigSnapshot(
        networkName = networkName,
        rpcUrl = rpcUrl,
        chainId = chainId,
        factoryAddress = factoryAddress,
        receiverImplementationAddress = receiverImplementationAddress,
        vaultAddress = vaultAddress,
        confirmationBlocks = confirmationBlocks,
        paymentTokens = paymentTokens
    )

    fun isConfigured(): Boolean =
        networkName.isNotBlank() && rpcUrl.isNotBlank() && chainId > 0 &&
            factoryAddress.isNotBlank() && receiverImplementationAddress.isNotBlank() &&
            vaultAddress.isNotBlank() && paymentTokens.isNotEmpty()
}
