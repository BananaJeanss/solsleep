// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.challenge

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.bananajeans.solsleep.R
import dev.bananajeans.solsleep.core.*
import kotlinx.coroutines.*
import java.time.Instant
import java.util.UUID

@Composable fun ChallengeSetting(value: ChallengePolicy?,onChange: (ChallengePolicy?)->Unit) {
    var open by remember {mutableStateOf(false)}
    OutlinedButton(onClick={open=true},modifier=Modifier.fillMaxWidth()) {
        Text(stringResource(if(value==null) R.string.task_enable else R.string.task_settings))
    }
    if(value!=null) Text(pluralStringResource(R.plurals.task_summary,value.rounds,value.rounds,stringResource(difficultyName(value.difficulty))))
    if(open) ChallengeSettings(value,onClose={open=false},onSave={onChange(it);open=false})
}

@Composable private fun ChallengeSettings(initial: ChallengePolicy?,onClose: ()->Unit,onSave: (ChallengePolicy?)->Unit) {
    val context=LocalContext.current;val resources=LocalResources.current;val scope=rememberCoroutineScope()
    var json by androidx.compose.runtime.saveable.rememberSaveable {mutableStateOf(ChallengePolicyCodec.encode(initial ?: ChallengePolicy()))}
    val policy=remember(json) {ChallengePolicyCodec.decode(json)}
    var enabled by androidx.compose.runtime.saveable.rememberSaveable {mutableStateOf(initial!=null)}
    var preview by remember {mutableStateOf(false)};var enroll by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf(false)};var working by remember {mutableStateOf(false)}
    var success by remember {mutableStateOf<Int?>(null)}
    fun update(next: ChallengePolicy) {json=ChallengePolicyCodec.encode(next)}
    val packImport=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri->
        if(uri!=null) scope.launch {
            working=true;error=false;success=null
            try {val pack=withContext(Dispatchers.IO) {context.contentResolver.openInputStream(uri)!!.use(ChallengePackCodec::read)}
                update(policy.copy(pack=pack,kinds=policy.kinds+ChallengeKind.CUSTOM));success=R.string.task_pack_imported}
            catch(e: CancellationException) {throw e} catch(_: Exception) {error=true} finally {working=false}
        }
    }
    val voiceImport=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri->
        if(uri!=null) scope.launch {
            working=true;error=false;success=null
            try {
                val clip=withContext(Dispatchers.IO) {
                    val samples=context.contentResolver.openInputStream(uri)!!.use(VoiceWav::read)
                    try {require(VoiceMatch.score(samples,samples)>=95);VoiceClip.from(resources.getString(R.string.task_own_clip),samples)}
                    finally {samples.fill(0)}
                }
                update(policy.copy(voiceClip=clip,kinds=policy.kinds+ChallengeKind.VOICEOVER));success=R.string.task_clip_imported
            } catch(e: CancellationException) {throw e} catch(_: Exception) {error=true} finally {working=false}
        }
    }
    val packExport=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {uri->
        if(uri!=null) scope.launch {
            working=true;error=false;success=null
            try {
                withContext(Dispatchers.IO) {
                    val bytes=ChallengePackCodec.encode(policy.pack ?: ChallengePackCodec.example()).toByteArray(Charsets.UTF_8)
                    requireNotNull(context.contentResolver.openOutputStream(uri,"wt")).use {it.write(bytes);it.flush()}
                    val actual=requireNotNull(context.contentResolver.openInputStream(uri)).use(ChallengePackCodec::read)
                    require(ChallengePackCodec.encode(actual).toByteArray().contentEquals(bytes))
                }
                success=R.string.task_pack_exported
            } catch(e: CancellationException) {throw e} catch(_: Exception) {error=true} finally {working=false}
        }
    }
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.task_settings),style=MaterialTheme.typography.headlineMedium)
                    Row {Checkbox(enabled,{enabled=it},modifier=Modifier.testTag("challenge_enabled"),enabled=!working);Text(stringResource(R.string.task_required),modifier=Modifier.padding(top=12.dp))}
                    Text(stringResource(R.string.task_contract))
                    Text(stringResource(R.string.task_phone_hint))
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        ChallengeDifficulty.entries.forEach {level->FilterChip(policy.difficulty==level,{update(policy.copy(difficulty=level))},enabled=!working,label={Text(stringResource(difficultyName(level)))})}
                    }
                    Text(stringResource(R.string.task_rounds,policy.rounds))
                    val roundSlider=rememberSliderState(value=policy.rounds.toFloat(),steps=10,trackRange=1f..12f)
                    Slider(state=roundSlider,onValueChange={roundSlider.value=it;update(policy.copy(rounds=it.toInt()))},enabled=!working)
                    Text(stringResource(R.string.task_quiet,policy.quietPercent))
                    val quietSlider=rememberSliderState(value=policy.quietPercent.toFloat(),steps=34,trackRange=5f..40f)
                    Slider(state=quietSlider,onValueChange={quietSlider.value=it;update(policy.copy(quietPercent=it.toInt()))},enabled=!working)
                    Text(stringResource(R.string.task_mix_hint))
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        ChallengeKind.entries.forEach {kind->
                            val available=when(kind) {ChallengeKind.NFC->policy.nfcHash!=null;ChallengeKind.CUSTOM->policy.pack!=null
                                ChallengeKind.VOICEOVER->policy.voiceClip!=null;else->true}
                            FilterChip(kind in policy.kinds,onClick={
                                val next=if(kind in policy.kinds) policy.kinds-kind else policy.kinds+kind
                                if(next.isNotEmpty()) update(policy.copy(kinds=next))
                            },enabled=available && !working,label={Text(stringResource(challengeName(kind)))})
                        }
                    }
                    OutlinedButton(onClick={enroll=!enroll},enabled=!working) {Text(stringResource(R.string.task_enroll_nfc))}
                    if(enroll) NfcReader(true) {hash->update(policy.copy(nfcHash=hash,kinds=policy.kinds+ChallengeKind.NFC));enroll=false}
                    if(policy.nfcHash!=null) Text(stringResource(R.string.task_nfc_saved))
                    HorizontalDivider()
                    Text(stringResource(R.string.task_voiceover),style=MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.task_voiceover_setup))
                    listOf(R.raw.voice_morning to R.string.voice_morning,R.raw.voice_captain to R.string.voice_captain,R.raw.voice_moon to R.string.voice_moon).forEach {(raw,title)->
                        OutlinedButton(enabled=!working,onClick={scope.launch {
                            working=true;error=false;success=null
                            try {val clip=withContext(Dispatchers.IO) {val samples=resources.openRawResource(raw).use(VoiceWav::read)
                                try {VoiceClip.from(resources.getString(title),samples)} finally {samples.fill(0)}}
                                update(policy.copy(voiceClip=clip,kinds=policy.kinds+ChallengeKind.VOICEOVER));success=R.string.task_clip_imported}
                            catch(e: CancellationException) {throw e} catch(_: Exception) {error=true} finally {working=false}
                        }}) {Text(stringResource(title))}
                    }
                    policy.voiceClip?.let {Text(stringResource(R.string.task_selected_clip,it.title))}
                    OutlinedButton(onClick={voiceImport.launch(arrayOf("audio/wav","audio/x-wav","audio/wave"))},enabled=!working) {Text(stringResource(R.string.task_import_voice))}
                    HorizontalDivider()
                    Text(stringResource(R.string.task_custom),style=MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.task_pack_hint))
                    policy.pack?.let {Text(it.title)}
                    OutlinedButton(onClick={packImport.launch(arrayOf("application/json","text/plain"))},enabled=!working) {Text(stringResource(R.string.task_import_pack))}
                    OutlinedButton(onClick={packExport.launch("solsleep-challenges.json")},enabled=!working) {Text(stringResource(R.string.task_export_pack))}
                    success?.let {Text(stringResource(it))}
                    if(error) Text(stringResource(R.string.task_import_error),color=MaterialTheme.colorScheme.error)
                    if(working) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Button(onClick={preview=true},enabled=!working) {Text(stringResource(R.string.task_preview))}
                }
                FlowRow(Modifier.fillMaxWidth().padding(16.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick=onClose,enabled=!working) {Text(stringResource(R.string.cancel))}
                    Button(onClick={onSave(if(enabled) policy else null)},enabled=!working,modifier=Modifier.testTag("challenge_save")) {Text(stringResource(R.string.save))}
                }
            }
        }
    }
    if(preview) ChallengePreview(policy) {preview=false}
}
@Composable fun ChallengePreview(policy: ChallengePolicy,onClose: ()->Unit) {
    var state by remember(policy) {mutableStateOf(WakeChallenge(UUID.randomUUID().toString(),java.security.SecureRandom().nextLong(),policy.fingerprint(),ChallengeAction.DISMISS,Instant.ofEpochMilli(System.currentTimeMillis())))}
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(24.dp)) {
                Text(stringResource(R.string.task_rehearsal),style=MaterialTheme.typography.titleLarge)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical=16.dp)) {
                    ChallengeContent(policy,state,false) {response->try {state=WakeChallenges.respond(policy,state,response)} finally {if(response is ChallengeResponse.Voiceover) response.samples.fill(0)}}
                }
                Button(onClick=onClose,modifier=Modifier.fillMaxWidth()) {Text(stringResource(R.string.close))}
            }
        }
    }
}
