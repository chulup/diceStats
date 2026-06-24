package com.dicestats.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.dicestats.app.feature.capture.CaptureScreen
import com.dicestats.app.feature.rolllog.RollLogScreen

object Routes {
    const val ROLL_LOG = "roll_log"
    const val CAPTURE = "capture"
}

@Composable
fun DiceStatsNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.ROLL_LOG) {
        composable(Routes.ROLL_LOG) {
            RollLogScreen(onCapture = { navController.navigate(Routes.CAPTURE) })
        }
        composable(Routes.CAPTURE) {
            CaptureScreen(
                onPhotoSaved = { navController.popBackStack() },
                onBack = { navController.popBackStack() },
            )
        }
    }
}
