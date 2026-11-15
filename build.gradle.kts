// Top-level build file for CodeSathi.
// Idea-only project: all code here was written fresh for CodeSathi.
// Latest setup (Nov 2026, verified against AGP 9.5 release notes):
// AGP 9.5.1 needs Gradle 9.6.0 + JDK 17 + Build Tools 36.0.0, and bundles
// Kotlin Gradle plugin 2.2.10. AGP 9 has Kotlin built in, so the separate
// org.jetbrains.kotlin.android plugin is no longer applied (it is rejected
// by AGP 9). Previous versions: AGP 8.2.0, Gradle 8.2, Kotlin 1.9.22.
plugins {
    id("com.android.application") version "9.5.1" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
    id("com.google.devtools.ksp") version "2.2.10-2.0.2" apply false
    // KSP 2.2.10-2.0.2 (reverted 2026-11-14 on the user's "Fix kr do"
    // after the sync warning): pairs with this project's bundled Kotlin
    // 2.2.10. v1.2 used 2.3.13 (verified to exist on Maven Central,
    // published 2026-10-31), but 2.3.x normally pairs with Kotlin 2.3.x,
    // not 2.2.10 — keep 2.3.13 as the alternative if sync ever asks for it.
}
