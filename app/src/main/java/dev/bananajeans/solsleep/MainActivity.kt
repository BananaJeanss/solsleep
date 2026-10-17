// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import dev.bananajeans.solsleep.ui.*

class MainActivity : ComponentActivity() {
    private val model by lazy {ViewModelProvider(this,AlarmViewModel.Factory(applicationContext))[AlarmViewModel::class.java]}
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {SolSleepTheme {AlarmHome(model)}}
    }
    override fun onResume() {super.onResume();model.reload(reconcile=true)}
}
