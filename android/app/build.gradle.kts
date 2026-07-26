import java.io.File
import java.util.Properties
import org.gradle.api.GradleException

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

fun Properties.requiredSigningValue(name: String): String =
    getProperty(name)?.takeIf { it.isNotEmpty() }
        ?: throw GradleException(
            "android/key.properties is missing required key '$name'."
        )

val playSigningFlag = providers.gradleProperty("opkPlaySigning").orNull
val playSigningRequested = when (playSigningFlag) {
    null -> false
    "true" -> true
    else -> throw GradleException(
        "-PopkPlaySigning must be exactly 'true' when provided."
    )
}

val playSigningProperties: Properties? = if (playSigningRequested) {
    val propertiesFile = rootProject.file("key.properties")
    if (!propertiesFile.isFile) {
        throw GradleException(
            "Signed Google Play build requested, but android/key.properties is missing."
        )
    }

    Properties().apply {
        propertiesFile.inputStream().use { input -> load(input) }
    }
} else {
    null
}

val playKeystoreFile: File? = playSigningProperties?.let { properties ->
    val configured = File(properties.requiredSigningValue("storeFile"))
    if (!configured.isAbsolute) {
        throw GradleException(
            "storeFile in android/key.properties must be an absolute path."
        )
    }

    val resolved = configured.canonicalFile
    if (!resolved.isFile || !resolved.canRead()) {
        throw GradleException(
            "storeFile must point to a readable external keystore file."
        )
    }

    val repositoryRoot =
        rootProject.projectDir.parentFile.canonicalFile.toPath()
    if (resolved.toPath().startsWith(repositoryRoot)) {
        throw GradleException(
            "The Google Play upload keystore must be stored outside this repository."
        )
    }

    resolved
}

val playSigningPassword: String? = playSigningProperties?.let { properties ->
    val configured = File(properties.requiredSigningValue("passwordFile"))
    if (!configured.isAbsolute) {
        throw GradleException(
            "passwordFile in android/key.properties must be an absolute path."
        )
    }

    val resolved = configured.canonicalFile
    if (!resolved.isFile || !resolved.canRead()) {
        throw GradleException(
            "passwordFile must point to a readable external file."
        )
    }

    val repositoryRoot =
        rootProject.projectDir.parentFile.canonicalFile.toPath()
    if (resolved.toPath().startsWith(repositoryRoot)) {
        throw GradleException(
            "The Google Play upload password file must be stored outside this repository."
        )
    }

    resolved.readText()
        .trimEnd('\r', '\n')
        .takeIf { it.isNotEmpty() }
        ?: throw GradleException("passwordFile must not be empty.")
}

android {
    namespace = "com.openpasskey.terminal"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.openpasskey.terminal"
        minSdk = 26
        targetSdk = 35
        versionCode = 14
        versionName = "0.1.12"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (playSigningRequested) {
            create("playUpload") {
                val properties = requireNotNull(playSigningProperties)
                val configuredStoreType =
                    properties.requiredSigningValue("storeType").uppercase()
                if (configuredStoreType !in setOf("JKS", "PKCS12")) {
                    throw GradleException("storeType must be JKS or PKCS12.")
                }

                storeFile = requireNotNull(playKeystoreFile)
                storeType = configuredStoreType
                storePassword = requireNotNull(playSigningPassword)
                keyAlias =
                    properties.requiredSigningValue("keyAlias")
                keyPassword = requireNotNull(playSigningPassword)
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (playSigningRequested) {
                signingConfig = signingConfigs.getByName("playUpload")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    sourceSets.getByName("test").resources.srcDir("../../conformance")
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DISCLAIMER"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/*.SF"
            excludes += "/META-INF/*.DSA"
            excludes += "/META-INF/*.RSA"
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":erc681-sdk"))

    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.biometric)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // QR display plus configuration-only address import. Scanned content never initiates payment.
    implementation(libs.zxing.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Local non-secret settings serialization
    implementation(libs.gson)

    // App-layer operator wallet and settlement transaction signing. The reusable SDK remains read-only.
    implementation(libs.web3j.core)

    testImplementation(libs.junit)
    testImplementation("org.mockito:mockito-core:5.12.0")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation(libs.androidx.room.testing)
}
