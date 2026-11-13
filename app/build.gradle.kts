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
        // Desugaring (Phase 2, Step 8 fix): JGit 7.x uses java.nio.file,
        // which only exists on Android API 26+. The NIO desugar flavour
        // backports java.nio.file so Git works on minSdk 24 (Android 7.0/7.1)
        // instead of crashing with NoClassDefFoundError at runtime.
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        compose = true
    }
    // No composeOptions block: with Kotlin 2.x the Compose compiler is
    // bundled via org.jetbrains.kotlin.plugin.compose (see plugins above).
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // JGit 7.8.1 (Phase 2, Step 8): the JGit jar is signed and its
            // jars ship LICENSE/NOTICE files — exclude the signature files
            // and the duplicate license files so packaging does not fail
            // on duplicate META-INF entries.
            excludes += "META-INF/*.SF"
            excludes += "META-INF/*.DSA"
            excludes += "META-INF/*.RSA"
            excludes += "META-INF/LICENSE*"
            excludes += "META-INF/NOTICE*"
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

    // JGit (Phase 2, Step 8) — pure-Java Git: status / stage / commit /
    // push / pull / branches / clone over HTTPS. 7.8.1.202611041320-r is
    // the latest stable on Maven Central (released 5 Nov 2026). Its only
    // transitive dependencies are JavaEWAH and slf4j-api — no BouncyCastle
    // in core (that is the separate gpg.bc artifact, for commit signing,
    // which we do not use), no SSH transport (separate artifact; SSH
    // remotes are not supported, HTTPS only), no native code and no git
    // binary needed. JGit 7.x is compiled for Java 11, which D8 dexes;
    // slf4j has no binding here, so it logs nothing (one first-run
    // warning in logcat). See agent/Git.kt for the wrapper.
    implementation("org.eclipse.jgit:org.eclipse.jgit:7.8.1.202611041320-r")

    // NIO desugar library (Phase 2, Step 8 fix) — backports java.nio.file
    // for JGit on API 24/25. 2.0.1 is the version cited in Android's docs
    // table; the artifact lives on Google Maven, not Maven Central.
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.0.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
