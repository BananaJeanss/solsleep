// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.challenge

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.media.*
import android.nfc.NfcAdapter
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.bananajeans.solsleep.R
import dev.bananajeans.solsleep.alarm.RingingService
import dev.bananajeans.solsleep.core.*
import kotlinx.coroutines.*
import kotlin.math.*

internal tailrec fun Context.activity(): Activity=when(this) {is Activity->this;is ContextWrapper->baseContext.activity();else->error("Activity required")}

@Composable internal fun NfcTask(busy: Boolean,onResponse: (ChallengeResponse)->Unit) {
    NfcReader(enabled=!busy,onTag={onResponse(ChallengeResponse.Nfc(it))})
}
@Composable fun NfcReader(enabled: Boolean,onTag: (String)->Unit) {
    val context=LocalContext.current;val activity=context.activity();val owner=LocalLifecycleOwner.current
    val adapter=remember(activity) {NfcAdapter.getDefaultAdapter(activity)}
    val latest by rememberUpdatedState(onTag)
    var available by remember {mutableStateOf(adapter?.isEnabled==true)}
    DisposableEffect(activity,owner,enabled) {
        var active=false
        fun stop() {active=false;runCatching {adapter?.disableReaderMode(activity)}}
        fun start() {
            available=adapter?.isEnabled==true
            if(enabled && available) runCatching {
                active=true
                adapter?.enableReaderMode(activity,{tag->
                    val hash=runCatching {WakeChallenges.tagHash(tag.id)}.getOrNull()
                    if(hash!=null) activity.runOnUiThread {if(active && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) latest(hash)}
                },NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or
                    NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,null)
            }.onFailure {available=false}
        }
        val observer=LifecycleEventObserver {_,event->if(event==Lifecycle.Event.ON_RESUME) start() else if(event==Lifecycle.Event.ON_PAUSE) stop()}
        owner.lifecycle.addObserver(observer)
        if(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) start()
        onDispose {owner.lifecycle.removeObserver(observer);stop()}
    }
    Text(stringResource(if(available) R.string.task_scan_tag else R.string.task_nfc_unavailable))
}

@Composable internal fun MicrophoneTask(policy: ChallengePolicy,kind: ChallengeKind,busy: Boolean,onResponse: (ChallengeResponse)->Unit) {
    val context=LocalContext.current;val owner=LocalLifecycleOwner.current;
    var recording by remember {mutableStateOf(false)};var playing by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf(false)};var score by remember {mutableStateOf<Int?>(null)}
    val latest by rememberUpdatedState(onResponse)
    val jobs=remember(owner) {VisibleChallengeJobs(owner.lifecycle)}
    var permissionReady by remember {mutableStateOf(false)}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {granted->
        if(granted && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) recording=true
        else if(granted) permissionReady=true else error=true
    }
    DisposableEffect(owner,jobs) {
        val observer=LifecycleEventObserver {_,event->if(event==Lifecycle.Event.ON_PAUSE || event==Lifecycle.Event.ON_STOP) {recording=false;playing=false}}
        owner.lifecycle.addObserver(observer);onDispose {owner.lifecycle.removeObserver(observer);jobs.close()}
    }
    LaunchedEffect(recording) {
        if(!recording) return@LaunchedEffect
        val job=currentCoroutineContext().job
        error=false;permissionReady=false
        try {
            if(!jobs.track(0,job)) {recording=false;return@LaunchedEffect}
            val samples=withContext(Dispatchers.IO) {captureVoice(context)}
            try {
            if(kind==ChallengeKind.VOICEOVER) {
                val cleaned=VoiceMatch.suppressBaseline(samples)
                try {
                    val value=withContext(Dispatchers.Default) {VoiceMatch.score(requireNotNull(policy.voiceClip).samples(),cleaned)}
                    score=value;latest(ChallengeResponse.Voiceover(cleaned.copyOf()))
                } finally {cleaned.fill(0)}
            } else {
                val baseline=rmsDb(samples,0,4000)
                val frames=(4000 until samples.size-800 step 800).map {rmsDb(samples,it,it+800)}
                val threshold=listOf(-34.0,-30.0,-26.0)[policy.difficulty.ordinal]
                val delta=listOf(8.0,10.0,12.0)[policy.difficulty.ordinal]
                var run=0;var best=0
                frames.forEach {if(it>=threshold && it-baseline>=delta && it< -1) run++ else run=0;best=maxOf(best,run)}
                latest(ChallengeResponse.Voice(best*100L,baseline,frames.filter {it>=threshold && it-baseline>=delta}.average()))
            }
            } finally {samples.fill(0)}
        } catch(e: CancellationException) {throw e} catch(_: Exception) {error=true}
        finally {if(jobs.untrack(0,job)) recording=false}
    }
    LaunchedEffect(playing) {
        if(playing) {
            val job=currentCoroutineContext().job
            try {if(!jobs.track(1,job)) {playing=false;return@LaunchedEffect}
                withContext(Dispatchers.IO) {playVoice(context,requireNotNull(policy.voiceClip).samples())}}
            catch(e: CancellationException) {throw e} catch(_: Exception) {error=true} finally {if(jobs.untrack(1,job)) playing=false}
        }
    }
    Text(stringResource(if(kind==ChallengeKind.VOICEOVER) R.string.task_voiceover_hint else R.string.task_voice_hint))
    if(kind==ChallengeKind.VOICEOVER) {
        Text(requireNotNull(policy.voiceClip).title)
        OutlinedButton(onClick={playing=!playing},enabled=!busy && !recording) {Text(stringResource(if(playing) R.string.task_stop_audio else R.string.task_reference))}
        score?.let {Text(stringResource(R.string.task_score,it,VoiceMatch.threshold(policy.difficulty)))}
    }
    if(permissionReady) Text(stringResource(R.string.task_permission_ready))
    if(error) Text(stringResource(R.string.task_mic_error),color=MaterialTheme.colorScheme.error)
    Button(onClick={
        if(recording) recording=false
        else if(ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) recording=true
        else permission.launch(Manifest.permission.RECORD_AUDIO)
    },enabled=!busy && !playing) {Text(stringResource(if(recording) R.string.task_recording else R.string.task_record))}
}
private fun rmsDb(values: ShortArray,from: Int,to: Int): Double {
    var sum=0.0;for(i in from until to) {val v=values[i]/32768.0;sum+=v*v}
    return 10*log10((sum/(to-from)).coerceAtLeast(1e-10))
}

/** Activity-only, six seconds maximum. Never background capture, never files, never networking. */
private suspend fun captureVoice(context: Context): ShortArray {
    check(ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)
    val minimum=AudioRecord.getMinBufferSize(8000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
    check(minimum>0)
    val builder=AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
        .setAudioFormat(AudioFormat.Builder().setSampleRate(8000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
        .setBufferSizeInBytes(maxOf(minimum,8000))
    if(Build.VERSION.SDK_INT>=30) builder.setPrivacySensitive(true)
    // Callers verify runtime permission immediately before the user-started coroutine.
    val recorder=builder.build();val samples=ShortArray(48000);var count=0
    try {
        check(recorder.state==AudioRecord.STATE_INITIALIZED);recorder.startRecording()
        val start=SystemClock.elapsedRealtime();var last=start
        while(count<samples.size) {
            currentCoroutineContext().ensureActive()
            val now=SystemClock.elapsedRealtime();check(now-start<=6500 && now-last<=500)
            val n=recorder.read(samples,count,minOf(800,samples.size-count),AudioRecord.READ_NON_BLOCKING)
            check(n>=0)
            if(n>0) {count+=n;last=now} else delay(10)
        }
        check(recorder.activeRecordingConfiguration?.isClientSilenced==false)
        return samples
    } catch(e: Exception) {samples.fill(0);throw e}
    finally {runCatching {recorder.stop()};recorder.release()}
}
private suspend fun playVoice(context: Context,samples: ShortArray) {
    val manager=context.getSystemService(AudioManager::class.java)
    val attributes=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    val lost=java.util.concurrent.atomic.AtomicBoolean(false)
    val focus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes).setOnAudioFocusChangeListener {if(it<0) lost.set(true)}.build()
    val mix=RingingService.running
    var track: AudioTrack?=null
    try {
        if(mix) check(RingingService.mayMixReference())
        else check(manager.requestAudioFocus(focus)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val output=AudioTrack.Builder().setAudioAttributes(attributes)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(8000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(samples.size*2).build()
        track=output
        check(output.state==AudioTrack.STATE_INITIALIZED && output.write(samples,0,samples.size)==samples.size)
        output.setVolume(.35f);output.play()
        val end=SystemClock.elapsedRealtime()+samples.size*1000L/8000+150
        while(SystemClock.elapsedRealtime()<end) {check(if(mix) RingingService.mayMixReference() else !lost.get());delay(25)}
    } finally {
        runCatching {track?.stop()};track?.release();if(!mix) manager.abandonAudioFocusRequest(focus);samples.fill(0)
    }
}
