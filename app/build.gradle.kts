plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
val releaseStore = providers.environmentVariable("FOIS_RELEASE_KEYSTORE").orNull
val releaseStorePassword = providers.environmentVariable("FOIS_RELEASE_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("FOIS_RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("FOIS_RELEASE_KEY_PASSWORD").orNull
val hasReleaseSigning = listOf(releaseStore, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
    .all { !it.isNullOrBlank() }

android {
    namespace = "dev.foistracker"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "org.railtracker.fois"
        minSdk = 26
        targetSdk = 36
        versionCode = 8
        versionName = "1.0.6"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    if (hasReleaseSigning) {
        signingConfigs.create("foisRelease") {
            storeFile = file(requireNotNull(releaseStore))
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("foisRelease")
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.03.00"))
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.work:work-runtime-ktx:2.11.1")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.21.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.03.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
