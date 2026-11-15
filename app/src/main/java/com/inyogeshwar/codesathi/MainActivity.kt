package com.inyogeshwar.codesathi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.inyogeshwar.codesathi.ui.AppViewModel
import com.inyogeshwar.codesathi.ui.EditorViewModel
import com.inyogeshwar.codesathi.ui.FloatingTabBar
import com.inyogeshwar.codesathi.ui.FloatingTabBarHeight
import com.inyogeshwar.codesathi.ui.GitViewModel
import com.inyogeshwar.codesathi.ui.Screen
import com.inyogeshwar.codesathi.ui.TerminalViewModel
import com.inyogeshwar.codesathi.ui.TopLevel
import com.inyogeshwar.codesathi.ui.back
import com.inyogeshwar.codesathi.ui.go
import com.inyogeshwar.codesathi.ui.screens.ChatScreen
import com.inyogeshwar.codesathi.ui.screens.CommandsScreen
import com.inyogeshwar.codesathi.ui.screens.EditorScreen
import com.inyogeshwar.codesathi.ui.screens.GitScreen
import com.inyogeshwar.codesathi.ui.screens.HomeScreen
import com.inyogeshwar.codesathi.ui.screens.McpScreen
import com.inyogeshwar.codesathi.ui.screens.NewSessionScreen
import com.inyogeshwar.codesathi.ui.screens.OnboardingScreen
import com.inyogeshwar.codesathi.ui.screens.ProviderPickerScreen
import com.inyogeshwar.codesathi.ui.screens.RemoteScreen
import com.inyogeshwar.codesathi.ui.screens.RunsScreen
import com.inyogeshwar.codesathi.ui.screens.SessionsScreen
import com.inyogeshwar.codesathi.ui.screens.SettingsScreen
import com.inyogeshwar.codesathi.ui.screens.SkillsScreen
import com.inyogeshwar.codesathi.ui.screens.SyncScreen
import com.inyogeshwar.codesathi.ui.screens.TerminalScreen
import com.inyogeshwar.codesathi.ui.screens.ToolsScreen
import com.inyogeshwar.codesathi.ui.screens.UsageScreen
import com.inyogeshwar.codesathi.ui.switchTab
import com.inyogeshwar.codesathi.ui.theme.CodeSathiTheme
import com.inyogeshwar.codesathi.ui.theme.LocalBottomBarInset
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppNavigation(app: CodeSathiApp, vm: AppViewModel) {
    val nav = rememberNavController()
    // Chosen once from the real stored value (see onboardingChecked).
    val start = remember { if (vm.onboarded) Screen.Home.route else Screen.Onboarding.route }
    val entry by nav.currentBackStackEntryAsState()
    val currentTab = TopLevel.fromRoute(entry?.destination?.route)
    val showBar = currentTab != null && !WindowInsets.isImeVisible

    val openSession: (String) -> Unit = { id -> nav.go(Screen.Chat.route(id)) }

    Box(Modifier.fillMaxSize()) {
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
                        // resumed screen, so a double tap cannot add Home twice.
                        val current = nav.currentBackStackEntry
                        if (current?.destination?.route == Screen.Onboarding.route &&
                            current.lifecycle.currentState == Lifecycle.State.RESUMED
                        ) {
                            nav.navigate(Screen.Home.route) {
                                popUpTo(Screen.Onboarding.route) { inclusive = true }
                            }
                            if (openProviders) nav.navigate(Screen.Providers.route)
                        }
                    }
                )
            }
            composable(Screen.Home.route) {
                CompositionLocalProvider(LocalBottomBarInset provides FloatingTabBarHeight) {
                    HomeScreen(
                        viewModel = vm,
                        onOpenSession = openSession,
                        onNewSession = { nav.go(Screen.NewSession.route) },
                        onAllSessions = { nav.switchTab(TopLevel.SESSIONS) },
                        onEditor = { nav.go(Screen.Editor.route) },
                        onTerminal = { nav.go(Screen.Terminal.route) },
                        onGit = { nav.go(Screen.Git.route) },
                        onProviders = { nav.go(Screen.Providers.route) },
                        onTools = { nav.switchTab(TopLevel.TOOLS) }
                    )
                }
            }
            composable(Screen.Sessions.route) {
                CompositionLocalProvider(LocalBottomBarInset provides FloatingTabBarHeight) {
                    SessionsScreen(
                        viewModel = vm,
                        onOpenSession = openSession,
                        onNewSession = { nav.go(Screen.NewSession.route) }
                    )
                }
            }
            composable(Screen.Tools.route) {
                CompositionLocalProvider(LocalBottomBarInset provides FloatingTabBarHeight) {
                    ToolsScreen(
                        onEditor = { nav.go(Screen.Editor.route) },
                        onGit = { nav.go(Screen.Git.route) },
                        onTerminal = { nav.go(Screen.Terminal.route) },
                        onSkills = { nav.go(Screen.Skills.route) },
                        onCommands = { nav.go(Screen.Commands.route) },
                        onMcp = { nav.go(Screen.Mcp.route) },
                        onRemote = { nav.go(Screen.Remote.route) },
                        onSync = { nav.go(Screen.Sync.route) },
                        onUsage = { nav.go(Screen.Usage.route) },
                        onRuns = { nav.go(Screen.Runs.route) }
                    )
                }
            }
            composable(Screen.Settings.route) {
                CompositionLocalProvider(LocalBottomBarInset provides FloatingTabBarHeight) {
                    SettingsScreen(
                        viewModel = vm,
                        onProviders = { nav.go(Screen.Providers.route) },
                        onUsage = { nav.go(Screen.Usage.route) },
                        onMcp = { nav.go(Screen.Mcp.route) }
                    )
                }
            }
            composable(Screen.NewSession.route) {
                NewSessionScreen(
                    viewModel = vm,
                    onCreated = { id ->
                        // Replace "New session" with the chat, so Back from
                        // the chat returns to where the user started.
                        nav.go(Screen.Chat.route(id)) {
                            popUpTo(Screen.NewSession.route) { inclusive = true }
                        }
                    },
                    onBack = { nav.back() }
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
                    onProviders = { nav.go(Screen.Providers.route) },
                    onBack = { nav.back() }
                )
            }
            composable(Screen.Providers.route) {
                ProviderPickerScreen(viewModel = vm, onBack = { nav.back() })
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

        AnimatedVisibility(
            visible = showBar,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(tween(160)) + slideInVertically(tween(200)) { it / 3 },
            exit = fadeOut(tween(120)) + slideOutVertically(tween(160)) { it / 3 }
        ) {
            FloatingTabBar(selected = currentTab, onSelect = { nav.switchTab(it) })
        }
    }
}
