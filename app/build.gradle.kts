plugins {
    id("com.android.application")
    // AGP 9 bundles Kotlin support, so org.jetbrains.kotlin.android is NOT
    // applied here anymore (AGP 9 rejects it). Compose compiler comes from
    // the Kotlin Compose plugin (Kotlin 2.0+), not kotlinCompilerExtensionVersion.
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.inyogeshwar.codesathi"
    // Latest setup (Nov 2026): API 36 = Android 16. Play Store requires
    // new apps/updates to target API 36 from 31 Aug 2026.
    compileSdk = 36

    defaultConfig {
        applicationId = "com.inyogeshwar.codesathi"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    // AGP 9.5 runs on JDK 17, so Java/Kotlin target is 17 as well.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    // No composeOptions block: with Kotlin 2.x the Compose compiler is
    // bundled via org.jetbrains.kotlin.plugin.compose (see plugins above).
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Compose BOM paired with Kotlin 2.2 (2025.06.00).
    val composeBom = platform("androidx.compose:compose-bom:2025.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.6")
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Room (sessions, messages, favourites, summaries) — 2.7.0 is the
    // Kotlin 2.2-era release; 2.6.1 predates it.
    implementation("androidx.room:room-runtime:2.7.0")
    implementation("androidx.room:room-ktx:2.7.0")
    ksp("androidx.room:room-compiler:2.7.0")

    // DataStore Preferences (project folder URI, default model)
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // EncryptedSharedPreferences — API keys live ONLY here, never plain text.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
