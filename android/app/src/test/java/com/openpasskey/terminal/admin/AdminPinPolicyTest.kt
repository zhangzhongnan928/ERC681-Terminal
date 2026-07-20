package com.openpasskey.terminal.admin

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdminPinPolicyTest {
    @Test
    fun pinMustBeExactlySixAsciiDigits() {
        assertTrue(AdminPinStore.isValidPin("123456"))
        listOf("", "12345", "1234567", "12345a", " 123456", "１２３４５６").forEach {
            assertFalse(it, AdminPinStore.isValidPin(it))
        }
    }

    @Test
    fun saltedSlowVerifierIsDeterministicOnlyForSamePinAndSalt() {
        val hasher = AdminPinHasher(iterations = 20)
        val salt = ByteArray(16) { it.toByte() }
        val first = hasher.derive("123456".toCharArray(), salt)
        val same = hasher.derive("123456".toCharArray(), salt)
        val otherPin = hasher.derive("654321".toCharArray(), salt)
        val otherSalt = hasher.derive("123456".toCharArray(), salt.reversedArray())
        assertArrayEquals(first, same)
        assertNotEquals(first.toList(), otherPin.toList())
        assertNotEquals(first.toList(), otherSalt.toList())
    }

    @Test
    fun fifthFailureLocksForThirtySecondsAndResetsCounter() {
        val throttle = AdminPinThrottle(maximumAttempts = 5, lockMillis = 30_000)
        var failures = 0
        repeat(4) {
            val next = throttle.recordFailure(failures, 1_000)
            assertEquals(AdminPinVerification.REJECTED, next.result)
            failures = next.failedAttempts
        }
        val locked = throttle.recordFailure(failures, 1_000)
        assertEquals(AdminPinVerification.LOCKED, locked.result)
        assertEquals(0, locked.failedAttempts)
        assertEquals(31_000, locked.lockUntilMillis)
    }
}
