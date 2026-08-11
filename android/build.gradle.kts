import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}

val approvedIminPrinterCoordinate =
    "com.github.iminsoftware:IminPrinterLibrary:V2.0.0.18"
val approvedIminPrinterSha256 =
    "8efa28e31c6e03ad9b460ecfa36d30471b4ded7f7a3ee4b7ed22e369afb14071"
val configuredIminPrinter = libs.imin.printer.get()
val configuredIminPrinterCoordinate = listOf(
    configuredIminPrinter.module.group,
    configuredIminPrinter.module.name,
    configuredIminPrinter.versionConstraint.requiredVersion,
).joinToString(":")

val iminPrinterArtifactVerification by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

dependencies {
    add(iminPrinterArtifactVerification.name, configuredIminPrinter)
}

val verifyIminPrinterArtifact = tasks.register("verifyIminPrinterArtifact") {
    group = "verification"
    description = "Verifies the pinned iMin printer AAR against its approved SHA-256 digest."

    doLast {
        check(configuredIminPrinterCoordinate == approvedIminPrinterCoordinate) {
            "The iMin printer dependency changed from the approved coordinate " +
                "$approvedIminPrinterCoordinate to $configuredIminPrinterCoordinate. " +
                "Review the upstream artifact before updating this verification gate."
        }

        val artifacts = iminPrinterArtifactVerification.resolvedConfiguration.resolvedArtifacts
        check(artifacts.size == 1) {
            "Expected one non-transitive iMin printer artifact, resolved ${artifacts.size}."
        }
        val artifact = artifacts.single()
        val resolvedCoordinate = artifact.moduleVersion.id.toString()
        check(resolvedCoordinate == approvedIminPrinterCoordinate) {
            "Resolved unexpected iMin printer coordinate $resolvedCoordinate."
        }
        check(artifact.extension == "aar") {
            "Expected the iMin printer dependency to resolve as an AAR, got ${artifact.extension}."
        }

        val digest = MessageDigest.getInstance("SHA-256")
        artifact.file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                digest.update(buffer, 0, count)
            }
        }
        val actualSha256 = digest.digest().joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
        check(actualSha256 == approvedIminPrinterSha256) {
            "iMin printer artifact checksum mismatch for $approvedIminPrinterCoordinate: " +
                "expected $approvedIminPrinterSha256, got $actualSha256."
        }

        logger.lifecycle(
            "Verified {} with SHA-256 {}",
            approvedIminPrinterCoordinate,
            actualSha256,
        )
    }
}

subprojects {
    pluginManager.withPlugin("com.android.application") {
        tasks.named("preBuild").configure {
            dependsOn(verifyIminPrinterArtifact)
        }
    }
}
