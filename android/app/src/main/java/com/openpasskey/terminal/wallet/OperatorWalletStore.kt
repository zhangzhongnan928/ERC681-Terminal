package com.openpasskey.terminal.wallet

import android.content.Context
import android.app.KeyguardManager
import android.os.Build
import android.os.UserManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import androidx.annotation.RequiresApi
import com.openpasskey.erc681.EvmAddress
import com.openpasskey.terminal.data.model.SettlementFeeMode
import com.openpasskey.terminal.settlement.SettlementBalancePolicy
import com.openpasskey.terminal.settlement.SettlementFeeQuote
import org.web3j.crypto.Credentials
import org.web3j.crypto.ECKeyPair
import org.web3j.crypto.Keys
import org.web3j.crypto.RawTransaction
import org.web3j.crypto.TransactionEncoder
import org.web3j.crypto.transaction.type.Transaction1559
import org.web3j.crypto.transaction.type.TransactionType
import org.web3j.utils.Numeric
import java.math.BigInteger
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

enum class OperatorWalletAvailability {
    NOT_CREATED,
    READY,
    UNAVAILABLE
}

data class OperatorWalletSnapshot(
    val availability: OperatorWalletAvailability,
    val address: String? = null,
    val error: String? = null,
    val activatedChainId: Long? = null,
    val activatedVaultAddress: String? = null,
    val activatedOperatorAddress: String? = null,
    val hardwareBacked: Boolean = false,
    val strongBoxBacked: Boolean = false,
    val deviceAuthenticationRequired: Boolean = false
)

/** Exact on-chain targets covered by the administrator's unattended-settlement grant. */
data class UnattendedAutoSweepScope(
    val chainId: Long,
    val vaultAddress: String,
    val operatorAddress: String,
) {
    init {
        require(chainId > 0) { "Auto-sweep grant chain ID must be positive" }
        EvmAddress.parse(vaultAddress)
        EvmAddress.parse(operatorAddress)
    }

    internal fun canonical(): UnattendedAutoSweepScope = copy(
        vaultAddress = EvmAddress.parse(vaultAddress).value.lowercase(),
        operatorAddress = EvmAddress.parse(operatorAddress).value.lowercase(),
    )

    internal fun encoded(): String = canonical().let {
        "${it.chainId}|${it.vaultAddress}|${it.operatorAddress}"
    }

    companion object {
        internal fun decode(value: String): UnattendedAutoSweepScope? = runCatching {
            val parts = value.split('|')
            require(parts.size == 3)
            UnattendedAutoSweepScope(parts[0].toLong(), parts[1], parts[2]).canonical()
        }.getOrNull()
    }
}

data class UnattendedAutoSweepGrantSnapshot(
    val ready: Boolean,
    val scopes: Set<UnattendedAutoSweepScope> = emptySet(),
)

internal fun requireExactUnattendedAutoSweepGrant(
    grant: UnattendedAutoSweepGrantSnapshot,
    requestedScope: UnattendedAutoSweepScope,
) {
    check(grant.ready && grant.scopes == setOf(requestedScope.canonical())) {
        "Auto-sweep is not enrolled for this exact chain, vault, and operator"
    }
}

/**
 * Stores the secp256k1 terminal identity and settlement-operator key. Existing random identifiers
 * from older app versions are never parsed, imported, or reinterpreted as wallet keys.
 *
 * Android Keystore protects the AES wrapping key. The Ethereum key is decrypted only for the
 * shortest practical signing window, but Android Keystore cannot itself sign secp256k1.
 */
class OperatorWalletStore(context: Context) {
    private val applicationContext = context.applicationContext
    private val preferences = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun snapshot(): OperatorWalletSnapshot {
        val address = preferences.getString(KEY_ADDRESS, null)
        val ciphertext = preferences.getString(KEY_CIPHERTEXT, null)
        val iv = preferences.getString(KEY_IV, null)
        if (address == null && ciphertext == null && iv == null) {
            return OperatorWalletSnapshot(OperatorWalletAvailability.NOT_CREATED)
        }
        if (address == null || ciphertext == null || iv == null) {
            return OperatorWalletSnapshot(
                OperatorWalletAvailability.UNAVAILABLE,
                address,
                "Operator wallet storage is incomplete; do not create a replacement until the old operator is revoked."
            )
        }
        if (!keyStore().containsAlias(KEYSTORE_ALIAS)) {
            return OperatorWalletSnapshot(
                OperatorWalletAvailability.UNAVAILABLE,
                address,
                "The Keystore wrapping key is unavailable. Revoke this operator address from the vault before replacing it."
            )
        }
        val protection = runCatching { wrappingKeyProtection() }.getOrElse { error ->
            return OperatorWalletSnapshot(
                OperatorWalletAvailability.UNAVAILABLE,
                address,
                "Unable to verify the Keystore protection: ${error.message}"
            )
        }
        if (!protection.deviceAuthenticationRequired) {
            return OperatorWalletSnapshot(
                OperatorWalletAvailability.UNAVAILABLE,
                address,
                "This wallet predates device-authenticated key access. Revoke it before replacement.",
                hardwareBacked = protection.hardwareBacked,
                strongBoxBacked = protection.strongBoxBacked
            )
        }
        return OperatorWalletSnapshot(
            OperatorWalletAvailability.READY,
            address,
            activatedChainId = preferences.getLong(KEY_ACTIVATED_CHAIN_ID, 0).takeIf { it > 0 },
            activatedVaultAddress = preferences.getString(KEY_ACTIVATED_VAULT, null),
            activatedOperatorAddress = preferences.getString(KEY_ACTIVATED_OPERATOR, null),
            hardwareBacked = protection.hardwareBacked,
            strongBoxBacked = protection.strongBoxBacked,
            deviceAuthenticationRequired = protection.deviceAuthenticationRequired
        )
    }

    /** Creates the auth-bound wrapping key before the UI presents its first device-auth prompt. */
    @Synchronized
    fun prepareWalletCreation() {
        check(snapshot().availability == OperatorWalletAvailability.NOT_CREATED) {
            "An operator wallet already exists"
        }
        getOrCreateWrappingKey()
    }

    @Synchronized
    fun recordVerifiedSettlementTarget(
        chainId: Long,
        vaultAddress: String,
        provisionedOperatorAddress: String,
    ) {
        val wallet = snapshot()
        check(wallet.availability == OperatorWalletAvailability.READY && wallet.address != null)
        check(chainId > 0)
        val vault = com.openpasskey.erc681.EvmAddress.parse(vaultAddress).value
        val operator = EvmAddress.parse(provisionedOperatorAddress).value
        check(wallet.address.equals(operator, true)) {
            "Provisioned operator does not match the local settlement wallet"
        }
        check(preferences.edit()
            .putLong(KEY_ACTIVATED_CHAIN_ID, chainId)
            .putString(KEY_ACTIVATED_VAULT, vault)
            .putString(KEY_ACTIVATED_OPERATOR, operator)
            .commit()
        ) { "Unable to record the verified settlement target" }
    }

    /** Creates the operator wallet once. Rotation is intentionally not an in-app one-tap action. */
    @Synchronized
    fun createWallet(): OperatorWalletSnapshot {
        check(snapshot().availability == OperatorWalletAvailability.NOT_CREATED) {
            "An operator wallet record already exists. Revoke it on the vault before any replacement."
        }
        val keyPair = OperatorKeyGenerator.create()
        val privateKeyBytes = Numeric.toBytesPadded(keyPair.privateKey, PRIVATE_KEY_BYTES)
        return try {
            val address = Keys.toChecksumAddress("0x${Keys.getAddress(keyPair)}")
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateWrappingKey())
            cipher.updateAAD(AAD)
            val ciphertext = cipher.doFinal(privateKeyBytes)
            val stored = preferences.edit()
                .putInt(KEY_FORMAT_VERSION, FORMAT_VERSION)
                .putString(KEY_ADDRESS, address)
                .putString(KEY_CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .commit()
            check(stored) { "Unable to persist the operator wallet" }
            OperatorWalletSnapshot(OperatorWalletAvailability.READY, address)
        } finally {
            privateKeyBytes.fill(0)
        }
    }

    /**
     * Destructive admin-only operation. Callers must show the operator address and warn that it
     * must first be revoked and emptied; this store deliberately cannot perform those chain acts.
     */
    @Synchronized
    fun resetWalletAfterExplicitConfirmation() {
        check(snapshot().availability != OperatorWalletAvailability.NOT_CREATED) {
            "No operator wallet exists"
        }
        if (keyStore().containsAlias(AUTO_SWEEP_KEYSTORE_ALIAS)) {
            keyStore().deleteEntry(AUTO_SWEEP_KEYSTORE_ALIAS)
        }
        if (keyStore().containsAlias(KEYSTORE_ALIAS)) keyStore().deleteEntry(KEYSTORE_ALIAS)
        check(preferences.edit().clear().commit()) { "Unable to clear the operator wallet record" }
    }

    /**
     * Re-wraps the operator key once, immediately after an explicit OS authentication. The
     * secondary key is usable only while Android reports the device unlocked, so ordinary
     * unattended payments do not prompt but reboot/lock state still fails closed.
     */
    @Synchronized
    fun enrollUnattendedAutoSweep(scopes: Set<UnattendedAutoSweepScope>) {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            "Unattended auto-sweep requires Android 9 or newer for locked-device key protection"
        }
        val canonicalScopes = canonicalGrantScopes(scopes)
        check(canonicalScopes.size == 1) {
            "Unattended auto-sweep supports one exact chain and vault target"
        }
        val wallet = snapshot()
        check(wallet.availability == OperatorWalletAvailability.READY && wallet.address != null) {
            wallet.error ?: "Create the operator wallet first"
        }
        check(canonicalScopes.all { it.operatorAddress.equals(wallet.address, true) }) {
            "Every auto-sweep target must use this terminal operator"
        }
        revokeUnattendedAutoSweepGrant()
        val privateKeyBytes = decryptPrimaryPrivateKey()
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, generateUnattendedWrappingKey())
            cipher.updateAAD(autoSweepAad(canonicalScopes))
            val ciphertext = cipher.doFinal(privateKeyBytes)
            val stored = preferences.edit()
                .putInt(KEY_AUTO_SWEEP_FORMAT_VERSION, AUTO_SWEEP_FORMAT_VERSION)
                .putStringSet(KEY_AUTO_SWEEP_SCOPES, canonicalScopes.mapTo(linkedSetOf()) { it.encoded() })
                .putString(KEY_AUTO_SWEEP_CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                .putString(KEY_AUTO_SWEEP_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .commit()
            if (!stored) {
                revokeUnattendedAutoSweepGrant()
                error("Unable to persist the unattended auto-sweep grant")
            }
        } finally {
            privateKeyBytes.fill(0)
        }
    }

    @Synchronized
    fun unattendedAutoSweepGrant(): UnattendedAutoSweepGrantSnapshot {
        if (preferences.getInt(KEY_AUTO_SWEEP_FORMAT_VERSION, 0) != AUTO_SWEEP_FORMAT_VERSION ||
            !keyStore().containsAlias(AUTO_SWEEP_KEYSTORE_ALIAS) ||
            preferences.getString(KEY_AUTO_SWEEP_CIPHERTEXT, null) == null ||
            preferences.getString(KEY_AUTO_SWEEP_IV, null) == null
        ) return UnattendedAutoSweepGrantSnapshot(ready = false)
        val encodedScopes = preferences.getStringSet(KEY_AUTO_SWEEP_SCOPES, null)
            ?: return UnattendedAutoSweepGrantSnapshot(ready = false)
        val decodedScopes = encodedScopes.map(UnattendedAutoSweepScope::decode)
        if (decodedScopes.any { it == null }) {
            return UnattendedAutoSweepGrantSnapshot(ready = false)
        }
        val scopes = decodedScopes.filterNotNull().toSet()
        if (scopes.size != encodedScopes.size) {
            return UnattendedAutoSweepGrantSnapshot(ready = false)
        }
        return UnattendedAutoSweepGrantSnapshot(
            ready = scopes.isNotEmpty(),
            scopes = scopes,
        )
    }

    @Synchronized
    fun hasUnattendedAutoSweepGrant(scopes: Set<UnattendedAutoSweepScope>): Boolean {
        val grant = unattendedAutoSweepGrant()
        return grant.ready && grant.scopes == canonicalGrantScopes(scopes)
    }

    @Synchronized
    fun revokeUnattendedAutoSweepGrant() {
        if (keyStore().containsAlias(AUTO_SWEEP_KEYSTORE_ALIAS)) {
            keyStore().deleteEntry(AUTO_SWEEP_KEYSTORE_ALIAS)
        }
        check(preferences.edit()
            .remove(KEY_AUTO_SWEEP_FORMAT_VERSION)
            .remove(KEY_AUTO_SWEEP_SCOPES)
            .remove(KEY_AUTO_SWEEP_CIPHERTEXT)
            .remove(KEY_AUTO_SWEEP_IV)
            .commit()
        ) { "Unable to revoke the unattended auto-sweep grant" }
    }

    /**
     * The only signing capability exported by the wallet store. It refuses native transfers,
     * contract creation, calls outside the verified active chain/vault, and every method except the
     * typed sweepSessions selector. Credentials never leave this class, so UI and ViewModels cannot
     * turn the terminal into a general-purpose signer.
     */
    @Synchronized
    internal fun activateAndSignSettlementTransaction(
        transaction: RawTransaction,
        chainId: Long,
        vaultAddress: String,
        operatorAddress: String,
        eip1559: Boolean,
    ): ByteArray {
        // Hold the wallet monitor across activation and signing so a concurrent readiness refresh
        // cannot replace a revalidated historical target between these two operations.
        recordVerifiedSettlementTarget(chainId, vaultAddress, operatorAddress)
        return signSettlementTransaction(transaction, chainId, eip1559)
    }

    /** Unattended counterpart. Manual signing continues to use the auth-bound primary key. */
    @Synchronized
    internal fun activateAndSignAutomaticSettlementTransaction(
        transaction: RawTransaction,
        chainId: Long,
        vaultAddress: String,
        operatorAddress: String,
        eip1559: Boolean,
        invoiceIds: List<String>,
        expectedAmounts: List<BigInteger>,
        tokenAddress: String,
        maximumGasCost: BigInteger,
        requiredBalance: BigInteger,
    ): ByteArray {
        check(isDeviceCurrentlyUnlocked()) {
            "Device is locked; automatic settlement will retry after unlock"
        }
        val scope = UnattendedAutoSweepScope(
            chainId,
            vaultAddress,
            operatorAddress,
        ).canonical()
        val grant = unattendedAutoSweepGrant()
        requireExactUnattendedAutoSweepGrant(grant, scope)
        require(maximumGasCost.signum() > 0 && maximumGasCost <= AUTO_SWEEP_MAX_GAS_COST_WEI) {
            "Automatic settlement fee exceeds the unattended safety cap"
        }
        require(requiredBalance >= maximumGasCost && requiredBalance <= AUTO_SWEEP_MAX_REQUIRED_BALANCE_WEI) {
            "Automatic settlement reserve exceeds the unattended safety cap"
        }
        requireAuthorizedSweepSessionsCallData(
            callData = transaction.data,
            invoiceIds = invoiceIds,
            expectedAmounts = expectedAmounts,
            tokenAddress = tokenAddress,
        )
        val signedFeeQuote = if (eip1559) {
            val typed = transaction.transaction as? Transaction1559
                ?: throw IllegalArgumentException("Invalid type-2 transaction")
            SettlementFeeQuote(
                mode = SettlementFeeMode.EIP1559,
                maxPriorityFeePerGas = typed.maxPriorityFeePerGas,
                maxFeePerGas = typed.maxFeePerGas,
            )
        } else {
            SettlementFeeQuote(
                mode = SettlementFeeMode.LEGACY,
                gasPrice = transaction.gasPrice,
            )
        }
        val signedRequirement = SettlementBalancePolicy.requirement(
            transaction.gasLimit,
            signedFeeQuote,
        )
        require(signedRequirement.maximumGasCost == maximumGasCost &&
            signedRequirement.requiredBalance == requiredBalance
        ) { "Automatic settlement fee authorization does not match the transaction bytes" }
        recordVerifiedSettlementTarget(chainId, vaultAddress, operatorAddress)
        validateSettlementTransaction(transaction, chainId, eip1559)
        return signSettlementTransactionWith(
            transaction = transaction,
            chainId = chainId,
            eip1559 = eip1559,
            credentials = unattendedCredentials(grant.scopes),
        )
    }

    @Synchronized
    private fun signSettlementTransaction(
        transaction: RawTransaction,
        chainId: Long,
        eip1559: Boolean
    ): ByteArray {
        validateSettlementTransaction(transaction, chainId, eip1559)
        return withCredentials { credentials ->
            signSettlementTransactionWith(transaction, chainId, eip1559, credentials)
        }
    }

    private fun validateSettlementTransaction(
        transaction: RawTransaction,
        chainId: Long,
        eip1559: Boolean,
    ) {
        require(chainId > 0) { "A positive chain ID is required" }
        val target = EvmAddress.parse(transaction.to)
        require(!target.isZero) { "Settlement must target a non-zero vault" }
        requireVerifiedSettlementActivation(snapshot(), chainId, target)
        require(transaction.value == BigInteger.ZERO) { "Settlement cannot transfer native value" }
        requireSweepSessionsCallData(transaction.data)
        if (eip1559) {
            require(transaction.type == TransactionType.EIP1559) { "Expected a type-2 transaction" }
            val typed = transaction.transaction as? Transaction1559
                ?: throw IllegalArgumentException("Invalid type-2 transaction")
            require(typed.chainId == chainId) { "Type-2 transaction chain ID mismatch" }
        } else {
            require(transaction.type == TransactionType.LEGACY) { "Expected a legacy transaction" }
        }
    }

    private fun signSettlementTransactionWith(
        transaction: RawTransaction,
        chainId: Long,
        eip1559: Boolean,
        credentials: Credentials,
    ): ByteArray = try {
        if (eip1559) TransactionEncoder.signMessage(transaction, credentials)
        else TransactionEncoder.signMessage(transaction, chainId, credentials)
    } finally {
        // ECKeyPair uses immutable BigInteger material, but drop the sole Credentials reference as
        // soon as web3j returns. The decrypted byte source is separately zeroed by its caller.
    }

    @Synchronized
    private fun <T> withCredentials(block: (Credentials) -> T): T {
        val stored = snapshot()
        check(stored.availability == OperatorWalletAvailability.READY) {
            stored.error ?: "Create an operator wallet first"
        }
        check(preferences.getInt(KEY_FORMAT_VERSION, 0) == FORMAT_VERSION) {
            "Unsupported operator wallet format"
        }
        val ciphertext = Base64.decode(
            requireNotNull(preferences.getString(KEY_CIPHERTEXT, null)),
            Base64.NO_WRAP
        )
        val iv = Base64.decode(requireNotNull(preferences.getString(KEY_IV, null)), Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(AAD)
        val privateKeyBytes = cipher.doFinal(ciphertext)
        return try {
            check(privateKeyBytes.size == PRIVATE_KEY_BYTES) { "Invalid operator private-key length" }
            val credentials = Credentials.create(ECKeyPair.create(privateKeyBytes))
            val derived = Keys.toChecksumAddress(credentials.address)
            check(derived.equals(stored.address, ignoreCase = true)) {
                "Operator wallet address does not match the encrypted key"
            }
            block(credentials)
        } finally {
            privateKeyBytes.fill(0)
            ciphertext.fill(0)
            iv.fill(0)
        }
    }

    private fun decryptPrimaryPrivateKey(): ByteArray {
        val ciphertext = Base64.decode(
            requireNotNull(preferences.getString(KEY_CIPHERTEXT, null)),
            Base64.NO_WRAP,
        )
        val iv = Base64.decode(
            requireNotNull(preferences.getString(KEY_IV, null)),
            Base64.NO_WRAP,
        )
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(AAD)
            cipher.doFinal(ciphertext)
        } finally {
            ciphertext.fill(0)
            iv.fill(0)
        }
    }

    private fun unattendedCredentials(scopes: Set<UnattendedAutoSweepScope>): Credentials {
        val ciphertext = Base64.decode(
            requireNotNull(preferences.getString(KEY_AUTO_SWEEP_CIPHERTEXT, null)),
            Base64.NO_WRAP,
        )
        val iv = Base64.decode(
            requireNotNull(preferences.getString(KEY_AUTO_SWEEP_IV, null)),
            Base64.NO_WRAP,
        )
        val privateKeyBytes = try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                keyStore().getKey(AUTO_SWEEP_KEYSTORE_ALIAS, null) as SecretKey,
                GCMParameterSpec(GCM_TAG_BITS, iv),
            )
            cipher.updateAAD(autoSweepAad(scopes))
            cipher.doFinal(ciphertext)
        } finally {
            ciphertext.fill(0)
            iv.fill(0)
        }
        return try {
            check(privateKeyBytes.size == PRIVATE_KEY_BYTES) { "Invalid operator private-key length" }
            val credentials = Credentials.create(ECKeyPair.create(privateKeyBytes))
            check(Keys.toChecksumAddress(credentials.address).equals(snapshot().address, true)) {
                "Unattended auto-sweep key does not match the terminal operator"
            }
            credentials
        } finally {
            privateKeyBytes.fill(0)
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun wrappingKey(): SecretKey = keyStore().getKey(KEYSTORE_ALIAS, null) as? SecretKey
        ?: throw IllegalStateException("Operator wallet Keystore key is unavailable")

    private fun getOrCreateWrappingKey(): SecretKey {
        if (keyStore().containsAlias(KEYSTORE_ALIAS)) return wrappingKey()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                return generateWrappingKey(strongBox = true).also {
                    preferences.edit().putBoolean(KEY_STRONGBOX_REQUEST_SUCCEEDED, true).commit()
                }
            } catch (_: StrongBoxUnavailableException) {
                keyStore().deleteEntry(KEYSTORE_ALIAS)
            } catch (_: ProviderException) {
                // Some devices report unsupported StrongBox algorithms as ProviderException.
                keyStore().deleteEntry(KEYSTORE_ALIAS)
            }
        }
        return generateWrappingKey(strongBox = false).also {
            preferences.edit().putBoolean(KEY_STRONGBOX_REQUEST_SUCCEEDED, false).commit()
        }
    }

    private fun generateWrappingKey(strongBox: Boolean): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val builder = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(
                AUTH_WINDOW_SECONDS,
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
            )
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(AUTH_WINDOW_SECONDS)
        }
        if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setIsStrongBoxBacked(true)
        }
        generator.init(builder.build())
        return generator.generateKey()
    }

    private fun generateUnattendedWrappingKey(): SecretKey {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            throw IllegalStateException("Unattended auto-sweep requires Android 9 or newer")
        }
        return generateUnattendedWrappingKeyApi28()
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun generateUnattendedWrappingKeyApi28(): SecretKey {
        try {
            return generateUnattendedWrappingKey(strongBox = true)
        } catch (_: StrongBoxUnavailableException) {
            keyStore().deleteEntry(AUTO_SWEEP_KEYSTORE_ALIAS)
        } catch (_: ProviderException) {
            keyStore().deleteEntry(AUTO_SWEEP_KEYSTORE_ALIAS)
        }
        return generateUnattendedWrappingKey(strongBox = false)
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun generateUnattendedWrappingKey(strongBox: Boolean): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val builder = KeyGenParameterSpec.Builder(
            AUTO_SWEEP_KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false)
        builder.setUnlockedDeviceRequired(true)
        if (strongBox) builder.setIsStrongBoxBacked(true)
        generator.init(builder.build())
        return generator.generateKey()
    }

    private fun isDeviceCurrentlyUnlocked(): Boolean {
        val userManager = applicationContext.getSystemService(UserManager::class.java)
        val keyguardManager = applicationContext.getSystemService(KeyguardManager::class.java)
        return userManager?.isUserUnlocked == true &&
            keyguardManager?.isDeviceSecure == true &&
            keyguardManager.isDeviceLocked == false
    }

    private fun canonicalGrantScopes(
        scopes: Set<UnattendedAutoSweepScope>,
    ): Set<UnattendedAutoSweepScope> = scopes.mapTo(linkedSetOf()) { it.canonical() }
        .also { require(it.isNotEmpty()) { "At least one auto-sweep target is required" } }

    private fun autoSweepAad(scopes: Set<UnattendedAutoSweepScope>): ByteArray =
        ("OPK_UNATTENDED_AUTO_SWEEP_V1\n" + scopes.map { it.encoded() }.sorted().joinToString("\n"))
            .toByteArray(Charsets.UTF_8)

    private data class WrappingKeyProtection(
        val hardwareBacked: Boolean,
        val strongBoxBacked: Boolean,
        val deviceAuthenticationRequired: Boolean
    )

    private fun wrappingKeyProtection(): WrappingKeyProtection {
        val key = wrappingKey()
        val factory = SecretKeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE)
        val info = factory.getKeySpec(key, KeyInfo::class.java) as KeyInfo
        val strongBoxRequested = preferences.getBoolean(KEY_STRONGBOX_REQUEST_SUCCEEDED, false)
        return WrappingKeyProtection(
            hardwareBacked = info.isInsideSecureHardware(),
            strongBoxBacked = strongBoxRequested && info.isInsideSecureHardware(),
            deviceAuthenticationRequired = info.isUserAuthenticationRequired()
        )
    }

    companion object {
        private const val PREFS_NAME = "opk_operator_wallet_v1"
        private const val KEY_FORMAT_VERSION = "format_version"
        private const val KEY_ADDRESS = "operator_address"
        private const val KEY_CIPHERTEXT = "encrypted_private_key"
        private const val KEY_IV = "encryption_iv"
        private const val KEY_ACTIVATED_CHAIN_ID = "activated_chain_id"
        private const val KEY_ACTIVATED_VAULT = "activated_vault"
        private const val KEY_ACTIVATED_OPERATOR = "activated_operator"
        private const val KEY_STRONGBOX_REQUEST_SUCCEEDED = "strongbox_request_succeeded"
        private const val KEY_AUTO_SWEEP_FORMAT_VERSION = "auto_sweep_grant_format_version"
        private const val KEY_AUTO_SWEEP_SCOPES = "auto_sweep_grant_scopes"
        private const val KEY_AUTO_SWEEP_CIPHERTEXT = "auto_sweep_encrypted_private_key"
        private const val KEY_AUTO_SWEEP_IV = "auto_sweep_encrypted_private_key_iv"
        private const val KEYSTORE_ALIAS = "opk_operator_wallet_wrapping_key_v1"
        private const val AUTO_SWEEP_KEYSTORE_ALIAS = "opk_operator_wallet_auto_sweep_key_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val FORMAT_VERSION = 1
        private const val AUTO_SWEEP_FORMAT_VERSION = 1
        private const val PRIVATE_KEY_BYTES = 32
        private const val GCM_TAG_BITS = 128
        private const val AUTH_WINDOW_SECONDS = 30
        private val AAD = "OPK_OPERATOR_WALLET_V1".toByteArray(Charsets.UTF_8)
        val AUTO_SWEEP_MAX_GAS_COST_WEI: BigInteger = BigInteger("10000000000000000")
        val AUTO_SWEEP_MAX_REQUIRED_BALANCE_WEI: BigInteger = BigInteger("15000000000000000")
    }
}

internal fun requireVerifiedSettlementActivation(
    wallet: OperatorWalletSnapshot,
    chainId: Long,
    target: EvmAddress,
) {
    check(wallet.availability == OperatorWalletAvailability.READY && wallet.address != null) {
        wallet.error ?: "Create an operator wallet first"
    }
    require(wallet.activatedChainId == chainId) {
        "Settlement chain is not the operator wallet's verified active chain"
    }
    require(wallet.activatedVaultAddress?.equals(target.value, ignoreCase = true) == true) {
        "Settlement target is not the operator wallet's verified active vault"
    }
    require(wallet.activatedOperatorAddress?.equals(wallet.address, ignoreCase = true) == true) {
        "Settlement activation is not bound to this operator wallet"
    }
}

// Selector bytes for sweepSessions(bytes32[],uint256[],address). The guard must validate the
// exact bytes web3j will sign, mirroring its decode semantics precisely:
//  - Numeric.hexStringToByteArray left-pads odd-length hex, so "682b11b5f" signs as 0x0682b11b….
//  - Numeric.cleanHexPrefix strips only a lowercase "0x"; an uppercase "0X" prefix survives into
//    the signed bytes as 0xff, so "0X682B11B5…" signs selector 0xff682b11. Case-normalizing
//    before prefix handling would therefore validate a different string than the one signed.
// Hence: strip the prefix exactly as web3j does, then accept only unambiguous ASCII hex.
private val SWEEP_SESSIONS_SELECTOR = byteArrayOf(0x68, 0x2b, 0x11, 0xb5.toByte())

internal fun requireSweepSessionsCallData(callData: String?) {
    val normalized = Numeric.cleanHexPrefix(callData.orEmpty())
    require(
        normalized.length >= SWEEP_SESSIONS_SELECTOR.size * 2 &&
            normalized.length % 2 == 0 &&
            normalized.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' },
    ) { "Operator key only signs sweepSessions calls" }
    val selector = Numeric.hexStringToByteArray(normalized).copyOfRange(0, SWEEP_SESSIONS_SELECTOR.size)
    require(selector.contentEquals(SWEEP_SESSIONS_SELECTOR)) {
        "Operator key only signs sweepSessions calls"
    }
}

/** Requires the full canonical ABI payload, not merely the four-byte selector. */
internal fun requireAuthorizedSweepSessionsCallData(
    callData: String?,
    invoiceIds: List<String>,
    expectedAmounts: List<BigInteger>,
    tokenAddress: String,
) {
    requireSweepSessionsCallData(callData)
    require(invoiceIds.size == expectedAmounts.size && invoiceIds.isNotEmpty()) {
        "Automatic settlement invoice authorization is incomplete"
    }
    val canonical = com.openpasskey.terminal.settlement.SettlementAbi.encodeSweepSessions(
        invoiceIds.indices.map { index ->
            com.openpasskey.terminal.settlement.SettlementInvoiceIntent(
                invoiceId = invoiceIds[index],
                receiver = "0x0000000000000000000000000000000000000000",
                expectedAmount = expectedAmounts[index],
            )
        },
        tokenAddress,
    )
    require(
        Numeric.hexStringToByteArray(callData)
            .contentEquals(Numeric.hexStringToByteArray(canonical)),
    ) {
        "Automatic settlement call data does not match the canonical confirmed invoices"
    }
}
