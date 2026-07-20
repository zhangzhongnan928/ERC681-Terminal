package com.openpasskey.terminal.chain

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.openpasskey.erc681.EvmAddress

data class PaymentToken(
    val address: String,
    val symbol: String,
    val decimals: Int,
)

data class TerminalConfigSnapshot(
    val networkName: String,
    val rpcUrl: String,
    val chainId: Long,
    val factoryAddress: String,
    val receiverImplementationAddress: String,
    val vaultAddress: String,
    val confirmationBlocks: Int,
    val paymentTokens: List<PaymentToken>,
    val protocolVersion: String,
    val provisionedOperatorAddress: String?,
    val provisioned: Boolean,
)

/**
 * Non-secret terminal configuration. A provisioned candidate is stored as one JSON value by one
 * synchronous SharedPreferences editor commit so a failed write cannot expose a partial config.
 */
class ChainConfig(context: Context) {
    companion object {
        private const val PREFS_NAME = "opk_chain_config"
        private const val KEY_CONFIG_JSON_V2 = "provisioned_config_v2"
        private const val KEY_PROVISIONED_V2 = "is_provisioned_v2"

        // Legacy per-field keys are read only to preserve the merchant's confirmation preference.
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
        const val DEFAULT_CONFIRMATION_BLOCKS = 2

        private val LEGACY_MUTABLE_KEYS = listOf(
            KEY_NETWORK_NAME,
            KEY_RPC_URL,
            KEY_CHAIN_ID,
            KEY_FACTORY_ADDRESS,
            KEY_RECEIVER_IMPLEMENTATION,
            KEY_VAULT_ADDRESS,
            KEY_CONFIRMATION_BLOCKS,
            KEY_PAYMENT_TOKENS,
        )
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    val networkName: String get() = snapshot().networkName
    val rpcUrl: String get() = snapshot().rpcUrl
    val chainId: Long get() = snapshot().chainId
    val factoryAddress: String get() = snapshot().factoryAddress
    val receiverImplementationAddress: String get() = snapshot().receiverImplementationAddress
    val vaultAddress: String get() = snapshot().vaultAddress
    val confirmationBlocks: Int get() = snapshot().confirmationBlocks
    val paymentTokens: List<PaymentToken> get() = snapshot().paymentTokens

    @Synchronized
    fun snapshot(): TerminalConfigSnapshot {
        if (prefs.getBoolean(KEY_PROVISIONED_V2, false)) {
            val json = prefs.getString(KEY_CONFIG_JSON_V2, null)
            val stored = json?.let {
                runCatching { gson.fromJson(it, TerminalConfigSnapshot::class.java) }.getOrNull()
            }
            if (stored != null && stored.provisioned && stored.hasCompleteProvisioning()) return stored
        }
        val legacyChainId = prefs.getLong(KEY_CHAIN_ID, DEFAULT_CHAIN_ID)
        val legacyRpcUrl = prefs.getString(KEY_RPC_URL, DEFAULT_RPC_URL) ?: DEFAULT_RPC_URL
        return TerminalConfigSnapshot(
            networkName = DEFAULT_NETWORK_NAME,
            rpcUrl = if (legacyChainId == DEFAULT_CHAIN_ID) legacyRpcUrl else DEFAULT_RPC_URL,
            chainId = DEFAULT_CHAIN_ID,
            factoryAddress = DEFAULT_FACTORY_ADDRESS.lowercase(),
            receiverImplementationAddress = DEFAULT_RECEIVER_IMPLEMENTATION.lowercase(),
            vaultAddress = "",
            confirmationBlocks = prefs.getInt(
                KEY_CONFIRMATION_BLOCKS,
                DEFAULT_CONFIRMATION_BLOCKS,
            ).coerceIn(1, 64),
            paymentTokens = emptyList(),
            protocolVersion = "",
            provisionedOperatorAddress = null,
            provisioned = false,
        )
    }

    /** The sole production configuration write path, with snapshot compare-and-set semantics. */
    @Synchronized
    fun compareAndReplaceProvisioned(
        expected: TerminalConfigSnapshot,
        candidate: TerminalConfigSnapshot,
    ): Boolean {
        if (snapshot() != expected) return false
        require(candidate.provisioned) { "Replacement configuration must be provisioned" }
        require(candidate.hasCompleteProvisioning()) { "Replacement configuration is incomplete" }
        val canonical = candidate.copy(
            factoryAddress = EvmAddress.parse(candidate.factoryAddress).value,
            receiverImplementationAddress = EvmAddress.parse(candidate.receiverImplementationAddress).value,
            vaultAddress = EvmAddress.parse(candidate.vaultAddress).value,
            provisionedOperatorAddress = EvmAddress.parse(
                requireNotNull(candidate.provisionedOperatorAddress),
            ).value,
            paymentTokens = candidate.paymentTokens.map { token ->
                token.copy(address = EvmAddress.parse(token.address).value)
            },
        )
        val editor = prefs.edit()
            .putString(KEY_CONFIG_JSON_V2, gson.toJson(canonical))
            .putBoolean(KEY_PROVISIONED_V2, true)
        LEGACY_MUTABLE_KEYS.forEach(editor::remove)
        return editor.commit()
    }

    /** Admin-only reset of configuration; invoice history is intentionally untouched. */
    @Synchronized
    fun clearProvisioning(): Boolean = prefs.edit()
        .remove(KEY_CONFIG_JSON_V2)
        .remove(KEY_PROVISIONED_V2)
        .commit()

    fun isConfigured(): Boolean = snapshot().let { it.provisioned && it.hasCompleteProvisioning() }
}

internal fun TerminalConfigSnapshot.hasCompleteProvisioning(): Boolean = runCatching {
        require(provisioned)
        require(networkName.isNotBlank())
        require(rpcUrl.isNotBlank())
        require(chainId > 0)
        require(!EvmAddress.parse(factoryAddress).isZero)
        require(!EvmAddress.parse(receiverImplementationAddress).isZero)
        require(!EvmAddress.parse(vaultAddress).isZero)
        require(confirmationBlocks in 1..64)
        require(protocolVersion.isNotBlank())
        require(!EvmAddress.parse(requireNotNull(provisionedOperatorAddress)).isZero)
        require(paymentTokens.size == 1)
        val token = paymentTokens.single()
        require(!EvmAddress.parse(token.address).isZero)
        require(token.symbol.isNotBlank())
        require(token.decimals in 0..255)
    }.isSuccess
