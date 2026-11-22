plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.brainrotstopper.core.security"
    compileSdk = 37

    defaultConfig {
        minSdk = 31
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core:model"))

    // EncryptedSharedPreferences is the storage the M7 acceptance criteria name; it keeps the
    // credential encrypted at rest under an Android Keystore master key. Exposed as `api`
    // because EncryptedCredentialStore is part of this module's public surface.
    api(libs.androidx.security.crypto)

    testImplementation(libs.junit)
}
