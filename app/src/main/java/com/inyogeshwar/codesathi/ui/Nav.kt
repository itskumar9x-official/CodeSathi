package com.inyogeshwar.codesathi.ui

/** Navigation routes for the v1 app (no path text field on any screen). */
sealed class Screen(val route: String) {
    object Onboarding : Screen("onboarding")
    object Home : Screen("home")
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
}
