package com.openpasskey.terminal.rpc

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.openpasskey.terminal.provisioning.KnownChainPolicy
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The saved override's health, deliberately without its credential-bearing URL. */
enum class RpcEndpointOverrideState {
    NOT_CONFIGURED,
    READY,
    UNAVAILABLE,
}

/** The effective transport source, without exposing a credential-bearing endpoint. */
enum class RpcEndpointSource {
    ADMIN_OVERRIDE,
    BUILD_MANAGED,
    PUBLIC_FALLBACK,
    MISSING,
    UNAVAILABLE,
}

/** Redacted RPC settings metadata that is safe to expose to Compose state and diagnostics. */
data class RpcEndpointSnapshot(
    val chainId: Long,
    val state: RpcEndpointOverrideState,
    val providerLabel: String?,
    val source: RpcEndpointSource = defaultRpcEndpointSource(state, providerLabel),
) {
    val available: Boolean
        get() = source == RpcEndpointSource.ADMIN_OVERRIDE ||
            source == RpcEndpointSource.BUILD_MANAGED ||
            source == RpcEndpointSource.PUBLIC_FALLBACK
}

private fun defaultRpcEndpointSource(
    state: RpcEndpointOverrideState,
    providerLabel: String?,
): RpcEndpointSource = when (state) {
    RpcEndpointOverrideState.READY -> RpcEndpointSource.ADMIN_OVERRIDE
    RpcEndpointOverrideState.UNAVAILABLE -> RpcEndpointSource.UNAVAILABLE
    RpcEndpointOverrideState.NOT_CONFIGURED -> if (providerLabel == null) {
        RpcEndpointSource.PUBLIC_FALLBACK
    } else {
        RpcEndpointSource.BUILD_MANAGED
    }
}

/**
 * Read-only endpoint capability used by repositories and other runtime RPC consumers. Implementors
 * must never return a credential-bearing URL from [snapshot].
 */
interface RpcEndpointResolver {
    fun snapshot(chainId: Long): RpcEndpointSnapshot

    /** Resolves an endpoint for immediate use. Callers must never persist or log the result. */
    fun resolve(chainId: Long, fallbackUrl: String): String

    /**
     * Resolves an endpoint together with a process-local generation. Long-running readers use the
     * generation to reject results produced by an endpoint that an administrator replaced while
     * the read was in flight. The returned object deliberately has no credential-revealing
     * [toString] implementation and must never enter UI or persistence state.
     */
    fun resolveCurrent(chainId: Long, fallbackUrl: String): RpcEndpointResolution =
        RpcEndpointResolution(chainId, resolve(chainId, fallbackUrl), generation = 0)

    /** True when [resolution] still represents the active endpoint for its chain. */
    fun isCurrent(resolution: RpcEndpointResolution): Boolean = true

    companion object {
        /** Compatibility/test resolver for call sites that do not own secure endpoint storage. */
        val PASSTHROUGH: RpcEndpointResolver = object : RpcEndpointResolver {
            override fun snapshot(chainId: Long): RpcEndpointSnapshot = RpcEndpointSnapshot(
                chainId = chainId,
                state = RpcEndpointOverrideState.NOT_CONFIGURED,
                providerLabel = null,
            )

            override fun resolve(chainId: Long, fallbackUrl: String): String = fallbackUrl
        }
    }
}

class RpcEndpointResolution internal constructor(
    internal val chainId: Long,
    internal val endpoint: String,
    internal val generation: Long,
)

class RpcEndpointStorageException(message: String) : IllegalStateException(message)

class RpcEndpointNotConfiguredException : IllegalStateException(
    "Configure a dedicated HTTPS RPC endpoint in Admin/setup before using this Base network.",
)

/**
 * Per-chain admin RPC overrides encrypted with a non-exportable Android Keystore AES key.
 *
 * The wrapping key intentionally does not require interactive user authentication: invoice
 * monitoring and settlement recovery must continue while the admin screen is locked. The app's
 * backup and device-transfer rules exclude all shared preferences, and only AES-GCM ciphertext is
 * written here. Direct provider credentials still exist briefly in process memory and on the TLS
 * connection because a native terminal must present them to the provider.
 */
class RpcEndpointStore internal constructor(
    private val records: RpcEndpointRecordStorage,
    private val cipher: RpcEndpointCipher,
    buildManagedEndpoints: Map<Long, String> = emptyMap(),
    private val allowPublicFallback: Boolean = false,
) : RpcEndpointResolver {
    private val generations = mutableMapOf<Long, Long>()
    private val buildManagedEndpoints: Map<Long, String> = buildManagedEndpoints
        .mapValues { (chainId, endpoint) ->
            requireSupportedChain(chainId)
            validateRpcEndpoint(endpoint)
        }
        .toMap()

    constructor(
        context: Context,
        buildManagedEndpoints: Map<Long, String> = emptyMap(),
        allowPublicFallback: Boolean = false,
    ) : this(
        records = SharedPreferencesRpcEndpointStorage(
            context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
        ),
        cipher = AndroidKeystoreRpcEndpointCipher(),
        buildManagedEndpoints = buildManagedEndpoints,
        allowPublicFallback = allowPublicFallback,
    )

    @Synchronized
    override fun snapshot(chainId: Long): RpcEndpointSnapshot {
        requireSupportedChain(chainId)
        val record = records.read(chainId)
            ?: return RpcEndpointSnapshot(
                chainId = chainId,
                state = RpcEndpointOverrideState.NOT_CONFIGURED,
                providerLabel = buildManagedEndpoints[chainId]?.let(::safeProviderLabel),
                source = when {
                    buildManagedEndpoints.containsKey(chainId) -> RpcEndpointSource.BUILD_MANAGED
                    allowPublicFallback -> RpcEndpointSource.PUBLIC_FALLBACK
                    else -> RpcEndpointSource.MISSING
                },
            )
        val endpoint = runCatching {
            validateRpcEndpoint(cipher.decrypt(chainId, record))
        }.getOrNull()
            ?: return RpcEndpointSnapshot(
                chainId = chainId,
                state = RpcEndpointOverrideState.UNAVAILABLE,
                providerLabel = null,
                source = RpcEndpointSource.UNAVAILABLE,
            )
        return RpcEndpointSnapshot(
            chainId = chainId,
            state = RpcEndpointOverrideState.READY,
            providerLabel = safeProviderLabel(endpoint),
            source = RpcEndpointSource.ADMIN_OVERRIDE,
        )
    }

    @Synchronized
    override fun resolve(chainId: Long, fallbackUrl: String): String {
        requireSupportedChain(chainId)
        val record = records.read(chainId) ?: return buildManagedEndpoints[chainId]
            ?: if (allowPublicFallback) {
                validateRpcEndpoint(fallbackUrl)
            } else {
                throw RpcEndpointNotConfiguredException()
            }
        return try {
            validateRpcEndpoint(cipher.decrypt(chainId, record))
        } catch (_: Exception) {
            // Never attach the crypto/parser cause. Some platform exceptions include their input.
            throw RpcEndpointStorageException(
                "The saved RPC endpoint is unavailable. Unlock Admin/setup and replace or clear it.",
            )
        }
    }

    @Synchronized
    override fun resolveCurrent(chainId: Long, fallbackUrl: String): RpcEndpointResolution =
        RpcEndpointResolution(
            chainId = chainId,
            endpoint = resolve(chainId, fallbackUrl),
            generation = generations[chainId] ?: 0L,
        )

    @Synchronized
    override fun isCurrent(resolution: RpcEndpointResolution): Boolean =
        resolution.generation == (generations[resolution.chainId] ?: 0L)

    /**
     * Encrypts and replaces one admin override. The caller must independently prove eth_chainId
     * before invoking this method; local URL validation cannot prove what a remote server serves.
     */
    fun validateCandidate(rawUrl: String) {
        validateRpcEndpoint(rawUrl)
    }

    fun validateCandidate(chainId: Long, rawUrl: String) {
        requireSupportedChain(chainId)
        validateCandidate(rawUrl)
    }

    @Synchronized
    fun setOverride(chainId: Long, rawUrl: String): Boolean {
        requireSupportedChain(chainId)
        val endpoint = validateRpcEndpoint(rawUrl)
        val record = try {
            cipher.encrypt(chainId, endpoint)
        } catch (_: Exception) {
            throw RpcEndpointStorageException("Unable to protect the RPC endpoint on this device.")
        }
        return records.write(chainId, record).also { written ->
            if (written) incrementGeneration(chainId)
        }
    }

    @Synchronized
    fun clearOverride(chainId: Long): Boolean {
        requireSupportedChain(chainId)
        return records.remove(chainId).also { removed ->
            if (removed) incrementGeneration(chainId)
        }
    }

    private fun incrementGeneration(chainId: Long) {
        val current = generations[chainId] ?: 0L
        generations[chainId] = if (current == Long.MAX_VALUE) 0 else current + 1
    }

    companion object {
        const val MAX_RPC_URL_LENGTH = 8_192
        private const val PREFERENCES_NAME = "opk_rpc_endpoint_secrets_v1"
    }
}

/** Validates without returning, logging, or embedding the submitted endpoint in an error. */
internal fun validateRpcEndpoint(rawUrl: String): String {
    require(rawUrl.isNotBlank()) { "RPC URL is required." }
    require(rawUrl.length <= RpcEndpointStore.MAX_RPC_URL_LENGTH) {
        "RPC URL is too long."
    }
    require(rawUrl.none(Char::isISOControl)) { "RPC URL contains unsupported characters." }
    require(rawUrl == rawUrl.trim()) { "Remove spaces before or after the RPC URL." }
    val uri = try {
        URI(rawUrl)
    } catch (_: Exception) {
        throw IllegalArgumentException("RPC URL is invalid.")
    }
    require(!uri.isOpaque && uri.scheme.equals("https", ignoreCase = true)) {
        "RPC URL must use HTTPS."
    }
    require(!uri.host.isNullOrBlank()) { "RPC URL must include a host." }
    require(uri.userInfo == null) { "RPC URL must not use username/password credentials." }
    require(uri.fragment == null) { "RPC URL must not include a fragment." }
    require(uri.port == -1 || uri.port in 1..65_535) { "RPC URL port is invalid." }
    val canonical = uri.toASCIIString()
    require(canonical.length <= RpcEndpointStore.MAX_RPC_URL_LENGTH) {
        "RPC URL is too long."
    }
    return canonical
}

private fun requireSupportedChain(chainId: Long) {
    try {
        KnownChainPolicy.requireProfile(chainId)
    } catch (_: Exception) {
        throw IllegalArgumentException("RPC overrides are supported only for known terminal chains.")
    }
}

/** The exact hostname is intentionally not returned because some providers put keys in subdomains. */
private fun safeProviderLabel(endpoint: String): String {
    val host = requireNotNull(URI(endpoint).host).lowercase()
    return when {
        host == "api.developer.coinbase.com" -> "Coinbase CDP"
        host == "base-mainnet.g.alchemy.com" ||
            host == "base-sepolia.g.alchemy.com" -> "Alchemy"
        else -> "Custom HTTPS provider"
    }
}

internal interface RpcEndpointRecordStorage {
    fun read(chainId: Long): String?
    fun write(chainId: Long, record: String): Boolean
    fun remove(chainId: Long): Boolean
}

private class SharedPreferencesRpcEndpointStorage(
    private val preferences: SharedPreferences,
) : RpcEndpointRecordStorage {
    override fun read(chainId: Long): String? = preferences.getString(key(chainId), null)

    override fun write(chainId: Long, record: String): Boolean = preferences.edit()
        .putString(key(chainId), record)
        .commit()

    override fun remove(chainId: Long): Boolean = preferences.edit()
        .remove(key(chainId))
        .commit()

    private fun key(chainId: Long): String = "endpoint_$chainId"
}

internal interface RpcEndpointCipher {
    fun encrypt(chainId: Long, plaintext: String): String
    fun decrypt(chainId: Long, record: String): String
}

private class AndroidKeystoreRpcEndpointCipher : RpcEndpointCipher {
    @Synchronized
    override fun encrypt(chainId: Long, plaintext: String): String {
        val plaintextBytes = plaintext.toByteArray(Charsets.UTF_8)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateWrappingKey())
            cipher.updateAAD(aad(chainId))
            val ciphertext = cipher.doFinal(plaintextBytes)
            listOf(
                RECORD_VERSION,
                Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
                Base64.encodeToString(ciphertext, Base64.NO_WRAP),
            ).joinToString(RECORD_SEPARATOR)
        } finally {
            plaintextBytes.fill(0)
        }
    }

    @Synchronized
    override fun decrypt(chainId: Long, record: String): String {
        val parts = record.split(RECORD_SEPARATOR, limit = 3)
        require(parts.size == 3 && parts[0] == RECORD_VERSION) { "Invalid encrypted record" }
        val iv = Base64.decode(parts[1], Base64.NO_WRAP)
        val ciphertext = Base64.decode(parts[2], Base64.NO_WRAP)
        require(iv.size == GCM_IV_BYTES && ciphertext.size >= GCM_TAG_BYTES) {
            "Invalid encrypted record"
        }
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                wrappingKey(),
                GCMParameterSpec(GCM_TAG_BITS, iv),
            )
            cipher.updateAAD(aad(chainId))
            val plaintext = cipher.doFinal(ciphertext)
            try {
                plaintext.toString(Charsets.UTF_8)
            } finally {
                plaintext.fill(0)
            }
        } finally {
            iv.fill(0)
            ciphertext.fill(0)
        }
    }

    private fun aad(chainId: Long): ByteArray =
        "OPK_RPC_ENDPOINT_V1:$chainId".toByteArray(Charsets.UTF_8)

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun wrappingKey(): SecretKey = keyStore().getKey(KEYSTORE_ALIAS, null) as? SecretKey
        ?: throw IllegalStateException("RPC endpoint Keystore key is unavailable")

    private fun getOrCreateWrappingKey(): SecretKey {
        if (keyStore().containsAlias(KEYSTORE_ALIAS)) return wrappingKey()
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                // Background payment monitoring must not depend on an interactive auth prompt.
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE_ALIAS = "opk_rpc_endpoint_wrapping_key_v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val RECORD_VERSION = "v1"
        const val RECORD_SEPARATOR = ":"
        const val GCM_TAG_BITS = 128
        const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
        const val GCM_IV_BYTES = 12
    }
}
