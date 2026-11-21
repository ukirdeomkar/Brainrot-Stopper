plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.brainrotstopper.feature.blocking"
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
    implementation(project(":core:data"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
}
