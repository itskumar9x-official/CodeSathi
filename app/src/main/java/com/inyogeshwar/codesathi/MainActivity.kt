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
import com.inyogeshwar.codesathi.ui.Screen
import com.inyogeshwar.codesathi.ui.screens.ChatScreen
import com.inyogeshwar.codesathi.ui.screens.HomeScreen
import com.inyogeshwar.codesathi.ui.screens.NewSessionScreen
import com.inyogeshwar.codesathi.ui.screens.OnboardingScreen
import com.inyogeshwar.codesathi.ui.screens.ProviderPickerScreen
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
                                onProviders = { nav.navigate(Screen.Providers.route) }
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
                    }
                }
            }
        }
    }
}
