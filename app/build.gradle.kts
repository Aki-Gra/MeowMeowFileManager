// Release credentials are read from the environment. Never commit signing material.
val releaseKeystorePath = System.getenv("MEOWMEOW_SIGNING_KEYSTORE_PATH")
val releaseStorePassword = System.getenv("MEOWMEOW_SIGNING_STORE_PASSWORD")
val releaseKeyAlias = System.getenv("MEOWMEOW_SIGNING_KEY_ALIAS")
val releaseKeyPassword = System.getenv("MEOWMEOW_SIGNING_KEY_PASSWORD")
val hasReleaseSigning = listOf(
    releaseKeystorePath, releaseStorePassword, releaseKeyAlias, releaseKeyPassword
).all { !it.isNullOrBlank() }
val requestedReleaseBuild = gradle.startParameter.taskNames.any {
    it.substringAfterLast(':') in setOf("assembleRelease", "bundleRelease")
}
if (requestedReleaseBuild && !hasReleaseSigning) {
    throw GradleException(
        "Missing release signing environment variables. See docs/SIGNED_RELEASES.md."
    )
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.meowmeow.filemanager"
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }

    defaultConfig {
        applicationId = "com.meowmeow.filemanager"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("meowmeowRelease") {
                storeFile = file(requireNotNull(releaseKeystorePath))
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    buildTypes {
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("meowmeowRelease")
            }
        }
    }

    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("androidx.core:core:1.17.0")
    // Offline support for legacy Word .doc text extraction.
    implementation("org.apache.poi:poi-scratchpad:5.5.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:core:1.7.0")
}
