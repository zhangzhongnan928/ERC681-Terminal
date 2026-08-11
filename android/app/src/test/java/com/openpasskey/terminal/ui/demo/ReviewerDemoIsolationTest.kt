package com.openpasskey.terminal.ui.demo

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewerDemoIsolationTest {
    @Test
    fun demoSourcesDoNotImportProductionStateOrSideEffectLayers() {
        val sources = listOf(
            sourceFile("ColdLaunchRoot.kt"),
            sourceFile("ReviewerDemoContract.kt"),
            sourceFile("ReviewerDemoScreen.kt"),
            productionSourceFile("ui/components/QRCodeView.kt"),
        )
        val forbiddenImports = listOf(
            "com.openpasskey.terminal.admin.",
            "com.openpasskey.terminal.data.",
            "com.openpasskey.terminal.lifecycle.",
            "com.openpasskey.terminal.provisioning.",
            "com.openpasskey.terminal.rpc.",
            "com.openpasskey.terminal.settlement.",
            "com.openpasskey.terminal.viewmodel.",
            "com.openpasskey.terminal.wallet.",
            "androidx.room.",
            "android.content.",
            "android.security.",
            "java.net.",
            "java.io.",
            "java.security.",
            "okhttp3.",
            "org.web3j.",
        )

        sources.forEach { source ->
            val importLines = source.readLines().filter { it.startsWith("import ") }
            forbiddenImports.forEach { forbidden ->
                assertFalse(
                    "${source.name} must not import $forbidden",
                    importLines.any { forbidden in it },
                )
            }
        }
    }

    @Test
    fun demoScreenAcceptsOnlyCloseAndKeepsStateInNonSaveableMemory() {
        val screen = sourceFile("ReviewerDemoScreen.kt").readText()

        assertTrue(
            screen.contains("internal fun ReviewerDemoScreen(onClose: () -> Unit)"),
        )
        assertTrue(screen.contains("remember { mutableStateOf(newReviewerDemoState()) }"))
        assertFalse(screen.contains("rememberSaveable"))
        assertFalse(screen.contains("ViewModel"))
        assertFalse(screen.contains("LocalContext"))
        assertFalse(screen.contains("LocalClipboardManager"))
        assertFalse(screen.contains("Camera"))
        assertFalse(screen.contains("DeviceAuthentication"))
        assertFalse(screen.contains("LocalUriHandler"))
        assertFalse(screen.contains("Intent"))
    }

    @Test
    fun demoIsAvailableOnlyFromTheColdLaunchBoundary() {
        val main = File("src/main/java/com/openpasskey/terminal/MainActivity.kt").readText()
        val navigation = File(
            "src/main/java/com/openpasskey/terminal/ui/navigation/NavGraph.kt",
        ).readText()
        val activityBody = main.substringAfter("class MainActivity")
        val productionProperties = listOf(
            "app.invoiceRepository",
            "app.settlementRepository",
            "app.chainConfig",
            "app.operatorWalletStore",
            "app.adminPinStore",
            "app.terminalProvisioner",
            "app.terminalResetCoordinator",
            "app.terminalLifecycleGate",
            "app.rpcWorkCoordinator",
            "app.receiptPrinter",
            "app.receiptCoordinator",
        )

        assertTrue(activityBody.contains("ColdLaunchRoot("))
        assertTrue(activityBody.contains("factory.create(this, app)"))
        productionProperties.forEach { property ->
            assertFalse(
                "MainActivity must access $property only inside the guarded live factory",
                activityBody.contains(property),
            )
        }
        assertFalse(navigation.contains("ReviewerDemo"))
        assertFalse(navigation.contains("reviewer-demo"))
        assertTrue(main.contains("ProcessLaunchMode.DEMO"))
        assertTrue(main.contains("ProcessLaunchMode.LIVE"))
    }

    private fun sourceFile(name: String): File {
        return productionSourceFile("ui/demo/$name")
    }

    private fun productionSourceFile(relativePath: String): File {
        val file = File("src/main/java/com/openpasskey/terminal/$relativePath")
        assertTrue("Missing demo source ${file.absolutePath}", file.isFile)
        return file
    }
}
