package com.openpasskey.terminal.provisioning

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TerminalProvisioningPayloadCodecTest {
    private val fixture by lazy {
        val text = checkNotNull(
            javaClass.getResourceAsStream("/opk-terminal-provisioning-v1.json"),
        ) { "Missing shared provisioning fixture" }.bufferedReader().use { it.readText() }
        JsonParser.parseString(text).asJsonObject
    }

    @Test
    fun operatorPairingUsesCanonicalSharedFormatAndNormalizesMixedCase() {
        val pairing = fixture.getAsJsonObject("operatorPairing")
        assertEquals(
            pairing["canonical"].asString,
            TerminalProvisioningPayloadCodec.encodeOperatorPairing(pairing["address"].asString),
        )
        pairing.getAsJsonArray("mustAccept").forEach { value ->
            val parsed = TerminalProvisioningPayloadCodec.parseOperatorPairing(value.asString)
            assertEquals(parsed.value, TerminalProvisioningPayloadCodec.parseOperatorPairing(
                TerminalProvisioningPayloadCodec.encodeOperatorPairing(parsed.value),
            ).value)
        }
        pairing.getAsJsonArray("mustReject").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                TerminalProvisioningPayloadCodec.parseOperatorPairing(value.asString)
            }
        }
    }

    @Test
    fun provisioningParserConsumesEverySharedAcceptanceAndRejectionVector() {
        val provisioning = fixture.getAsJsonObject("provisioning")
        val expected = TerminalProvisioningPayloadCodec.parse(provisioning["canonical"].asString)
        assertEquals(provisioning["chainId"].asLong, expected.chainId)
        assertEquals(provisioning["vault"].asString, expected.vault.value)
        assertEquals(provisioning["token"].asString, expected.token.value)
        assertEquals(provisioning["operator"].asString, expected.operator.value)
        assertEquals(
            provisioning["canonical"].asString,
            TerminalProvisioningPayloadCodec.encodeProvisioning(
                expected.chainId,
                expected.vault.value,
                expected.token.value,
                expected.operator.value,
            ),
        )

        provisioning.getAsJsonArray("mustAccept").forEach { value ->
            val parsed = TerminalProvisioningPayloadCodec.parse(value.asString)
            assertEquals(expected.chainId, parsed.chainId)
            assertEquals(expected.vault, parsed.vault)
            assertEquals(expected.token, parsed.token)
        }
        provisioning.getAsJsonArray("mustReject").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                TerminalProvisioningPayloadCodec.parse(value.asString)
            }
        }
    }
}
