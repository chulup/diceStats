package com.dicestats.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.dicestats.app.ui.navigation.DiceStatsNavHost
import com.dicestats.app.ui.theme.DiceStatsTheme

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
