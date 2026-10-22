import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
// Stable signing key: taken from environment variables (CI) or git-ignored local.properties (local builds).
// Without it, builds fall back to the default debug key, which cannot update an APK signed with the stable key.
val relayLocalProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun relaySigningValue(key: String): String? =
    System.getenv(key)?.takeIf { it.isNotBlank() } ?: relayLocalProps.getProperty(key)?.takeIf { it.isNotBlank() }
val relayKeystoreFile = relaySigningValue("RELAY_KEYSTORE_FILE")?.let { rootProject.file(it) }?.takeIf { it.exists() }

android {
    namespace = "com.nuvio.tv.relay.standalone"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.nuvio.tv.relay.standalone"
        minSdk = 26
        targetSdk = 32
        versionCode = 41
        versionName = "0.5.0"
    }
    signingConfigs {
        if (relayKeystoreFile != null) {
            create("relay") {
                storeFile = relayKeystoreFile
                storePassword = relaySigningValue("RELAY_KEYSTORE_PASSWORD")
                keyAlias = relaySigningValue("RELAY_KEY_ALIAS")
                keyPassword = relaySigningValue("RELAY_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        debug {
            if (relayKeystoreFile != null) signingConfig = signingConfigs.getByName("relay")
        }
        release {
            isMinifyEnabled = false
            if (relayKeystoreFile != null) signingConfig = signingConfigs.getByName("relay")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
dependencies {
    implementation("androidx.tvprovider:tvprovider:1.0.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("com.google.zxing:core:3.5.3")
}
