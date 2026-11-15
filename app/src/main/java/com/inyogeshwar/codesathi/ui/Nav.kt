package com.inyogeshwar.codesathi.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavHostController

/** Navigation routes (no path text field on any screen). */
sealed class Screen(val route: String) {
    object Onboarding : Screen("onboarding")
    // v1.2 top-level tabs (floating tab bar).
    object Home : Screen("home")
    object Sessions : Screen("sessions")
    object Tools : Screen("tools")
    object Settings : Screen("settings")
    // Secondary screens, reached from the tabs / Tools hub.
    object NewSession : Screen("new_session")
    object Chat : Screen("chat/{sessionId}") {
        fun route(sessionId: String) = "chat/$sessionId"
    }
    object Providers : Screen("providers")
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

/** The four destinations on the floating tab bar. */
enum class TopLevel(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector
) {
    HOME(Screen.Home.route, "Home", Icons.Outlined.Home, Icons.Filled.Home),
    SESSIONS(Screen.Sessions.route, "Chats", Icons.Outlined.Chat, Icons.Filled.Chat),
    TOOLS(Screen.Tools.route, "Tools", Icons.Outlined.Widgets, Icons.Filled.Widgets),
    SETTINGS(Screen.Settings.route, "Settings", Icons.Outlined.Settings, Icons.Filled.Settings);

    companion object {
        fun fromRoute(route: String?): TopLevel? = entries.firstOrNull { it.route == route }
    }
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

/**
 * Switch tabs: keeps one copy of each tab, saves/restores its state, and
 * keeps Home at the root so Back from any tab returns to Home, and Back
 * on Home leaves the app.
 */
fun NavHostController.switchTab(tab: TopLevel) {
    if (currentBackStackEntry?.destination?.route == tab.route) return
    if (!isIdle()) return
    navigate(tab.route) {
        // Home is always the root once onboarding is done (onboarding pops
        // itself), so tabs pop back to Home rather than the graph start.
        popUpTo(Screen.Home.route) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
