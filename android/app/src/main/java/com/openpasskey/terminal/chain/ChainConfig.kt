package com.openpasskey.terminal.chain

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.openpasskey.erc681.EvmAddress
import com.openpasskey.erc681.NetworkConfig
import com.openpasskey.erc681.NativeAsset
import com.openpasskey.erc681.PaymentProfile
import com.openpasskey.terminal.provisioning.KnownChainPolicy
import com.openpasskey.terminal.printing.AutomaticReceiptClaimResult
import com.openpasskey.terminal.printing.AutomaticReceiptClaimStore
import com.openpasskey.terminal.rpc.RpcEndpointStore

data class PaymentToken(
    val address: String,
    val symbol: String,
    val decimals: Int,
)

/** Local receipt identity. Invoices snapshot this so later profile edits cannot alter reprints. */
class MerchantReceiptProfile private constructor(
    val name: String,
    val abn: String,
) {
    override fun equals(other: Any?): Boolean =
        other is MerchantReceiptProfile && name == other.name && abn == other.abn

    override fun hashCode(): Int = 31 * name.hashCode() + abn.hashCode()

    override fun toString(): String = "MerchantReceiptProfile(name=$name, abn=$abn)"

    companion object {
        const val DEFAULT_NAME = "OPK Terminal"
        const val MAX_NAME_LENGTH = 64
        val DEFAULT = MerchantReceiptProfile(DEFAULT_NAME, "")

        fun fromInput(name: String, abn: String): MerchantReceiptProfile {
            require(name.none(Char::isISOControl)) {
                "Merchant name must be a single printable line."
            }
            val canonicalName = name.trim().replace(Regex("\\s+"), " ")
            require(canonicalName.isNotEmpty()) { "Merchant name is required." }
            require(canonicalName.codePointCount(0, canonicalName.length) <= MAX_NAME_LENGTH) {
                "Merchant name must be $MAX_NAME_LENGTH characters or fewer."
            }
            val digits = abn.filter { it in '0'..'9' }
            require(abn.all { it in '0'..'9' || it == ' ' }) {
                "ABN may contain digits and spaces only."
            }
            if (digits.isNotEmpty()) {
                require(digits.length == 11) { "ABN must contain 11 digits." }
                require(isValidAustralianAbn(digits)) { "Enter a valid Australian ABN." }
            }
            val formattedAbn = if (digits.isEmpty()) {
                ""
            } else {
                "${digits.take(2)} ${digits.substring(2, 5)} " +
                    "${digits.substring(5, 8)} ${digits.takeLast(3)}"
            }
            return MerchantReceiptProfile(canonicalName, formattedAbn)
        }

        internal fun isValidAustralianAbn(digits: String): Boolean {
            if (digits.length != 11 || !digits.all { it in '0'..'9' }) return false
            val weights = intArrayOf(10, 1, 3, 5, 7, 9, 11, 13, 15, 17, 19)
            val weightedSum = digits.map { it - '0' }
                .mapIndexed { index, digit ->
                    (if (index == 0) digit - 1 else digit) * weights[index]
                }
                .sum()
            return weightedSum % 89 == 0
        }
    }
}

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
class ChainConfig(
    context: Context,
    /** Shared app capability. Resolved credential-bearing URLs must never be persisted. */
    val rpcEndpointStore: RpcEndpointStore = RpcEndpointStore(context),
) : AutomaticReceiptClaimStore {
    companion object {
        private const val PREFS_NAME = "opk_chain_config"
        private const val KEY_CONFIG_JSON_V3 = "provisioned_config_v3"
        private const val KEY_PROVISIONED_V3 = "is_provisioned_v3"
        private const val KEY_CONFIG_JSON_V2 = "provisioned_config_v2"
        private const val KEY_PROVISIONED_V2 = "is_provisioned_v2"
        private const val KEY_FINALITY_MIGRATION_PROFILE_IDS_V3 =
            "finality_migration_profile_ids_v3"
        private const val KEY_RECEIPT_MERCHANT_NAME = "receipt_merchant_name"
        private const val KEY_RECEIPT_MERCHANT_ABN = "receipt_merchant_abn"
        private const val KEY_AUTO_SWEEP_ENABLED = "auto_sweep_enabled_v1"
        private const val KEY_AUTO_SWEEP_DISMISSED_FINGERPRINTS =
            "auto_sweep_dismissed_fingerprints_v1"
        private const val KEY_AUTOMATIC_RECEIPT_CLAIMS = "automatic_receipt_claims_v1"

        // Legacy per-field keys are read only to preserve the merchant's confirmation preference.
        private const val KEY_NETWORK_NAME = "network_name"
        private const val KEY_RPC_URL = "rpc_url"
        private const val KEY_CHAIN_ID = "chain_id"
        private const val KEY_FACTORY_ADDRESS = "factory_address"
        private const val KEY_RECEIVER_IMPLEMENTATION = "receiver_implementation"
        private const val KEY_VAULT_ADDRESS = "vault_address"
        private const val KEY_CONFIRMATION_BLOCKS = "confirmation_blocks"
        private const val KEY_PAYMENT_TOKENS = "payment_tokens"

        const val DEFAULT_NETWORK_NAME = "Base Mainnet"
        const val DEFAULT_RPC_URL = "https://mainnet.base.org"
        const val DEFAULT_CHAIN_ID = KnownChainPolicy.DEFAULT_CHAIN_ID
        const val DEFAULT_FACTORY_ADDRESS = "0x5418ab1790eaf96a20e26146c5b7765cb99328da"
        const val DEFAULT_RECEIVER_IMPLEMENTATION = "0xe6393f6176865cc62cd08d8b8f0c38d35af55254"
        const val DEFAULT_CONFIRMATION_BLOCKS = 1
        const val MAX_PAYMENT_PROFILES = 32
        private const val MAX_AUTO_SWEEP_DISMISSALS = 512
        private const val MAX_AUTOMATIC_RECEIPT_CLAIMS = 1_024
        private val RECEIPT_CLAIM_FINGERPRINT = Regex("^receipt-v1:[0-9a-f]{64}$")

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
    fun merchantReceiptProfile(): MerchantReceiptProfile =
        MerchantReceiptProfile.fromInput(
            name = prefs.getString(
                KEY_RECEIPT_MERCHANT_NAME,
                MerchantReceiptProfile.DEFAULT_NAME,
            ) ?: MerchantReceiptProfile.DEFAULT_NAME,
            abn = prefs.getString(KEY_RECEIPT_MERCHANT_ABN, "") ?: "",
        )

    /** Saves only local receipt presentation metadata, never chain or signing configuration. */
    @Synchronized
    fun updateMerchantReceiptProfile(name: String, abn: String): Boolean {
        val profile = MerchantReceiptProfile.fromInput(name, abn)
        return prefs.edit()
            .putString(KEY_RECEIPT_MERCHANT_NAME, profile.name)
            .putString(KEY_RECEIPT_MERCHANT_ABN, profile.abn)
            .commit()
    }

    /** Local operational preference. Missing values deliberately remain disabled after upgrades. */
    @Synchronized
    fun autoSweepEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_SWEEP_ENABLED, false)

    @Synchronized
    fun updateAutoSweepEnabled(enabled: Boolean): Boolean = prefs.edit()
        .putBoolean(KEY_AUTO_SWEEP_ENABLED, enabled)
        .commit()

    /** Exact canonical review snapshots dismissed by the merchant, never signing authority. */
    @Synchronized
    fun autoSweepDismissedFingerprints(): Set<String> =
        prefs.getStringSet(KEY_AUTO_SWEEP_DISMISSED_FINGERPRINTS, emptySet())
            ?.filterTo(linkedSetOf()) { fingerprint ->
                fingerprint.isNotBlank() && fingerprint.length <= 1_024 &&
                    fingerprint.none(Char::isISOControl)
            }
            .orEmpty()

    @Synchronized
    fun updateAutoSweepDismissedFingerprints(
        fingerprints: Set<String>,
        retainFingerprint: String? = null,
    ): Boolean {
        val valid = fingerprints.filterTo(linkedSetOf(), ::isValidLocalFingerprint)
        val required = retainFingerprint?.takeIf(::isValidLocalFingerprint)
        required?.let(valid::add)
        if (valid.size > MAX_AUTO_SWEEP_DISMISSALS) {
            // Preserve every earlier dismissal. Turning the preference off is safer than evicting
            // an old fingerprint and silently reopening its review after process restart.
            prefs.edit().putBoolean(KEY_AUTO_SWEEP_ENABLED, false).commit()
            return false
        }
        return prefs.edit()
            .putStringSet(KEY_AUTO_SWEEP_DISMISSED_FINGERPRINTS, valid)
            .commit()
    }

    @Synchronized
    override fun claims(): Set<String> = storedAutomaticReceiptClaims()

    @Synchronized
    override fun claim(fingerprint: String): AutomaticReceiptClaimResult {
        if (!isValidReceiptClaimFingerprint(fingerprint)) {
            return AutomaticReceiptClaimResult.PERSISTENCE_FAILED
        }
        val claims = storedAutomaticReceiptClaims().toMutableSet()
        if (fingerprint in claims) return AutomaticReceiptClaimResult.ALREADY_CLAIMED
        if (claims.size >= MAX_AUTOMATIC_RECEIPT_CLAIMS) {
            return AutomaticReceiptClaimResult.PERSISTENCE_FAILED
        }
        claims += fingerprint
        return if (prefs.edit().putStringSet(KEY_AUTOMATIC_RECEIPT_CLAIMS, claims).commit()) {
            AutomaticReceiptClaimResult.CLAIMED
        } else {
            AutomaticReceiptClaimResult.PERSISTENCE_FAILED
        }
    }

    @Synchronized
    override fun release(fingerprint: String): Boolean {
        val claims = storedAutomaticReceiptClaims().toMutableSet()
        if (!claims.remove(fingerprint)) return true
        return prefs.edit().putStringSet(KEY_AUTOMATIC_RECEIPT_CLAIMS, claims).commit()
    }

    @Synchronized
    override fun retainOnly(liveFingerprints: Set<String>): Boolean {
        val current = storedAutomaticReceiptClaims()
        val retained = current.intersect(
            liveFingerprints.filterTo(mutableSetOf(), ::isValidReceiptClaimFingerprint),
        )
        if (retained == current) return true
        return prefs.edit().putStringSet(KEY_AUTOMATIC_RECEIPT_CLAIMS, retained).commit()
    }

    private fun storedAutomaticReceiptClaims(): Set<String> =
        prefs.getStringSet(KEY_AUTOMATIC_RECEIPT_CLAIMS, emptySet())
            ?.filterTo(linkedSetOf(), ::isValidReceiptClaimFingerprint)
            .orEmpty()

    private fun isValidReceiptClaimFingerprint(fingerprint: String): Boolean =
        RECEIPT_CLAIM_FINGERPRINT.matches(fingerprint)

    private fun isValidLocalFingerprint(fingerprint: String): Boolean =
        fingerprint.isNotBlank() && fingerprint.length <= 1_024 &&
            fingerprint.none(Char::isISOControl)

    @Synchronized
    fun snapshot(): TerminalConfigSnapshot {
        // Once v3 exists it is authoritative. A malformed v3 value must fail closed rather than
        // falling back to stale v2 data that may describe a different checkout route.
        if (prefs.getBoolean(KEY_PROVISIONED_V3, false)) {
            val stored = storedSnapshot(KEY_CONFIG_JSON_V3)
            if (stored != null) {
                scrubInactivePlaintextRpcMaterial(KEY_CONFIG_JSON_V3)
                return stored
            }
            recoverThenScrubFailedConfiguration(KEY_CONFIG_JSON_V3)
            return unprovisionedSnapshot()
        }
        if (prefs.getBoolean(KEY_PROVISIONED_V2, false)) {
            val migrated = migrateV2Snapshot()
            if (migrated != null) return migrated
            recoverThenScrubFailedConfiguration(KEY_CONFIG_JSON_V2)
            return unprovisionedSnapshot()
        }
        recoverThenScrubLegacyRpcMaterial()
        return unprovisionedSnapshot()
    }

    private fun unprovisionedSnapshot(): TerminalConfigSnapshot {
        return TerminalConfigSnapshot(
            networkName = DEFAULT_NETWORK_NAME,
            // Legacy mutable endpoint fields are not trust roots. A fresh, reset, or malformed
            // unprovisioned configuration always returns the compiled production default.
            rpcUrl = DEFAULT_RPC_URL,
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

    /**
     * Changes finality for future invoices on one network. Existing invoice rows retain the
     * confirmation policy they snapshotted when their payment QR was published.
     */
    @Synchronized
    fun updateNetworkConfirmationBlocks(chainId: Long, confirmationBlocks: Int): Boolean {
        val current = snapshot()
        val updated = current.updatingNetworkConfirmationBlocks(chainId, confirmationBlocks)
        return persist(updated.canonicalCatalog())
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

    /** Admin-only reset; invoice history and its snapshotted receipt identity remain untouched. */
    @Synchronized
    fun clearProvisioning(): Boolean = prefs.edit()
        .remove(KEY_CONFIG_JSON_V3)
        .remove(KEY_PROVISIONED_V3)
        .remove(KEY_CONFIG_JSON_V2)
        .remove(KEY_PROVISIONED_V2)
        .remove(KEY_FINALITY_MIGRATION_PROFILE_IDS_V3)
        .remove(KEY_AUTO_SWEEP_ENABLED)
        .remove(KEY_AUTO_SWEEP_DISMISSED_FINGERPRINTS)
        .commit()

    fun isConfigured(): Boolean = snapshot().let { it.provisioned && it.hasCompleteProvisioning() }

    private fun storedSnapshot(jsonKey: String): TerminalConfigSnapshot? {
        val json = prefs.all[jsonKey] as? String ?: return null
        val stored = decodeSnapshot(json) ?: return null
        // v3 is catalog-native. Never reinterpret an empty/corrupt catalog as its flattened
        // downgrade facade, which exists only so older app versions can inspect the selection.
        if (stored.paymentProfiles.isEmpty() || stored.selectedProfileId == null) return null
        if (!stored.provisioned || !stored.hasCompleteProvisioning(
                requireUniformNetworkConfirmations = false,
            )
        ) return null
        val catalog = stored.catalogNormalized()
        val normalized = catalog.normalizingNetworkConfirmationBlocks()
        if (!normalized.hasCompleteProvisioning()) return null
        val secured = runCatching {
            normalized.protectingRpcEndpointOverrides(rpcEndpointStore)
        }.getOrNull() ?: return null
        if (secured != stored || secured != catalog || jsonRequiresRpcCanonicalization(json)) {
            val previousById = catalog.resolvedPaymentProfiles().associateBy { it.id }
            val adjustedIds = secured.resolvedPaymentProfiles()
                .filter { profile ->
                    profile.confirmationBlocks > requireNotNull(previousById[profile.id]).confirmationBlocks
                }
                .mapTo(mutableSetOf()) { it.id }
            val existingNoticeIds = prefs
                .getStringSet(KEY_FINALITY_MIGRATION_PROFILE_IDS_V3, null)
                .orEmpty()
            val canonical = secured.canonicalCatalog()
            if (!persistProtected(
                    canonical,
                    existingNoticeIds + adjustedIds,
                )
            ) return null
            return canonical
        }
        return secured
    }

    /**
     * v2 accepted one confirmation on this chain. Raise only historically valid positive values
     * to the current compiled floor, then immediately persist the canonical v3 catalog. Invalid
     * addresses, duplicate routes, unknown chains, and other malformed data still fail closed.
     */
    private fun migrateV2Snapshot(): TerminalConfigSnapshot? {
        val json = prefs.all[KEY_CONFIG_JSON_V2] as? String ?: return null
        val stored = decodeSnapshot(json) ?: return null
        if (!stored.provisioned) return null
        val migration = stored.raisingLegacyConfirmationFloors() ?: return null
        if (!migration.snapshot.hasCompleteProvisioning()) return null
        val canonical = migration.snapshot.canonicalCatalog()
        val secured = runCatching {
            canonical.protectingRpcEndpointOverrides(rpcEndpointStore)
        }.getOrNull() ?: return null
        if (!persistProtected(secured, migration.adjustedProfileIds)) return null
        return secured
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

    /**
     * A malformed catalog cannot be rewritten safely because doing so might accidentally repair
     * and reactivate a configuration that was supposed to fail closed. Recover only one exact,
     * supported-chain custom endpoint, encrypt it first, then remove every ordinary-preferences
     * value capable of retaining an RPC credential. Provisioning flags remain untouched so a
     * malformed v3 catalog stays authoritative over stale v2 data.
     */
    private fun recoverThenScrubFailedConfiguration(configJsonKey: String) {
        (prefs.all[configJsonKey] as? String)
            ?.let(::uniquelyRecoverableCustomRpcEndpoint)
            ?.let { endpoint ->
                runCatching { rpcEndpointStore.setOverride(endpoint.chainId, endpoint.url) }
            }
        scrubInactivePlaintextRpcMaterial(activeConfigJsonKey = null)
    }

    /** Standalone legacy fields are usable only when both their chain and endpoint are present. */
    private fun recoverThenScrubLegacyRpcMaterial() {
        val endpoint = prefs.all[KEY_RPC_URL] as? String
        val chainId = (prefs.all[KEY_CHAIN_ID] as? Number)?.toLong()
        val hasVersionedCatalog = prefs.contains(KEY_CONFIG_JSON_V3) ||
            prefs.contains(KEY_CONFIG_JSON_V2)
        if (!hasVersionedCatalog && endpoint != null && chainId != null) {
            val policyUrl = runCatching { KnownChainPolicy.requireProfile(chainId).rpcUrl }.getOrNull()
            if (policyUrl != null && endpoint != policyUrl) {
                runCatching { rpcEndpointStore.setOverride(chainId, endpoint) }
            }
        }
        scrubInactivePlaintextRpcMaterial(activeConfigJsonKey = null)
    }

    /**
     * Versioned JSON values mix RPC URLs with other provisioning fields, so an inactive or failed
     * value must be removed as a whole. Independent receipt and operational preferences are left
     * intact. A valid active v3 catalog already contains only its public policy URL.
     */
    private fun scrubInactivePlaintextRpcMaterial(activeConfigJsonKey: String?) {
        val hasLegacyRpcUrl = prefs.contains(KEY_RPC_URL)
        val hasInactiveV3 = activeConfigJsonKey != KEY_CONFIG_JSON_V3 &&
            prefs.contains(KEY_CONFIG_JSON_V3)
        val hasInactiveV2 = activeConfigJsonKey != KEY_CONFIG_JSON_V2 &&
            prefs.contains(KEY_CONFIG_JSON_V2)
        if (!hasLegacyRpcUrl && !hasInactiveV3 && !hasInactiveV2) return

        val editor = prefs.edit().remove(KEY_RPC_URL)
        if (activeConfigJsonKey != KEY_CONFIG_JSON_V3) editor.remove(KEY_CONFIG_JSON_V3)
        if (activeConfigJsonKey != KEY_CONFIG_JSON_V2) editor.remove(KEY_CONFIG_JSON_V2)
        editor.commit()
    }

    private fun uniquelyRecoverableCustomRpcEndpoint(json: String): RecoverableRpcEndpoint? {
        val scan = scanRpcEndpointMaterial(json) ?: return null
        if (scan.hasUnattributedRpcUrl) return null
        return scan.customCandidates.singleOrNull()
    }

    private fun jsonRequiresRpcCanonicalization(json: String): Boolean {
        val scan = scanRpcEndpointMaterial(json) ?: return true
        return scan.hasUnattributedRpcUrl || scan.customCandidates.isNotEmpty()
    }

    private fun scanRpcEndpointMaterial(json: String): RpcEndpointMaterialScan? {
        val root = runCatching { JsonParser.parseString(json) }.getOrNull() ?: return null
        val candidates = linkedSetOf<RecoverableRpcEndpoint>()
        var hasUnattributedRpcUrl = false

        fun inspect(element: JsonElement) {
            when {
                element.isJsonArray -> element.asJsonArray.forEach(::inspect)
                element.isJsonObject -> {
                    val objectValue = element.asJsonObject
                    if (objectValue.has("rpcUrl")) {
                        val rpcValue = objectValue.get("rpcUrl")
                        val chainValue = objectValue.get("chainId")
                        val rpcUrl = rpcValue
                            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                            ?.asString
                        val chainId = chainValue
                            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                            ?.let { runCatching { it.asLong }.getOrNull() }
                        val policyUrl = chainId
                            ?.let { runCatching { KnownChainPolicy.requireProfile(it).rpcUrl }.getOrNull() }
                        if (rpcUrl == null || chainId == null || policyUrl == null) {
                            hasUnattributedRpcUrl = true
                        } else if (rpcUrl != policyUrl) {
                            candidates += RecoverableRpcEndpoint(chainId, rpcUrl)
                        }
                    }
                    objectValue.entrySet().forEach { (_, child) -> inspect(child) }
                }
            }
        }

        inspect(root)
        return RpcEndpointMaterialScan(
            customCandidates = candidates,
            hasUnattributedRpcUrl = hasUnattributedRpcUrl,
        )
    }

    private fun persist(
        snapshot: TerminalConfigSnapshot,
        adjustedConfirmationProfileIds: Set<String>? = null,
    ): Boolean {
        val secured = snapshot.protectingRpcEndpointOverrides(rpcEndpointStore) ?: return false
        return persistProtected(secured, adjustedConfirmationProfileIds)
    }

    /** Receives only snapshots already stripped of credential-bearing endpoint overrides. */
    private fun persistProtected(
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

private data class RecoverableRpcEndpoint(
    val chainId: Long,
    val url: String,
)

private data class RpcEndpointMaterialScan(
    val customCandidates: Set<RecoverableRpcEndpoint>,
    val hasUnattributedRpcUrl: Boolean,
)

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
    // Confirmation depth is a merchant preference for the network, not for a vault/token route.
    // Existing routes are the merchant's saved network preference. Normalize those to their
    // strongest value, but never let an incoming compiled default override an explicit choice.
    // The incoming value applies only to the first profile configured on a chain.
    val networkConfirmationBlocks = profiles
        .asSequence()
        .filter { it.chainId == profile.chainId }
        .map { it.confirmationBlocks }
        .maxOrNull()
        ?: profile.confirmationBlocks
    val inheritedProfile = profile.copy(confirmationBlocks = networkConfirmationBlocks)
    if (index < 0) {
        profiles += inheritedProfile
    } else {
        // Re-scanning refreshes chain-derived metadata without weakening the network preference.
        profiles[index] = inheritedProfile
    }
    profiles.indices.forEach { profileIndex ->
        if (profiles[profileIndex].chainId == profile.chainId) {
            profiles[profileIndex] = profiles[profileIndex].copy(
                confirmationBlocks = networkConfirmationBlocks,
            )
        }
    }
    return copy(
        provisioned = true,
        provisionedOperatorAddress = canonicalOperator,
        paymentProfiles = profiles,
    ).selectingProfile(inheritedProfile.id)
}

/** Applies one merchant-selected confirmation depth to every future-checkout route on a chain. */
fun TerminalConfigSnapshot.updatingNetworkConfirmationBlocks(
    chainId: Long,
    confirmationBlocks: Int,
): TerminalConfigSnapshot {
    require(provisioned) { "Provision this terminal before changing confirmation requirements" }
    val policy = KnownChainPolicy.requireProfile(chainId)
    require(confirmationBlocks in policy.minimumConfirmationBlocks..64) {
        "Confirmations for ${policy.networkName} must be between " +
            "${policy.minimumConfirmationBlocks} and 64"
    }
    val profiles = resolvedPaymentProfiles()
    require(profiles.any { it.chainId == chainId }) {
        "Network ${policy.networkName} is not configured on this terminal"
    }
    val selectedId = requireNotNull(selectedPaymentProfile()).id
    val updatedProfiles = profiles.map { profile ->
        if (profile.chainId == chainId) {
            profile.copy(confirmationBlocks = confirmationBlocks)
        } else {
            profile
        }
    }
    return copy(paymentProfiles = updatedProfiles).selectingProfile(selectedId)
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

/**
 * One-time migration for releases that allowed operational RPC URLs inside the non-secret profile
 * catalog. Every distinct per-chain override is encrypted first. Only after every write succeeds is
 * the returned catalog rewritten to the compiled public policy endpoints.
 *
 * SharedPreferences cannot atomically commit across two files. Encrypt-first prevents credential
 * loss; if the later catalog commit fails, the next read repeats this idempotent migration instead
 * of returning a partially sanitized configuration.
 */
internal fun TerminalConfigSnapshot.protectingRpcEndpointOverrides(
    endpointStore: RpcEndpointStore,
): TerminalConfigSnapshot? {
    if (!provisioned) return this
    val profiles = resolvedPaymentProfiles()
    if (profiles.isEmpty()) return null
    val policyUrls = profiles
        .map { profile -> profile.chainId to KnownChainPolicy.requireProfile(profile.chainId).rpcUrl }
        .toMap()
    val overrides = profiles
        .groupBy(TerminalPaymentProfile::chainId)
        .mapValues { (chainId, networkProfiles) ->
            networkProfiles.map(TerminalPaymentProfile::rpcUrl)
                .filterNot { it == requireNotNull(policyUrls[chainId]) }
                .distinct()
        }
    // The new store deliberately supports one endpoint per chain. Ambiguous historical catalogs
    // fail closed rather than silently selecting one credential and changing another route.
    if (overrides.values.any { it.size > 1 }) return null
    overrides.toSortedMap().forEach { (chainId, endpoints) ->
        val endpoint = endpoints.singleOrNull() ?: return@forEach
        val encrypted = runCatching { endpointStore.setOverride(chainId, endpoint) }
            .getOrDefault(false)
        if (!encrypted) return null
    }
    val sanitizedProfiles = profiles.map { profile ->
        profile.copy(rpcUrl = requireNotNull(policyUrls[profile.chainId]))
    }
    val selectedId = selectedPaymentProfile()?.id ?: return null
    return copy(paymentProfiles = sanitizedProfiles).selectingProfile(selectedId)
}

private data class LegacyFinalityMigration(
    val snapshot: TerminalConfigSnapshot,
    val adjustedProfileIds: Set<String>,
)

private fun TerminalConfigSnapshot.raisingLegacyConfirmationFloors(): LegacyFinalityMigration? =
    runCatching {
        val profiles = resolvedPaymentProfiles()
        require(profiles.isNotEmpty())
        val adjustedProfiles = profiles.map { profile ->
            // Zero/negative and >64 were never valid legacy preferences and remain corruption.
            require(profile.confirmationBlocks in 1..64)
            val floor = KnownChainPolicy.requireProfile(profile.chainId).minimumConfirmationBlocks
            if (profile.confirmationBlocks < floor) {
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
        val normalized = copy(paymentProfiles = adjustedProfiles)
            .selectingProfile(selected.id)
            .normalizingNetworkConfirmationBlocks()
        val previousById = profiles.associateBy { it.id }
        val adjustedIds = normalized.resolvedPaymentProfiles()
            .filter { profile ->
                profile.confirmationBlocks > requireNotNull(previousById[profile.id]).confirmationBlocks
            }
            .mapTo(mutableSetOf()) { it.id }
        LegacyFinalityMigration(
            snapshot = normalized,
            adjustedProfileIds = adjustedIds,
        )
    }.getOrNull()

private fun TerminalConfigSnapshot.catalogNormalized(): TerminalConfigSnapshot {
    if (!provisioned) return this
    val selected = selectedPaymentProfile() ?: return this
    return copy(paymentProfiles = resolvedPaymentProfiles()).selectingProfile(selected.id)
}

/** Repairs historical same-chain divergence by choosing the strongest stored value. */
internal fun TerminalConfigSnapshot.normalizingNetworkConfirmationBlocks(): TerminalConfigSnapshot {
    if (!provisioned) return this
    val profiles = resolvedPaymentProfiles()
    if (profiles.isEmpty()) return this
    val strongestByChain = profiles.groupingBy { it.chainId }
        .fold(0) { strongest, profile -> maxOf(strongest, profile.confirmationBlocks) }
    val normalized = profiles.map { profile ->
        profile.copy(confirmationBlocks = requireNotNull(strongestByChain[profile.chainId]))
    }
    if (normalized == profiles) return this
    val selectedId = requireNotNull(selectedPaymentProfile()).id
    return copy(paymentProfiles = normalized).selectingProfile(selectedId)
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

internal fun TerminalConfigSnapshot.hasCompleteProvisioning(
    requireUniformNetworkConfirmations: Boolean = true,
): Boolean = runCatching {
    require(provisioned)
    require(!EvmAddress.parse(requireNotNull(provisionedOperatorAddress)).isZero)
    val profiles = resolvedPaymentProfiles()
    require(profiles.isNotEmpty())
    require(profiles.size <= ChainConfig.MAX_PAYMENT_PROFILES)
    require(profiles.map { it.id }.distinct().size == profiles.size)
    if (requireUniformNetworkConfirmations) {
        profiles.groupBy { it.chainId }.values.forEach { networkProfiles ->
            require(networkProfiles.map { it.confirmationBlocks }.distinct().size == 1)
        }
    }
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
    val paymentAsset = EvmAddress.parse(token.address)
    require(protocolVersion == known.protocolVersionFor(paymentAsset))
    if (NativeAsset.isNative(paymentAsset)) {
        require(token.symbol == known.nativeCurrencySymbol)
        require(token.decimals == known.nativeCurrencyDecimals)
        require(token.decimals == NativeAsset.DECIMALS)
    }
    NetworkConfig(
        chainId = chainId,
        rpcUrl = rpcUrl,
        factory = known.factory,
        receiverImplementation = known.receiverImplementation,
        vault = EvmAddress.parse(vaultAddress),
    )
}
