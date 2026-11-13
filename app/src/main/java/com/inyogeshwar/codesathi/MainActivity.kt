package com.inyogeshwar.codesathi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
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
import com.inyogeshwar.codesathi.ui.screens.SkillsScreen
import com.inyogeshwar.codesathi.ui.screens.SyncScreen
import com.inyogeshwar.codesathi.ui.screens.TerminalScreen
import com.inyogeshwar.codesathi.ui.screens.UsageScreen
import com.inyogeshwar.codesathi.ui.theme.CodeSathiTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CodeSathiTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val app = application as CodeSathiApp
                    val vm: AppViewModel = viewModel(
                        factory = AppViewModel.factory(app)
                    )
                    // RemoteService reuses this same ViewModel for remote turns.
                    app.appViewModel = vm
                    val nav = rememberNavController()
                    val start = if (vm.onboarded) Screen.Home.route else Screen.Onboarding.route
                    NavHost(navController = nav, startDestination = start) {
                        composable(Screen.Onboarding.route) {
                            OnboardingScreen(
                                viewModel = vm,
                                onDone = {
                                    nav.navigate(Screen.Home.route) {
                                        popUpTo(Screen.Onboarding.route) { inclusive = true }
                                    }
                                }
                            )
                        }
                        composable(Screen.Home.route) {
                            HomeScreen(
                                viewModel = vm,
                                onOpenSession = { id -> nav.navigate(Screen.Chat.route(id)) },
                                onNewSession = { nav.navigate(Screen.NewSession.route) },
                                onProviders = { nav.navigate(Screen.Providers.route) },
                                onMcp = { nav.navigate(Screen.Mcp.route) },
                                onSkills = { nav.navigate(Screen.Skills.route) },
                                onCommands = { nav.navigate(Screen.Commands.route) },
                                onTerminal = { nav.navigate(Screen.Terminal.route) },
                                onRemote = { nav.navigate(Screen.Remote.route) },
                                onSync = { nav.navigate(Screen.Sync.route) },
                                onGit = { nav.navigate(Screen.Git.route) },
                                onEditor = { nav.navigate(Screen.Editor.route) },
                                onUsage = { nav.navigate(Screen.Usage.route) }
                            )
                        }
                        composable(Screen.NewSession.route) {
                            NewSessionScreen(
                                viewModel = vm,
                                onCreated = { id -> nav.navigate(Screen.Chat.route(id)) },
                                onBack = { nav.popBackStack() }
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
                                onProviders = { nav.navigate(Screen.Providers.route) },
                                onBack = { nav.popBackStack() }
                            )
                        }
                        composable(Screen.Providers.route) {
                            ProviderPickerScreen(
                                viewModel = vm,
                                onBack = { nav.popBackStack() }
                            )
                        }
                        composable(Screen.Mcp.route) {
                            McpScreen(
                                viewModel = vm,
                                onBack = { nav.popBackStack() }
                            )
                        }
                        composable(Screen.Skills.route) {
                            SkillsScreen(
                                viewModel = vm,
                                onBack = { nav.popBackStack() }
                            )
                        }
                        composable(Screen.Commands.route) {
                            CommandsScreen(
                                viewModel = vm,
                                onBack = { nav.popBackStack() }
                            )
                        }
                        composable(Screen.Remote.route) {
                            RemoteScreen(
                                viewModel = vm,
                                onBack = { nav.popBackStack() }
                            )
                        }
                        composable(Screen.Sync.route) {
                            SyncScreen(
                                viewModel = vm,
                                onBack = { nav.popBackStack() }
                            )
                        }
                        composable(Screen.Terminal.route) {
                            val terminalVm: TerminalViewModel = viewModel(
                                factory = TerminalViewModel.factory(app)
                            )
                            TerminalScreen(
                                viewModel = terminalVm,
                                onBack = { nav.popBackStack() }
                            )
                        }
                        composable(Screen.Git.route) {
                            val gitVm: GitViewModel = viewModel(
                                factory = GitViewModel.factory(app)
                            )
                            GitScreen(
                                viewModel = gitVm,
                                onBack = { nav.popBackStack() }
                            )
                        }
                        composable(Screen.Editor.route) {
                            val editorVm: EditorViewModel = viewModel(
                                factory = EditorViewModel.factory(app)
                            )
                            EditorScreen(
                                viewModel = editorVm,
                                appViewModel = vm,
                                onBack = { nav.popBackStack() },
                                onOpenSession = { id -> nav.navigate(Screen.Chat.route(id)) }
                            )
                        }
                        composable(Screen.Usage.route) {
                            UsageScreen(
                                viewModel = vm,
                                onBack = { nav.popBackStack() }
                            )
                        }
                    }
                }
            }
        }
    }
}
