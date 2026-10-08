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
import xyz.chulup.dicestats.feature.dicemanage.DiceManageScreen
import xyz.chulup.dicestats.feature.games.GameStatsScreen
import xyz.chulup.dicestats.feature.games.GamesScreen
import xyz.chulup.dicestats.feature.manualroll.ManualRollScreen
import xyz.chulup.dicestats.feature.rolllog.RollLogScreen
import xyz.chulup.dicestats.feature.stats.DieStatsScreen
import xyz.chulup.dicestats.feature.stats.RollTotalsScreen

object Routes {
    const val ROLL_LOG = "roll_log"
    const val CAPTURE = "capture"
    const val MANUAL_ROLL = "manual_roll"
    const val DICE_LIST = "dice_list"
    const val DICE_MANAGE = "dice_manage"
    const val ROLL_TOTALS = "roll_totals"
    const val GAMES = "games"

    const val DETECTION_ARG_PHOTO_PATH = "photoPath"
    const val DETECTION = "detection/{$DETECTION_ARG_PHOTO_PATH}"

    const val DIE_STATS_ARG_DIE_ID = "dieId"
    const val DIE_STATS = "die_stats/{$DIE_STATS_ARG_DIE_ID}"

    const val GAME_STATS_ARG_GAME_ID = "gameId"
    const val GAME_STATS = "game_stats/{$GAME_STATS_ARG_GAME_ID}"

    fun detection(photoPath: String): String = "detection/${Uri.encode(photoPath)}"

    fun dieStats(dieId: Long): String = "die_stats/$dieId"

    fun gameStats(gameId: Long): String = "game_stats/$gameId"
}

@Composable
fun DiceStatsNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.ROLL_LOG) {
        composable(Routes.ROLL_LOG) {
            RollLogScreen(
                onCapture = { navController.navigate(Routes.CAPTURE) },
                onManualRoll = { navController.navigate(Routes.MANUAL_ROLL) },
                onDiceStats = { navController.navigate(Routes.DICE_LIST) },
                onManageDice = { navController.navigate(Routes.DICE_MANAGE) },
            )
        }
        composable(Routes.MANUAL_ROLL) {
            ManualRollScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.DICE_LIST) {
            DiceListScreen(
                onBack = { navController.popBackStack() },
                onDieSelected = { dieId -> navController.navigate(Routes.dieStats(dieId)) },
                onRollTotals = { navController.navigate(Routes.ROLL_TOTALS) },
                onGames = { navController.navigate(Routes.GAMES) },
            )
        }
        composable(Routes.DICE_MANAGE) {
            DiceManageScreen(
                onBack = { navController.popBackStack() },
                onDieSelected = { dieId -> navController.navigate(Routes.dieStats(dieId)) },
            )
        }
        composable(Routes.ROLL_TOTALS) {
            RollTotalsScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.GAMES) {
            GamesScreen(
                onBack = { navController.popBackStack() },
                onGameSelected = { gameId -> navController.navigate(Routes.gameStats(gameId)) },
            )
        }
        composable(
            route = Routes.GAME_STATS,
            arguments = listOf(
                navArgument(Routes.GAME_STATS_ARG_GAME_ID) { type = NavType.LongType },
            ),
        ) {
            GameStatsScreen(onBack = { navController.popBackStack() })
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
