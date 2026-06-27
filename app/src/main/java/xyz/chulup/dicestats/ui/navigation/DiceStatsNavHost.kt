package xyz.chulup.dicestats.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import xyz.chulup.dicestats.feature.capture.CaptureScreen
import xyz.chulup.dicestats.feature.detection.DetectionScreen
import xyz.chulup.dicestats.feature.dicelist.DiceListScreen
import xyz.chulup.dicestats.feature.rolllog.RollLogScreen
import xyz.chulup.dicestats.feature.stats.DieStatsScreen

object Routes {
    const val ROLL_LOG = "roll_log"
    const val CAPTURE = "capture"
    const val DICE_LIST = "dice_list"

    const val DETECTION_ARG_PHOTO_PATH = "photoPath"
    const val DETECTION = "detection/{$DETECTION_ARG_PHOTO_PATH}"

    const val DIE_STATS_ARG_DIE_ID = "dieId"
    const val DIE_STATS = "die_stats/{$DIE_STATS_ARG_DIE_ID}"

    fun detection(photoPath: String): String = "detection/${Uri.encode(photoPath)}"

    fun dieStats(dieId: Long): String = "die_stats/$dieId"
}

@Composable
fun DiceStatsNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.ROLL_LOG) {
        composable(Routes.ROLL_LOG) {
            RollLogScreen(
                onCapture = { navController.navigate(Routes.CAPTURE) },
                onDiceStats = { navController.navigate(Routes.DICE_LIST) },
            )
        }
        composable(Routes.DICE_LIST) {
            DiceListScreen(
                onBack = { navController.popBackStack() },
                onDieSelected = { dieId -> navController.navigate(Routes.dieStats(dieId)) },
            )
        }
        composable(
            route = Routes.DIE_STATS,
            arguments = listOf(
                navArgument(Routes.DIE_STATS_ARG_DIE_ID) { type = NavType.LongType },
            ),
        ) {
            DieStatsScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.CAPTURE) {
            CaptureScreen(
                // Go straight to confirm/detection on the captured photo; drop capture
                // from the back stack so "back" from confirm returns to the roll log.
                onPhotoSaved = { path ->
                    navController.navigate(Routes.detection(path)) {
                        popUpTo(Routes.CAPTURE) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = Routes.DETECTION,
            arguments = listOf(
                navArgument(Routes.DETECTION_ARG_PHOTO_PATH) { type = NavType.StringType },
            ),
        ) {
            // The photo path is read from the nav argument by DetectionViewModel.
            DetectionScreen(
                onBack = { navController.popBackStack() },
                // Retake: reopen the camera, replacing this detection on the back stack.
                onRetake = {
                    navController.navigate(Routes.CAPTURE) {
                        popUpTo(Routes.DETECTION) { inclusive = true }
                    }
                },
                onSaved = { navController.popBackStack(Routes.ROLL_LOG, inclusive = false) },
                // Report a bad photo and immediately reopen the camera for another shot.
                onReported = {
                    navController.navigate(Routes.CAPTURE) {
                        popUpTo(Routes.DETECTION) { inclusive = true }
                    }
                },
            )
        }
    }
}
