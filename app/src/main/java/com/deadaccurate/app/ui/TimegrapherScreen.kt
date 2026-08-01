package com.deadaccurate.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.deadaccurate.engine.NativeEngine

/**
 * M0 placeholder for the main screen: proves the Compose toolchain and the
 * JNI seam work end to end by showing the native engine's version string.
 * Replaced by the real trace/readout UI in M1+.
 */
@Composable
fun TimegrapherScreen() {
    val engineVersion = remember { NativeEngine.version() }
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "DeadAccurate",
                style = MaterialTheme.typography.headlineLarge,
            )
            Text(
                text = "engine $engineVersion",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
