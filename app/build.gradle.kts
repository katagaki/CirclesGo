plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.tsubuzaki.circlesgo"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tsubuzaki.circlesgo"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        debug {
            buildConfigField("String", "CIRCLEMS_AUTH_ENDPOINT", "\"https://auth1-sandbox.circle.ms\"")
            buildConfigField("String", "CIRCLEMS_API_ENDPOINT", "\"https://api1-sandbox.circle.ms\"")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("String", "CIRCLEMS_AUTH_ENDPOINT", "\"https://auth1.circle.ms\"")
            buildConfigField("String", "CIRCLEMS_API_ENDPOINT", "\"https://api1.circle.ms\"")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.1"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Kotlin & Coroutines
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Ktor HTTP client
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    // Room
     implementation(libs.room.runtime)
     implementation(libs.room.ktx)

    // AndroidX Security
    implementation(libs.security.crypto)

    // AndroidX Browser (Custom Tabs)
    implementation(libs.browser)

    // AndroidX Core
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.ktx)

    // Compose
    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
}