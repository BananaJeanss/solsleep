// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.capture

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.bananajeans.solsleep.ui.*

class MotionCaptureActivity: ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState);enableEdgeToEdge()
        setContent {SolSleepTheme {CaptureHome(onBack={finish()})}}
    }
}
