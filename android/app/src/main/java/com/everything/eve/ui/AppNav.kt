package com.everything.eve.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.everything.eve.ServiceLocator
import com.everything.eve.ui.screens.VaultScreen
import com.everything.eve.ui.screens.WelcomeScreen

object Routes {
    const val WELCOME = "welcome"
    const val VAULT = "vault"
}

@Composable
fun AppNav() {
    val nav = rememberNavController()
    val start = if (ServiceLocator.auth.isLoggedIn) Routes.VAULT else Routes.WELCOME
    NavHost(navController = nav, startDestination = start) {
        composable(Routes.WELCOME) {
            WelcomeScreen(onEntered = {
                nav.navigate(Routes.VAULT) {
                    popUpTo(Routes.WELCOME) { inclusive = true }
                }
            })
        }
        composable(Routes.VAULT) {
            VaultScreen(onLoggedOut = {
                nav.navigate(Routes.WELCOME) {
                    popUpTo(Routes.VAULT) { inclusive = true }
                }
            })
        }
    }
}
