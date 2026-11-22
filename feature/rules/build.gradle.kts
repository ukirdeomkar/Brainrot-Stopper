plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.brainrotstopper.feature.rules"
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
    implementation(project(":core:security"))
    implementation(project(":feature:settings"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    // Proves the limit saved by this editor is enforced by the M6 decision engine.
    testImplementation(project(":feature:blocking"))
}
