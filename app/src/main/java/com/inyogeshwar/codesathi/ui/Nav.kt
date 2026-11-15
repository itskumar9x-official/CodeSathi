package com.inyogeshwar.codesathi.ui

import androidx.lifecycle.Lifecycle
import androidx.navigation.NavHostController

/**
 * Navigation routes (no path text field on any screen).
 *
 * Phase 8: no floating tab bar. Sessions is the start destination after
 * onboarding; Chat and every secondary screen are reached from Sessions
 * (or Settings) and return with a back arrow. The old tab destinations
 * (Home, Tools), the NewSession screen (now a bottom sheet on Sessions)
 * and the standalone Providers route (providers live in Settings) are
 * gone.
 */
sealed class Screen(val route: String) {
    object Onboarding : Screen("onboarding")
    object Sessions : Screen("sessions")
    object Settings : Screen("settings")
    // Secondary screens, reached from Sessions / Settings / Chat.
    object Chat : Screen("chat/{sessionId}") {
        fun route(sessionId: String) = "chat/$sessionId"
    }
    object Mcp : Screen("mcp")
    object Skills : Screen("skills")
    object Commands : Screen("commands")
    object Terminal : Screen("terminal")
    object Remote : Screen("remote")
    object Sync : Screen("sync")
    object Git : Screen("git")
    object Editor : Screen("editor")
    object Usage : Screen("usage")
    object Runs : Screen("runs")
}

/**
 * Single-tap guard: navigation only happens from a screen that is fully
 * RESUMED. A second tap during the transition (when the old screen is
 * no longer resumed) is ignored, so a double tap never opens a screen
 * twice or pops two screens.
 */
private fun NavHostController.isIdle(): Boolean =
    currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED

/** Guarded navigate to a secondary screen. */
fun NavHostController.go(route: String, builder: androidx.navigation.NavOptionsBuilder.() -> Unit = {}) {
    if (isIdle()) navigate(route, builder)
}

/** Guarded back. */
fun NavHostController.back() {
    if (isIdle()) popBackStack()
}
