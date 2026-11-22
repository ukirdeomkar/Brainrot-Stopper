plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.brainrotstopper.feature.settings"
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
    implementation(project(":core:security"))
    implementation(project(":core:data"))
    implementation(project(":feature:timers"))
    implementation(project(":feature:blocking"))
    implementation(project(":feature:onboarding"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
}
