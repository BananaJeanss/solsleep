// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.alarm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import dev.bananajeans.solsleep.R
import dev.bananajeans.solsleep.core.*
import dev.bananajeans.solsleep.ui.*
import kotlinx.coroutines.*

class RingingActivity : ComponentActivity() {
    private var entry by mutableStateOf<AlarmEntry?>(null)
    private var busy by mutableStateOf(false)
    private var error by mutableStateOf(false)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge();setShowWhenLocked(true);setTurnScreenOn(true)
        setContent {
            SolSleepTheme(night=true) {
                Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) {
                    val current=entry
                    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(32.dp),
                        horizontalAlignment=Alignment.CenterHorizontally,
                        verticalArrangement=Arrangement.spacedBy(24.dp,Alignment.CenterVertically)) {
                        Image(painterResource(R.drawable.ic_sleeping_android),null,Modifier.size(156.dp))
                        Text(stringResource(R.string.wake_up),style=MaterialTheme.typography.headlineMedium)
                        if(current!=null) {
                            val pending=requireNotNull(current.pending)
                            TimeDisplay(current.rule.time,large=true)
                            Text(current.rule.label.ifBlank {getString(R.string.alarm)},
                                style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Medium)
                            if(error) Text(stringResource(R.string.action_error),color=MaterialTheme.colorScheme.error)
                            Spacer(Modifier.height(20.dp))
                            if(pending.snoozes<current.rule.snoozeLimit) {
                                Button(enabled=!busy,onClick={act(snooze=true)},
                                    modifier=Modifier.fillMaxWidth().heightIn(min=64.dp)) {
                                    Text(stringResource(R.string.snooze_minutes,current.rule.snoozeMinutes),
                                        style=MaterialTheme.typography.titleLarge)
                                }
                                Text(pluralStringResource(R.plurals.snoozes_left,current.rule.snoozeLimit-pending.snoozes,current.rule.snoozeLimit-pending.snoozes),
                                    style=MaterialTheme.typography.bodyMedium)
                            }
                            FilledTonalButton(enabled=!busy,onClick={act(snooze=false)},
                                modifier=Modifier.fillMaxWidth().heightIn(min=64.dp)) {
                                Text(stringResource(R.string.dismiss),style=MaterialTheme.typography.titleLarge)
                            }
                        } else CircularProgressIndicator()
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while(isActive) {
                    try {
                        val active=withContext(Dispatchers.IO) {
                            AlarmGraph.engine(this@RingingActivity).entries()
                                .firstOrNull {it.pending?.state==DeliveryState.RINGING}
                        }
                        if(active==null) {finish();return@repeatOnLifecycle}
                        entry=active
                    } catch(e: CancellationException) {throw e}
                    catch(_: Exception) {error=true}
                    delay(1000)
                }
            }
        }
    }

    private fun act(snooze: Boolean) {
        val token=entry?.pending?.token ?: return
        if(busy) return
        busy=true
        lifecycleScope.launch {
            try {
                val done=withContext(Dispatchers.IO) {
                    if(snooze) AlarmGraph.engine(this@RingingActivity).snooze(token)
                    else AlarmGraph.engine(this@RingingActivity).dismiss(token)
                }
                if(done) {
                    RingingService.refresh(this@RingingActivity)
                    val other=withContext(Dispatchers.IO) {AlarmGraph.engine(this@RingingActivity)
                        .entries().firstOrNull {it.pending?.state==DeliveryState.RINGING}}
                    if(other==null) finish() else entry=other
                } else error=true
            } catch(e: CancellationException) {throw e}
            catch(_: Exception) {error=true}
            finally {busy=false}
        }
    }
}
