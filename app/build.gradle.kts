plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "dev.brainrotstopper.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.brainrotstopper"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0-scaffold"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
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
    implementation(project(":feature:blocking"))
    implementation(project(":feature:onboarding"))
    implementation(project(":feature:rules"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:timers"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(project(":core:security"))

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(project(":core:security"))
    androidTestImplementation(project(":feature:settings"))
}
