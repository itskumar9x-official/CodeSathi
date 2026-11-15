package com.inyogeshwar.codesathi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.inyogeshwar.codesathi.ui.AppViewModel
import com.inyogeshwar.codesathi.ui.EditorViewModel
import com.inyogeshwar.codesathi.ui.GitViewModel
import com.inyogeshwar.codesathi.ui.Screen
import com.inyogeshwar.codesathi.ui.TerminalViewModel
import com.inyogeshwar.codesathi.ui.back
import com.inyogeshwar.codesathi.ui.go
import com.inyogeshwar.codesathi.ui.screens.ChatScreen
import com.inyogeshwar.codesathi.ui.screens.CommandsScreen
import com.inyogeshwar.codesathi.ui.screens.EditorScreen
import com.inyogeshwar.codesathi.ui.screens.GitScreen
import com.inyogeshwar.codesathi.ui.screens.McpScreen
import com.inyogeshwar.codesathi.ui.screens.OnboardingScreen
import com.inyogeshwar.codesathi.ui.screens.RemoteScreen
import com.inyogeshwar.codesathi.ui.screens.RunsScreen
import com.inyogeshwar.codesathi.ui.screens.SessionsScreen
import com.inyogeshwar.codesathi.ui.screens.SettingsScreen
import com.inyogeshwar.codesathi.ui.screens.SkillsScreen
import com.inyogeshwar.codesathi.ui.screens.SyncScreen
import com.inyogeshwar.codesathi.ui.screens.TerminalScreen
import com.inyogeshwar.codesathi.ui.screens.UsageScreen
import com.inyogeshwar.codesathi.ui.theme.CodeSathiTheme
import com.inyogeshwar.codesathi.ui.theme.SathiBackdrop
import com.inyogeshwar.codesathi.ui.theme.SathiMark

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // v1.2: draw behind the system bars; every screen handles insets
        // through SathiScaffold (bars + keyboard).
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            CodeSathiTheme {
                val app = application as CodeSathiApp
                val vm: AppViewModel = viewModel(factory = AppViewModel.factory(app))
                // RemoteService reuses this same ViewModel for remote turns.
                app.appViewModel = vm
                SathiBackdrop {
                    if (!vm.onboardingChecked) {
                        // Brief brand splash while the stored onboarded
                        // flag is read, so onboarding is only shown once.
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            SathiMark(size = 84.dp, tile = true)
                        }
                    } else {
                        AppNavigation(app, vm)
                    }
                }
            }
        }
    }
}

/**
 * Phase 8 navigation: no floating tab bar anywhere. Sessions is the
 * start destination once onboarding is done; every other screen is a
 * secondary destination reached with [go] and left with [back].
 * LocalBottomBarInset is left at its 0.dp default — screens keep
 * working, they just no longer reserve space for a tab bar.
 */
@Composable
private fun AppNavigation(app: CodeSathiApp, vm: AppViewModel) {
    val nav = rememberNavController()
    // Chosen once from the real stored value (see onboardingChecked).
    val start = remember { if (vm.onboarded) Screen.Sessions.route else Screen.Onboarding.route }

    val openSession: (String) -> Unit = { id -> nav.go(Screen.Chat.route(id)) }

    NavHost(
        navController = nav,
        startDestination = start,
        enterTransition = { fadeIn(tween(200)) },
        exitTransition = { fadeOut(tween(140)) },
        popEnterTransition = { fadeIn(tween(200)) },
        popExitTransition = { fadeOut(tween(140)) }
    ) {
        composable(Screen.Onboarding.route) {
            OnboardingScreen(
                viewModel = vm,
                onDone = { openProviders ->
                    // Single-tap guard: only act while onboarding is the
                    // resumed screen, so a double tap cannot add Sessions twice.
                    val current = nav.currentBackStackEntry
                    if (current?.destination?.route == Screen.Onboarding.route &&
                        current.lifecycle.currentState == Lifecycle.State.RESUMED
                    ) {
                        nav.navigate(Screen.Sessions.route) {
                            popUpTo(Screen.Onboarding.route) { inclusive = true }
                        }
                        if (openProviders) nav.navigate(Screen.Settings.route)
                    }
                }
            )
        }
        composable(Screen.Sessions.route) {
            SessionsScreen(
                viewModel = vm,
                onOpenSession = openSession,
                onSettings = { nav.go(Screen.Settings.route) }
            )
        }
        composable(Screen.Settings.route) {
            SettingsScreen(
                viewModel = vm,
                onUsage = { nav.go(Screen.Usage.route) },
                onMcp = { nav.go(Screen.Mcp.route) },
                onSkills = { nav.go(Screen.Skills.route) },
                onCommands = { nav.go(Screen.Commands.route) },
                onTerminal = { nav.go(Screen.Terminal.route) },
                onRemote = { nav.go(Screen.Remote.route) },
                onSync = { nav.go(Screen.Sync.route) },
                onGit = { nav.go(Screen.Git.route) },
                onEditor = { nav.go(Screen.Editor.route) },
                onRuns = { nav.go(Screen.Runs.route) }
            )
        }
        composable(
            route = Screen.Chat.route,
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType })
        ) { backStack ->
            val sessionId = backStack.arguments?.getString("sessionId").orEmpty()
            ChatScreen(
                viewModel = vm,
                sessionId = sessionId,
                onProviders = { nav.go(Screen.Settings.route) },
                onBack = { nav.back() },
                onSettings = { nav.go(Screen.Settings.route) }
            )
        }
        composable(Screen.Mcp.route) {
            McpScreen(viewModel = vm, onBack = { nav.back() })
        }
        composable(Screen.Skills.route) {
            SkillsScreen(viewModel = vm, onBack = { nav.back() })
        }
        composable(Screen.Commands.route) {
            CommandsScreen(viewModel = vm, onBack = { nav.back() })
        }
        composable(Screen.Remote.route) {
            RemoteScreen(viewModel = vm, onBack = { nav.back() })
        }
        composable(Screen.Sync.route) {
            SyncScreen(viewModel = vm, onBack = { nav.back() })
        }
        composable(Screen.Terminal.route) {
            val terminalVm: TerminalViewModel = viewModel(factory = TerminalViewModel.factory(app))
            TerminalScreen(viewModel = terminalVm, onBack = { nav.back() })
        }
        composable(Screen.Git.route) {
            val gitVm: GitViewModel = viewModel(factory = GitViewModel.factory(app))
            GitScreen(viewModel = gitVm, onBack = { nav.back() })
        }
        composable(Screen.Editor.route) {
            val editorVm: EditorViewModel = viewModel(factory = EditorViewModel.factory(app))
            EditorScreen(
                viewModel = editorVm,
                appViewModel = vm,
                onBack = { nav.back() },
                onOpenSession = openSession
            )
        }
        composable(Screen.Usage.route) {
            UsageScreen(viewModel = vm, onBack = { nav.back() })
        }
        composable(Screen.Runs.route) {
            RunsScreen(viewModel = vm, onBack = { nav.back() }, onOpenSession = openSession)
        }
    }
}
