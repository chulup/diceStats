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
import xyz.chulup.dicestats.feature.rolllog.RollLogScreen

object Routes {
    const val ROLL_LOG = "roll_log"
    const val CAPTURE = "capture"

    const val DETECTION_ARG_PHOTO_PATH = "photoPath"
    const val DETECTION = "detection/{$DETECTION_ARG_PHOTO_PATH}"

    fun detection(photoPath: String): String = "detection/${Uri.encode(photoPath)}"
}

@Composable
fun DiceStatsNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.ROLL_LOG) {
        composable(Routes.ROLL_LOG) {
            RollLogScreen(
                onCapture = { navController.navigate(Routes.CAPTURE) },
            )
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
        ) { backStackEntry ->
            val photoPath = backStackEntry.arguments
                ?.getString(Routes.DETECTION_ARG_PHOTO_PATH)
                .orEmpty()
            DetectionScreen(
                photoPath = photoPath,
                onBack = { navController.popBackStack() },
                // Retake: reopen the camera, replacing this detection on the back stack.
                onRetake = {
                    navController.navigate(Routes.CAPTURE) {
                        popUpTo(Routes.DETECTION) { inclusive = true }
                    }
                },
                onSaved = { navController.popBackStack(Routes.ROLL_LOG, inclusive = false) },
            )
        }
    }
}
