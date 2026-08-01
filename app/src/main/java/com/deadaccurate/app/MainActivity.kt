package com.deadaccurate.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.deadaccurate.app.ui.TimegrapherScreen
import com.deadaccurate.app.ui.theme.DeadAccurateTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DeadAccurateTheme {
                TimegrapherScreen()
            }
        }
    }
}
