package com.openpasskey.terminal.provisioning

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class KnownChainPolicyTest {
    @Test
    fun baseSepoliaDefaultsToOneConfirmationAndAllowsMerchantIncreases() {
        val profile = KnownChainPolicy.requireProfile(84532)

        assertEquals(1, profile.minimumConfirmationBlocks)
        assertEquals(1, profile.defaultConfirmationBlocks)
    }

    @Test
    fun enabledProfilesMatchSharedConformanceRegistryAndEveryCreate2Component() {
        val registry = JsonParser.parseString(conformanceFile().readText()).asJsonObject
        assertEquals(1, registry.get("schemaVersion").asInt)
        val networks = registry.getAsJsonArray("networks").associateBy {
            it.asJsonObject.get("chainId").asLong
        }
        assertEquals(networks.keys, KnownChainPolicy.enabledChainIds())

        KnownChainPolicy.enabledProfiles().forEach { actual ->
            val expectedElement = networks[actual.chainId]
            assertNotNull(expectedElement)
            val expected = requireNotNull(expectedElement).asJsonObject
            assertEquals(expected.get("networkName").asString, actual.networkName)
            assertEquals(expected.get("isTestnet").asBoolean, actual.isTestnet)
            assertEquals(expected.get("nativeCurrencySymbol").asString, actual.nativeCurrencySymbol)
            assertEquals(expected.get("nativeCurrencyDecimals").asInt, actual.nativeCurrencyDecimals)
            assertEquals(
                expected.get("minimumConfirmationBlocks").asInt,
                actual.minimumConfirmationBlocks,
            )
            assertEquals(
                expected.get("defaultConfirmationBlocks").asInt,
                actual.defaultConfirmationBlocks,
            )
            assertEquals(
                expected.get("minimumOperatorNativeReserveWei").asString,
                actual.minimumOperatorNativeReserve.toString(),
            )
            assertEquals(expected.get("rpcUrl").asString, actual.rpcUrl)
            assertEquals(expected.get("protocolVersion").asString, actual.protocolVersion)
            assertEquals(expected.get("factory").asString, actual.factory.value)
            assertEquals(
                expected.get("receiverImplementation").asString,
                actual.receiverImplementation.value,
            )
            assertEquals(expected.get("vaultRuntimeCodeHash").asString, actual.vaultRuntimeCodeHash)

            val vector = expected.getAsJsonObject("create2TestVector")
            assertEquals(vector.get("vault").asString, actual.fixtureVault.value)
            assertEquals(vector.get("invoiceId").asString, actual.fixtureInvoiceId.hex)
            assertEquals(vector.get("salt").asString, actual.fixtureSalt)
            assertEquals(vector.get("initCodeHash").asString, actual.fixtureInitCodeHash)
            assertEquals(vector.get("expectedReceiver").asString, actual.fixtureReceiver.value)
            actual.requireValidCreate2Fixture()
        }
        assertThrows(IllegalArgumentException::class.java) {
            KnownChainPolicy.requireProfile(8453L)
        }
    }

    private fun conformanceFile(): File {
        val start = File(checkNotNull(System.getProperty("user.dir"))).absoluteFile
        return generateSequence(start) { directory -> directory.parentFile }
            .map { directory -> File(directory, "conformance/opk-terminal-networks-v1.json") }
            .firstOrNull(File::isFile)
            ?: error("Unable to locate conformance/opk-terminal-networks-v1.json from $start")
    }
}
