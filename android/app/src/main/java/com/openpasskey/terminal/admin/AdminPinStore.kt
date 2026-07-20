package com.openpasskey.terminal.admin

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

enum class AdminPinVerification {
    ACCEPTED,
    REJECTED,
    LOCKED,
    NOT_CONFIGURED,
}

data class AdminPinSnapshot(
    val configured: Boolean,
    val retryAfterSeconds: Long = 0,
)

data class AdminPinFailureState(
    val failedAttempts: Int,
    val lockUntilMillis: Long,
    val result: AdminPinVerification,
)

class AdminPinThrottle(
    private val maximumAttempts: Int = 5,
    private val lockMillis: Long = 30_000L,
) {
    init {
        require(maximumAttempts > 0 && lockMillis > 0)
    }

    fun recordFailure(currentFailures: Int, nowMillis: Long): AdminPinFailureState {
        require(currentFailures >= 0)
        val failures = currentFailures + 1
        return if (failures >= maximumAttempts) {
            AdminPinFailureState(0, nowMillis + lockMillis, AdminPinVerification.LOCKED)
        } else {
            AdminPinFailureState(failures, 0, AdminPinVerification.REJECTED)
        }
    }
}

class AdminPinHasher(
    private val iterations: Int = DEFAULT_ITERATIONS,
) {
    init {
        require(iterations > 0)
    }

    fun derive(pin: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin, salt, iterations, DERIVED_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    companion object {
        private const val DEFAULT_ITERATIONS = 210_000
        private const val DERIVED_BITS = 256
    }
}

/** Local-only admin gate. It stores a salted slow verifier, never a PIN or merchant credential. */
class AdminPinStore(
    context: Context,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val hasher: AdminPinHasher = AdminPinHasher(),
    private val throttle: AdminPinThrottle = AdminPinThrottle(),
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val random = SecureRandom()

    @Synchronized
    fun snapshot(): AdminPinSnapshot {
        val configured = prefs.contains(KEY_SALT) && prefs.contains(KEY_VERIFIER)
        val remaining = (prefs.getLong(KEY_LOCK_UNTIL, 0) - nowMillis()).coerceAtLeast(0)
        return AdminPinSnapshot(configured, (remaining + 999) / 1_000)
    }

    @Synchronized
    fun setInitialPin(pin: String) {
        check(!snapshot().configured) { "An admin PIN is already configured" }
        require(isValidPin(pin)) { "Admin PIN must be exactly 6 digits" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val chars = pin.toCharArray()
        val verifier = try {
            hasher.derive(chars, salt)
        } finally {
            chars.fill('\u0000')
        }
        try {
            check(
                prefs.edit()
                    .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                    .putString(KEY_VERIFIER, Base64.encodeToString(verifier, Base64.NO_WRAP))
                    .remove(KEY_FAILURES)
                    .remove(KEY_LOCK_UNTIL)
                    .commit(),
            ) { "Unable to save the admin PIN verifier" }
        } finally {
            salt.fill(0)
            verifier.fill(0)
        }
    }

    @Synchronized
    fun verify(pin: String): AdminPinVerification {
        val state = snapshot()
        if (!state.configured) return AdminPinVerification.NOT_CONFIGURED
        if (state.retryAfterSeconds > 0) return AdminPinVerification.LOCKED
        if (!isValidPin(pin)) return recordFailure()

        val salt = Base64.decode(requireNotNull(prefs.getString(KEY_SALT, null)), Base64.NO_WRAP)
        val stored = Base64.decode(requireNotNull(prefs.getString(KEY_VERIFIER, null)), Base64.NO_WRAP)
        val chars = pin.toCharArray()
        val actual = try {
            hasher.derive(chars, salt)
        } finally {
            chars.fill('\u0000')
        }
        return try {
            if (MessageDigest.isEqual(stored, actual)) {
                check(prefs.edit().remove(KEY_FAILURES).remove(KEY_LOCK_UNTIL).commit()) {
                    "Unable to update the admin PIN attempt state"
                }
                AdminPinVerification.ACCEPTED
            } else {
                recordFailure()
            }
        } finally {
            salt.fill(0)
            stored.fill(0)
            actual.fill(0)
        }
    }

    private fun recordFailure(): AdminPinVerification {
        val next = throttle.recordFailure(prefs.getInt(KEY_FAILURES, 0), nowMillis())
        check(
            prefs.edit()
                .putInt(KEY_FAILURES, next.failedAttempts)
                .putLong(KEY_LOCK_UNTIL, next.lockUntilMillis)
                .commit(),
        ) { "Unable to persist the admin PIN attempt state" }
        return next.result
    }

    companion object {
        private const val PREFS_NAME = "opk_local_admin_pin_v1"
        private const val KEY_SALT = "salt"
        private const val KEY_VERIFIER = "verifier"
        private const val KEY_FAILURES = "failures"
        private const val KEY_LOCK_UNTIL = "lock_until"
        private const val SALT_BYTES = 16
        val PIN_PATTERN = Regex("^[0-9]{6}$")

        fun isValidPin(pin: String): Boolean = PIN_PATTERN.matches(pin)
    }
}
