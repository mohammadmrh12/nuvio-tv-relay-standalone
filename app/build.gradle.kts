plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.nuvio.tv.relay.standalone"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.nuvio.tv.relay.standalone"
        minSdk = 26
        targetSdk = 32
        versionCode = 2
        versionName = "0.1.1"
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
}
