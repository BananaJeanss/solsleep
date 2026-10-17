// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.diagnostics

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.bananajeans.solsleep.R
import dev.bananajeans.solsleep.ui.SolSleepTheme

/** No exported entry point. Instrumentation launches it while visible. */
class MotionProbeActivity: ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState);enableEdgeToEdge()
        setContent {
            SolSleepTheme {
                var failed by remember {mutableStateOf(false)}
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding().padding(24.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
                        Text(stringResource(R.string.probe_title),style=MaterialTheme.typography.headlineMedium)
                        Text(stringResource(R.string.probe_body))
                        Button(onClick={try {
                            ContextCompat.startForegroundService(this@MotionProbeActivity,Intent(this@MotionProbeActivity,MotionProbeService::class.java))
                        } catch(_: Exception) {failed=true}}) {Text(stringResource(R.string.probe_start))}
                        OutlinedButton(onClick={stopService(Intent(this@MotionProbeActivity,MotionProbeService::class.java))}) {Text(stringResource(R.string.probe_stop))}
                        if(failed) Text(stringResource(R.string.probe_failure),color=MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}
