// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.alarm

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import dev.bananajeans.solsleep.R
import dev.bananajeans.solsleep.challenge.ChallengeContent
import dev.bananajeans.solsleep.core.*
import dev.bananajeans.solsleep.ui.*
import kotlinx.coroutines.*

/** Ephemeral visibility only. Durable gain deadline/progress live in the alarm database. */
internal object ChallengeVisibility {
    @Volatile var token: String?=null;private set
    @Volatile var refreshed=0L;private set
    var changed: (() -> Unit)?=null
    fun update(value: String?) {
        val different=token!=value;token=value;refreshed=android.os.SystemClock.elapsedRealtime()
        if(different) changed?.invoke()
    }
    fun visible(value: String)=token==value && android.os.SystemClock.elapsedRealtime()-refreshed in 0..2500
}

class RingingActivity : ComponentActivity() {
    private var entry by mutableStateOf<AlarmEntry?>(null)
    private var busy by mutableStateOf(false)
    private var error by mutableStateOf(false)
    private var resumed by mutableStateOf(false)
    private var phoneError by mutableStateOf(false)
    private var preferred: String?=null
    private var requested: ChallengeAction?=null
    private var intentGeneration=0
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readIntent(intent)
        enableEdgeToEdge();setShowWhenLocked(true);setTurnScreenOn(true)
        setContent {
            SolSleepTheme(night=true) {
                val current=entry;val pending=current?.pending;val policy=current?.rule?.challenge;val task=pending?.challenge
                SideEffect {ChallengeVisibility.update(pending?.token?.takeIf {resumed && task!=null && policy!=null && !task.satisfied(policy)})}
                Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().testTag("challenge_ring_root")) {
                        // Never inside the puzzle's scrolling area or disabled while an answer is saving.
                        TextButton(onClick={openPhone()},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {Text(stringResource(R.string.task_phone))}
                        if(phoneError) Text(stringResource(R.string.task_phone_unavailable),modifier=Modifier.padding(horizontal=20.dp))
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp),
                            verticalArrangement=Arrangement.spacedBy(16.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                            Text(stringResource(R.string.wake_up),style=MaterialTheme.typography.headlineMedium)
                            if(current!=null && pending!=null) {
                                TimeDisplay(current.rule.time,large=task==null)
                                Text(current.rule.label.ifBlank {getString(R.string.alarm)},style=MaterialTheme.typography.titleLarge)
                                if(error) Text(stringResource(R.string.action_error),color=MaterialTheme.colorScheme.error)
                                if(task!=null && policy!=null) {
                                    Text(stringResource(R.string.task_quiet_hint))
                                    ChallengeContent(policy,task,busy) {answer(task,it)}
                                    if(task.satisfied(policy)) Button(enabled=!busy,onClick={act(task.action)}) {Text(stringResource(R.string.task_continue))}
                                    TextButton(enabled=!busy,onClick={act(ChallengeAction.DISMISS)}) {Text(stringResource(R.string.task_switch_dismiss))}
                                    if(pending.snoozes<current.rule.snoozeLimit) TextButton(enabled=!busy,onClick={act(ChallengeAction.SNOOZE)}) {Text(stringResource(R.string.task_switch_snooze))}
                                } else {
                                    if(pending.snoozes<current.rule.snoozeLimit) {
                                        Button(enabled=!busy,onClick={act(ChallengeAction.SNOOZE)},modifier=Modifier.fillMaxWidth().heightIn(min=64.dp)) {
                                            Text(stringResource(R.string.snooze_minutes,current.rule.snoozeMinutes),style=MaterialTheme.typography.titleLarge)
                                        }
                                        Text(pluralStringResource(R.plurals.snoozes_left,current.rule.snoozeLimit-pending.snoozes,current.rule.snoozeLimit-pending.snoozes))
                                    }
                                    FilledTonalButton(enabled=!busy,onClick={act(ChallengeAction.DISMISS)},modifier=Modifier.fillMaxWidth().heightIn(min=64.dp)) {
                                        Text(stringResource(R.string.dismiss),style=MaterialTheme.typography.titleLarge)
                                    }
                                }
                            } else CircularProgressIndicator()
                        }
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while(isActive) {
                    if(!busy) try {
                        val generation=intentGeneration;val wanted=preferred;val action=requested
                        val active=withContext(Dispatchers.IO) {
                            val engine=AlarmGraph.engine(this@RingingActivity)
                            var result=engine.ringing().let {list->if(wanted==null) list.firstOrNull() else list.find {it.pending?.token==wanted}}
                            if(action!=null && result?.rule?.challenge!=null) {
                                val token=result.pending!!.token
                                engine.beginChallenge(token,action)
                                result=engine.ringing().find {it.pending?.token==token}
                            }
                            result
                        }
                        if(generation==intentGeneration) {
                            requested=null
                            if(active==null) {finish();return@repeatOnLifecycle}
                            entry=active
                            val p=active.pending;val policy=active.rule.challenge;val task=p?.challenge
                            ChallengeVisibility.update(p?.token?.takeIf {resumed && task!=null && policy!=null && !task.satisfied(policy)})
                        }
                    } catch(e: CancellationException) {throw e} catch(_: Exception) {error=true}
                    delay(1000)
                }
            }
        }
    }
    override fun onNewIntent(intent: Intent) {super.onNewIntent(intent);setIntent(intent);readIntent(intent)}
    private fun readIntent(intent: Intent) {
        preferred=intent.getStringExtra(AlarmIntents.TOKEN)
        requested=when(intent.action) {AlarmIntents.SNOOZE->ChallengeAction.SNOOZE;AlarmIntents.DISMISS->ChallengeAction.DISMISS;else->null}
        intentGeneration++;entry=null;error=false
    }
    override fun onResume() {super.onResume();resumed=true}
    override fun onPause() {resumed=false;ChallengeVisibility.update(null);super.onPause()}
    override fun onDestroy() {if(ChallengeVisibility.token==entry?.pending?.token) ChallengeVisibility.update(null);super.onDestroy()}
    private fun openPhone() {
        // Public API only: never place a call, request CALL_PHONE or hide system emergency controls.
        runCatching {startActivity(Intent(Intent.ACTION_DIAL))}.onFailure {phoneError=true}
    }
    private fun answer(state: WakeChallenge,response: ChallengeResponse) {
        val current=entry ?: return;val token=current.pending!!.token
        if(busy) return;busy=true;error=false
        lifecycleScope.launch {
            try {
                val updated=withContext(Dispatchers.IO) {AlarmGraph.engine(this@RingingActivity).answerChallenge(token,state.id,state.revision,response)}
                if(updated!=null) {
                    entry=current.copy(pending=requireNotNull(current.pending).copy(challenge=updated))
                    if(updated.satisfied(requireNotNull(current.rule.challenge))) finishAction(token,updated.action)
                }
            } catch(e: CancellationException) {throw e} catch(_: Exception) {error=true}
            finally {if(response is ChallengeResponse.Voiceover) response.samples.fill(0);busy=false}
        }
    }
    private fun act(action: ChallengeAction) {
        val current=entry ?: return;val token=current.pending!!.token
        if(busy) return;busy=true;error=false
        lifecycleScope.launch {
            try {
                val state=withContext(Dispatchers.IO) {AlarmGraph.engine(this@RingingActivity).beginChallenge(token,action)}
                if(state!=null) entry=current.copy(pending=requireNotNull(current.pending).copy(challenge=state))
                if(current.rule.challenge==null || state?.satisfied(requireNotNull(current.rule.challenge))==true) finishAction(token,action)
            } catch(e: CancellationException) {throw e} catch(_: Exception) {error=true}
            finally {busy=false}
        }
    }
    private suspend fun finishAction(token: String,action: ChallengeAction) {
        val done=withContext(Dispatchers.IO) {val engine=AlarmGraph.engine(this@RingingActivity)
            if(action==ChallengeAction.SNOOZE) engine.snooze(token) else engine.dismiss(token)}
        if(done) {
            ChallengeVisibility.update(null);RingingService.refresh(this)
            preferred=null;requested=null;intentGeneration++
            val other=withContext(Dispatchers.IO) {AlarmGraph.engine(this@RingingActivity).ringing().firstOrNull()}
            if(other==null) finish() else entry=other
        } else error=true
    }
}
