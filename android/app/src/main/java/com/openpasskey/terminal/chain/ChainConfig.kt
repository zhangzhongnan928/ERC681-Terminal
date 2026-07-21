package com.openpasskey.terminal.chain

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.openpasskey.erc681.EvmAddress
import com.openpasskey.erc681.NetworkConfig
import com.openpasskey.erc681.PaymentProfile
import com.openpasskey.terminal.provisioning.KnownChainPolicy

data class PaymentToken(
    val address: String,
    val symbol: String,
    val decimals: Int,
)

/** One selectable currency destination. An invoice always snapshots exactly one of these. */
data class TerminalPaymentProfile(
    val networkName: String,
    val rpcUrl: String,
    val chainId: Long,
    val factoryAddress: String,
    val receiverImplementationAddress: String,
    val vaultAddress: String,
    val confirmationBlocks: Int,
    val token: PaymentToken,
    val protocolVersion: String,
) {
    val id: String
        get() = PaymentProfile.id(
            chainId,
            EvmAddress.parse(vaultAddress),
            EvmAddress.parse(token.address),
        )
}

/**
 * The flattened fields remain the selected profile for source/storage compatibility. The catalog
 * is authoritative when present, so an existing v2 single-profile install migrates without
 * changing historical invoice snapshots.
 */
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
    val paymentProfiles: List<TerminalPaymentProfile> = emptyList(),
    val selectedProfileId: String? = null,
)

/** Observable, non-secret metadata for a safety-preserving storage migration. */
data class ChainConfigMigrationNotice(
    val adjustedConfirmationProfileIds: Set<String>,
)

/** Non-secret, atomically stored catalog of chain/vault/token payment profiles. */
class ChainConfig(context: Context) {
    companion object {
        private const val PREFS_NAME = "opk_chain_config"
        private const val KEY_CONFIG_JSON_V3 = "provisioned_config_v3"
        private const val KEY_PROVISIONED_V3 = "is_provisioned_v3"
        private const val KEY_CONFIG_JSON_V2 = "provisioned_config_v2"
        private const val KEY_PROVISIONED_V2 = "is_provisioned_v2"
        private const val KEY_FINALITY_MIGRATION_PROFILE_IDS_V3 =
            "finality_migration_profile_ids_v3"

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
        const val MAX_PAYMENT_PROFILES = 32

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
    val paymentProfiles: List<TerminalPaymentProfile> get() = snapshot().resolvedPaymentProfiles()

    @Synchronized
    fun snapshot(): TerminalConfigSnapshot {
        // Once v3 exists it is authoritative. A malformed v3 value must fail closed rather than
        // falling back to stale v2 data that may describe a different checkout route.
        if (prefs.getBoolean(KEY_PROVISIONED_V3, false)) {
            return storedSnapshot(KEY_CONFIG_JSON_V3) ?: unprovisionedSnapshot()
        }
        if (prefs.getBoolean(KEY_PROVISIONED_V2, false)) {
            return migrateV2Snapshot() ?: unprovisionedSnapshot()
        }
        return unprovisionedSnapshot()
    }

    private fun unprovisionedSnapshot(): TerminalConfigSnapshot {
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

    /** Remains pending until the presentation layer acknowledges it after informing the merchant. */
    fun pendingMigrationNotice(): ChainConfigMigrationNotice? {
        val profileIds = prefs.getStringSet(KEY_FINALITY_MIGRATION_PROFILE_IDS_V3, null)
            ?.toSet()
            .orEmpty()
        return profileIds.takeIf { it.isNotEmpty() }
            ?.let(::ChainConfigMigrationNotice)
    }

    fun acknowledgeMigrationNotice(): Boolean = prefs.edit()
        .remove(KEY_FINALITY_MIGRATION_PROFILE_IDS_V3)
        .commit()

    /** The sole production catalog write path, with snapshot compare-and-set semantics. */
    @Synchronized
    fun compareAndReplaceProvisioned(
        expected: TerminalConfigSnapshot,
        candidate: TerminalConfigSnapshot,
    ): Boolean {
        if (snapshot() != expected) return false
        require(candidate.provisioned) { "Replacement configuration must be provisioned" }
        require(candidate.hasCompleteProvisioning()) { "Replacement configuration is incomplete" }
        return persist(candidate.canonicalCatalog())
    }

    /** Atomically changes the selected checkout profile without editing any profile contents. */
    @Synchronized
    fun selectProfile(profileId: String): Boolean {
        val current = snapshot()
        require(current.provisioned) { "Provision this terminal before selecting a payment profile" }
        val selected = current.selectingProfile(profileId).canonicalCatalog()
        return persist(selected)
    }

    /** Removes one future-checkout choice. Durable invoice and settlement snapshots are separate. */
    @Synchronized
    fun removeProfile(profileId: String): Boolean {
        val current = snapshot()
        val updated = current.removingPaymentProfile(profileId) ?: return clearProvisioning()
        val retainedNoticeProfileIds = pendingMigrationNotice()
            ?.adjustedConfirmationProfileIds
            ?.intersect(updated.resolvedPaymentProfiles().mapTo(mutableSetOf()) { it.id })
        return persist(
            updated.canonicalCatalog(),
            adjustedConfirmationProfileIds = retainedNoticeProfileIds,
        )
    }

    /** Admin-only reset of configuration; invoice history is intentionally untouched. */
    @Synchronized
    fun clearProvisioning(): Boolean = prefs.edit()
        .remove(KEY_CONFIG_JSON_V3)
        .remove(KEY_PROVISIONED_V3)
        .remove(KEY_CONFIG_JSON_V2)
        .remove(KEY_PROVISIONED_V2)
        .remove(KEY_FINALITY_MIGRATION_PROFILE_IDS_V3)
        .commit()

    fun isConfigured(): Boolean = snapshot().let { it.provisioned && it.hasCompleteProvisioning() }

    private fun storedSnapshot(jsonKey: String): TerminalConfigSnapshot? {
        val json = prefs.getString(jsonKey, null) ?: return null
        val stored = decodeSnapshot(json) ?: return null
        // v3 is catalog-native. Never reinterpret an empty/corrupt catalog as its flattened
        // downgrade facade, which exists only so older app versions can inspect the selection.
        if (stored.paymentProfiles.isEmpty() || stored.selectedProfileId == null) return null
        if (!stored.provisioned || !stored.hasCompleteProvisioning()) return null
        return stored.catalogNormalized()
    }

    /**
     * v2 accepted one confirmation on this chain. Raise only historically valid positive values
     * to the current compiled floor, then immediately persist the canonical v3 catalog. Invalid
     * addresses, duplicate routes, unknown chains, and other malformed data still fail closed.
     */
    private fun migrateV2Snapshot(): TerminalConfigSnapshot? {
        val json = prefs.getString(KEY_CONFIG_JSON_V2, null) ?: return null
        val stored = decodeSnapshot(json) ?: return null
        if (!stored.provisioned) return null
        val migration = stored.raisingLegacyConfirmationFloors() ?: return null
        if (!migration.snapshot.hasCompleteProvisioning()) return null
        val canonical = migration.snapshot.canonicalCatalog()
        if (!persist(canonical, migration.adjustedProfileIds)) return null
        return canonical
    }

    private fun decodeSnapshot(json: String): TerminalConfigSnapshot? = runCatching {
        val tree = JsonParser.parseString(json).asJsonObject
        val stored = gson.fromJson(tree, TerminalConfigSnapshot::class.java)
        val profileType = object : TypeToken<List<TerminalPaymentProfile>>() {}.type
        val profiles: List<TerminalPaymentProfile> = if (tree.has("paymentProfiles")) {
            gson.fromJson(tree.get("paymentProfiles"), profileType)
        } else {
            emptyList()
        }
        val selected = tree.get("selectedProfileId")
            ?.takeUnless { it.isJsonNull }
            ?.asString
        stored.copy(paymentProfiles = profiles, selectedProfileId = selected)
    }.getOrNull()

    private fun persist(
        snapshot: TerminalConfigSnapshot,
        adjustedConfirmationProfileIds: Set<String>? = null,
    ): Boolean {
        val editor = prefs.edit()
            .putString(KEY_CONFIG_JSON_V3, gson.toJson(snapshot))
            .putBoolean(KEY_PROVISIONED_V3, true)
            .remove(KEY_CONFIG_JSON_V2)
            .remove(KEY_PROVISIONED_V2)
        if (adjustedConfirmationProfileIds != null) {
            if (adjustedConfirmationProfileIds.isEmpty()) {
                editor.remove(KEY_FINALITY_MIGRATION_PROFILE_IDS_V3)
            } else {
                editor.putStringSet(
                    KEY_FINALITY_MIGRATION_PROFILE_IDS_V3,
                    adjustedConfirmationProfileIds,
                )
            }
        }
        LEGACY_MUTABLE_KEYS.forEach(editor::remove)
        return editor.commit()
    }
}

fun TerminalConfigSnapshot.resolvedPaymentProfiles(): List<TerminalPaymentProfile> {
    if (paymentProfiles.isNotEmpty()) return paymentProfiles
    if (!provisioned || paymentTokens.size != 1) return emptyList()
    return listOf(
        TerminalPaymentProfile(
            networkName,
            rpcUrl,
            chainId,
            factoryAddress,
            receiverImplementationAddress,
            vaultAddress,
            confirmationBlocks,
            paymentTokens.single(),
            protocolVersion,
        ),
    )
}

fun TerminalConfigSnapshot.selectedPaymentProfile(): TerminalPaymentProfile? {
    val profiles = resolvedPaymentProfiles()
    val requested = selectedProfileId?.let { id -> profiles.firstOrNull { it.id == id } }
    if (requested != null) return requested
    val activeId = runCatching {
        if (paymentTokens.size != 1) null else PaymentProfile.id(
            chainId,
            EvmAddress.parse(vaultAddress),
            EvmAddress.parse(paymentTokens.single().address),
        )
    }.getOrNull()
    return profiles.firstOrNull { it.id == activeId } ?: profiles.firstOrNull()
}

fun TerminalConfigSnapshot.selectingProfile(profileId: String): TerminalConfigSnapshot {
    val profiles = resolvedPaymentProfiles()
    val selected = requireNotNull(profiles.firstOrNull { it.id == profileId }) {
        "Payment profile is not configured on this terminal"
    }
    return copy(
        networkName = selected.networkName,
        rpcUrl = selected.rpcUrl,
        chainId = selected.chainId,
        factoryAddress = selected.factoryAddress,
        receiverImplementationAddress = selected.receiverImplementationAddress,
        vaultAddress = selected.vaultAddress,
        confirmationBlocks = selected.confirmationBlocks,
        paymentTokens = listOf(selected.token),
        protocolVersion = selected.protocolVersion,
        paymentProfiles = profiles,
        selectedProfileId = selected.id,
    )
}

fun TerminalConfigSnapshot.upsertingProfile(
    profile: TerminalPaymentProfile,
    operatorAddress: String,
): TerminalConfigSnapshot {
    val profiles = resolvedPaymentProfiles().toMutableList()
    val canonicalOperator = EvmAddress.parse(operatorAddress).value
    if (profiles.isNotEmpty()) {
        val existingOperator = EvmAddress.parse(requireNotNull(provisionedOperatorAddress)).value
        require(existingOperator.equals(canonicalOperator, true)) {
            "Every payment profile on one terminal must use the same device operator wallet"
        }
    }
    val index = profiles.indexOfFirst { it.id == profile.id }
    require(index >= 0 || profiles.size < ChainConfig.MAX_PAYMENT_PROFILES) {
        "This terminal already has the maximum ${ChainConfig.MAX_PAYMENT_PROFILES} payment profiles"
    }
    if (index < 0) {
        profiles += profile
    } else {
        // Re-scanning a portal QR refreshes chain-derived metadata for this exact route, but it
        // must never silently weaken a confirmation preference the merchant raised previously.
        // A different chain/vault/token identity still starts from its compiled network default.
        profiles[index] = profile.copy(
            confirmationBlocks = maxOf(
                profiles[index].confirmationBlocks,
                profile.confirmationBlocks,
            ),
        )
    }
    return copy(
        provisioned = true,
        provisionedOperatorAddress = canonicalOperator,
        paymentProfiles = profiles,
    ).selectingProfile(profile.id)
}

/** Returns null when the removed profile was the last configured checkout choice. */
fun TerminalConfigSnapshot.removingPaymentProfile(profileId: String): TerminalConfigSnapshot? {
    val profiles = resolvedPaymentProfiles()
    require(profiles.any { it.id == profileId }) {
        "Payment profile is not configured on this terminal"
    }
    val remaining = profiles.filterNot { it.id == profileId }
    if (remaining.isEmpty()) return null
    val nextId = selectedProfileId
        ?.takeIf { selected -> remaining.any { it.id == selected } }
        ?: remaining.first().id
    return copy(paymentProfiles = remaining).selectingProfile(nextId)
}

private data class LegacyFinalityMigration(
    val snapshot: TerminalConfigSnapshot,
    val adjustedProfileIds: Set<String>,
)

private fun TerminalConfigSnapshot.raisingLegacyConfirmationFloors(): LegacyFinalityMigration? =
    runCatching {
        val profiles = resolvedPaymentProfiles()
        require(profiles.isNotEmpty())
        val adjustedIds = mutableSetOf<String>()
        val adjustedProfiles = profiles.map { profile ->
            // Zero/negative and >64 were never valid legacy preferences and remain corruption.
            require(profile.confirmationBlocks in 1..64)
            val floor = KnownChainPolicy.requireProfile(profile.chainId).minimumConfirmationBlocks
            if (profile.confirmationBlocks < floor) {
                adjustedIds += profile.id
                profile.copy(confirmationBlocks = floor)
            } else {
                profile
            }
        }
        val requestedSelection = selectedProfileId
            ?.let { id -> adjustedProfiles.firstOrNull { it.id == id }?.id }
            ?: selectedPaymentProfile()?.id
        val selected = requireNotNull(
            requestedSelection?.let { id -> adjustedProfiles.firstOrNull { it.id == id } },
        )
        LegacyFinalityMigration(
            snapshot = copy(paymentProfiles = adjustedProfiles).selectingProfile(selected.id),
            adjustedProfileIds = adjustedIds,
        )
    }.getOrNull()

private fun TerminalConfigSnapshot.catalogNormalized(): TerminalConfigSnapshot {
    if (!provisioned) return this
    val selected = selectedPaymentProfile() ?: return this
    return copy(paymentProfiles = resolvedPaymentProfiles()).selectingProfile(selected.id)
}

private fun TerminalConfigSnapshot.canonicalCatalog(): TerminalConfigSnapshot {
    val canonicalOperator = EvmAddress.parse(requireNotNull(provisionedOperatorAddress)).value
    val profiles = resolvedPaymentProfiles().map { profile ->
        profile.copy(
            factoryAddress = EvmAddress.parse(profile.factoryAddress).value,
            receiverImplementationAddress = EvmAddress.parse(profile.receiverImplementationAddress).value,
            vaultAddress = EvmAddress.parse(profile.vaultAddress).value,
            token = profile.token.copy(address = EvmAddress.parse(profile.token.address).value),
        )
    }
    val selectedId = requireNotNull(selectedPaymentProfile()).id
    return copy(
        provisionedOperatorAddress = canonicalOperator,
        paymentProfiles = profiles,
    ).selectingProfile(selectedId)
}

internal fun TerminalConfigSnapshot.hasCompleteProvisioning(): Boolean = runCatching {
    require(provisioned)
    require(!EvmAddress.parse(requireNotNull(provisionedOperatorAddress)).isZero)
    val profiles = resolvedPaymentProfiles()
    require(profiles.isNotEmpty())
    require(profiles.size <= ChainConfig.MAX_PAYMENT_PROFILES)
    require(profiles.map { it.id }.distinct().size == profiles.size)
    if (paymentProfiles.isNotEmpty()) {
        require(selectedProfileId != null)
        require(profiles.any { it.id == selectedProfileId })
    }
    profiles.forEach { it.requireComplete() }
    val selected = requireNotNull(selectedPaymentProfile())
    require(networkName == selected.networkName)
    require(rpcUrl == selected.rpcUrl)
    require(chainId == selected.chainId)
    require(factoryAddress.equals(selected.factoryAddress, true))
    require(receiverImplementationAddress.equals(selected.receiverImplementationAddress, true))
    require(vaultAddress.equals(selected.vaultAddress, true))
    require(confirmationBlocks == selected.confirmationBlocks)
    require(protocolVersion == selected.protocolVersion)
    require(paymentTokens.size == 1)
    val activeToken = paymentTokens.single()
    require(activeToken.address.equals(selected.token.address, true))
    require(activeToken.symbol == selected.token.symbol && activeToken.decimals == selected.token.decimals)
}.isSuccess

private fun TerminalPaymentProfile.requireComplete() {
    require(networkName.isNotBlank())
    require(rpcUrl.isNotBlank())
    require(chainId > 0)
    require(!EvmAddress.parse(factoryAddress).isZero)
    require(!EvmAddress.parse(receiverImplementationAddress).isZero)
    require(!EvmAddress.parse(vaultAddress).isZero)
    require(confirmationBlocks in 1..64)
    require(protocolVersion.isNotBlank())
    require(!EvmAddress.parse(token.address).isZero)
    require(token.symbol.isNotBlank())
    require(token.decimals in 0..255)
    val known = KnownChainPolicy.requireProfile(chainId)
    known.requireValidCreate2Fixture()
    require(confirmationBlocks >= known.minimumConfirmationBlocks)
    require(networkName == known.networkName)
    require(factoryAddress.equals(known.factory.value, true))
    require(receiverImplementationAddress.equals(known.receiverImplementation.value, true))
    require(protocolVersion == known.protocolVersion)
    NetworkConfig(
        chainId = chainId,
        rpcUrl = rpcUrl,
        factory = known.factory,
        receiverImplementation = known.receiverImplementation,
        vault = EvmAddress.parse(vaultAddress),
    )
}
