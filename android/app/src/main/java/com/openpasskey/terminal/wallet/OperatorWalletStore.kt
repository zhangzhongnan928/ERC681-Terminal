package com.openpasskey.terminal.wallet

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import com.openpasskey.erc681.EvmAddress
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
    val hardwareBacked: Boolean = false,
    val strongBoxBacked: Boolean = false,
    val deviceAuthenticationRequired: Boolean = false
)

/**
 * Stores the secp256k1 terminal identity and settlement-operator key. Existing random identifiers
 * from older app versions are never parsed, imported, or reinterpreted as wallet keys.
 *
 * Android Keystore protects the AES wrapping key. The Ethereum key is decrypted only for the
 * shortest practical signing window, but Android Keystore cannot itself sign secp256k1.
 */
class OperatorWalletStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

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
    fun recordVerifiedSettlementTarget(chainId: Long, vaultAddress: String) {
        check(snapshot().availability == OperatorWalletAvailability.READY)
        check(chainId > 0)
        val vault = com.openpasskey.erc681.EvmAddress.parse(vaultAddress).value
        check(preferences.edit()
            .putLong(KEY_ACTIVATED_CHAIN_ID, chainId)
            .putString(KEY_ACTIVATED_VAULT, vault)
            .commit()
        ) { "Unable to record the verified settlement target" }
    }

    /** Creates the operator wallet once. Rotation is intentionally not an in-app one-tap action. */
    @Synchronized
    fun createWallet(): OperatorWalletSnapshot {
        check(snapshot().availability == OperatorWalletAvailability.NOT_CREATED) {
            "An operator wallet record already exists. Revoke it on the vault before any replacement."
        }
        val keyPair = Keys.createEcKeyPair()
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
     * The only signing capability exported by the wallet store. It refuses native transfers,
     * contract creation, calls outside the verified active chain/vault, and every method except the
     * typed sweepSessions selector. Credentials never leave this class, so UI and ViewModels cannot
     * turn the terminal into a general-purpose signer.
     */
    @Synchronized
    internal fun signSettlementTransaction(
        transaction: RawTransaction,
        chainId: Long,
        eip1559: Boolean
    ): ByteArray {
        require(chainId > 0) { "A positive chain ID is required" }
        val target = EvmAddress.parse(transaction.to)
        require(!target.isZero) { "Settlement must target a non-zero vault" }
        val wallet = snapshot()
        check(wallet.availability == OperatorWalletAvailability.READY) {
            wallet.error ?: "Create an operator wallet first"
        }
        require(wallet.activatedChainId == chainId) {
            "Settlement chain is not the operator wallet's verified active chain"
        }
        require(wallet.activatedVaultAddress?.equals(target.value, ignoreCase = true) == true) {
            "Settlement target is not the operator wallet's verified active vault"
        }
        require(transaction.value == BigInteger.ZERO) { "Settlement cannot transfer native value" }
        require(transaction.data.lowercase().startsWith(SWEEP_SESSIONS_SELECTOR)) {
            "Operator key only signs sweepSessions calls"
        }
        if (eip1559) {
            require(transaction.type == TransactionType.EIP1559) { "Expected a type-2 transaction" }
            val typed = transaction.transaction as? Transaction1559
                ?: throw IllegalArgumentException("Invalid type-2 transaction")
            require(typed.chainId == chainId) { "Type-2 transaction chain ID mismatch" }
        } else {
            require(transaction.type == TransactionType.LEGACY) { "Expected a legacy transaction" }
        }
        return withCredentials { credentials ->
            if (eip1559) TransactionEncoder.signMessage(transaction, credentials)
            else TransactionEncoder.signMessage(transaction, chainId, credentials)
        }
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
        private const val KEY_STRONGBOX_REQUEST_SUCCEEDED = "strongbox_request_succeeded"
        private const val KEYSTORE_ALIAS = "opk_operator_wallet_wrapping_key_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val FORMAT_VERSION = 1
        private const val PRIVATE_KEY_BYTES = 32
        private const val GCM_TAG_BITS = 128
        private const val AUTH_WINDOW_SECONDS = 30
        private const val SWEEP_SESSIONS_SELECTOR = "0x682b11b5"
        private val AAD = "OPK_OPERATOR_WALLET_V1".toByteArray(Charsets.UTF_8)
    }
}
