package com.openpasskey.terminal.data.repository

import com.openpasskey.terminal.wallet.OperatorWalletAvailability
import com.openpasskey.terminal.wallet.OperatorWalletSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InvoiceIdentifierPolicyTest {
    @Test
    fun readyOperatorAddressIsTheInvoiceIdentifier() {
        val identifier = requireOperatorInvoiceIdentifier(
            OperatorWalletSnapshot(
                availability = OperatorWalletAvailability.READY,
                address = OPERATOR_ADDRESS
            )
        )

        assertEquals(OPERATOR_ADDRESS.lowercase(), identifier.value.lowercase())
    }

    @Test
    fun missingOperatorFailsClosed() {
        val error = assertThrows(IllegalStateException::class.java) {
            requireOperatorInvoiceIdentifier(
                OperatorWalletSnapshot(OperatorWalletAvailability.NOT_CREATED)
            )
        }

        assertTrue(error.message.orEmpty().contains("Create the terminal operator wallet"))
    }

    @Test
    fun unavailableOperatorPropagatesStorageFailure() {
        val error = assertThrows(IllegalStateException::class.java) {
            requireOperatorInvoiceIdentifier(
                OperatorWalletSnapshot(
                    availability = OperatorWalletAvailability.UNAVAILABLE,
                    address = OPERATOR_ADDRESS,
                    error = "Keystore key is unavailable"
                )
            )
        }

        assertEquals("Keystore key is unavailable", error.message)
    }

    @Test
    fun readySnapshotWithoutAddressFailsClosed() {
        assertThrows(IllegalStateException::class.java) {
            requireOperatorInvoiceIdentifier(
                OperatorWalletSnapshot(OperatorWalletAvailability.READY)
            )
        }
    }

    private companion object {
        const val OPERATOR_ADDRESS = "0x1111111111111111111111111111111111111111"
    }
}
