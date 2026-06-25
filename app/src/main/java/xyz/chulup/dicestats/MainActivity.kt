package xyz.chulup.dicestats

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import xyz.chulup.dicestats.ui.navigation.DiceStatsNavHost
import xyz.chulup.dicestats.ui.theme.DiceStatsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DiceStatsTheme {
                DiceStatsNavHost()
            }
        }
    }
}
