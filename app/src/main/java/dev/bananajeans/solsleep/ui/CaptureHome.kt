@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.*
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.bananajeans.solsleep.R
import dev.bananajeans.solsleep.capture.CaptureRuntime
import dev.bananajeans.solsleep.core.*
import kotlinx.coroutines.delay
import java.util.UUID
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable fun CaptureHome(onBack: ()->Unit,model: CaptureViewModel=viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val serviceError by CaptureRuntime.error.collectAsStateWithLifecycle()
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(model,lifecycle) {lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {while(true) {model.reload();delay(2000)}}}
    var consent by rememberSaveable {mutableStateOf(false)}
    var deleting by remember {mutableStateOf<CaptureSession?>(null)}
    var exportId by rememberSaveable {mutableStateOf<String?>(null)}
    var discard by rememberSaveable {mutableStateOf(false)}
    var showCoverage by rememberSaveable {mutableStateOf(false)}
    val create=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) {uri->
        val id=exportId;exportId=null
        if(uri!=null && id!=null) model.export(UUID.fromString(id),uri)
    }
    val open=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri->if(uri!=null) model.stage(uri)}
    val exportDeletions=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) {uri->
        if(uri!=null) model.exportDeletionHistory(uri)
    }
    val openDeletions=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri->
        if(uri!=null) model.inspectDeletionHistory(uri)
    }
    val notificationPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {model.reload()}
    val context=LocalContext.current
    val locale=LocalConfiguration.current.locales[0]
    val use24=android.text.format.DateFormat.is24HourFormat(context)
    val formatter=remember(locale,use24) {DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale,if(use24) "yMMMdHm" else "yMMMdhm"),locale)}
    state.deletionPreview?.let {preview->
        RecordingDeletionPreviewScreen(preview,state.deletionMergePreview,state.busy,state.deletionImportError,model::importDeletionHistory,model::closeDeletionPreview)
        return
    }
    if(state.exportHistorySession!=null) {
        MotionExportHistoryScreen(state.exportHistory,state.exportHistoryUnavailable,model::closeExportHistory)
        return
    }
    if(showCoverage && state.details!=null) {
        CaptureCoverageScreen(requireNotNull(state.details)) {showCoverage=false;model.closeDetails()}
        return
    }
    Scaffold(topBar={TopAppBar(title={Text(stringResource(R.string.capture_title))},navigationIcon={
        IconButton(onClick=onBack) {AppIcon(R.drawable.ic_close,stringResource(R.string.close))}
    })}) {padding->
        LazyColumn(Modifier.fillMaxSize().padding(padding).semantics {testTag="capture_home"},
            contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            item {
                Text(stringResource(R.string.capture_heading),style=MaterialTheme.typography.headlineMedium)
                Text(stringResource(R.string.capture_explanation))
            }
            if(state.unavailable) item {Text(stringResource(R.string.capture_unavailable),color=MaterialTheme.colorScheme.error)}
            state.message?.let {message->item {Text(stringResource(message),Modifier.semantics {testTag="motion_archive_message"})}}
            if(state.error || serviceError) item {Text(stringResource(R.string.capture_error),color=MaterialTheme.colorScheme.error)}
            if(!state.notifications) item {
                Text(stringResource(R.string.capture_notifications_needed))
                if(Build.VERSION.SDK_INT>=33) Button(onClick={notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)}) {Text(stringResource(R.string.capture_allow_notifications))}
                TextButton(onClick={context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,context.packageName))}) {Text(stringResource(R.string.capture_notification_settings))}
            }
            if(!state.sensorAvailable && !state.unavailable) item {Text(stringResource(R.string.capture_no_sensor))}
            if(state.active==null) item {
                Button(onClick={consent=true},enabled=!state.busy && !state.unavailable && state.notifications && state.sensorAvailable,
                    modifier=Modifier.fillMaxWidth().semantics {testTag="capture_start"}) {Text(stringResource(R.string.capture_start))}
            }
            state.active?.let {active->item {
                CaptureCard(active,formatter,enabled=!state.busy && !state.unavailable,
                    onResume={model.resume(active)},onPause={model.pause(active)},onStop={model.stop(active)},onInspect={model.inspect(active)},onDelete={},onExport={},onExportHistory={})
            }}
            item {Text(stringResource(R.string.capture_saved),style=MaterialTheme.typography.titleLarge)}
            items(state.sessions.filter {it.id!=state.active?.id},key={it.id}) {session->
                CaptureCard(session,formatter,enabled=!state.busy && !state.unavailable,onResume={model.resume(session)},onPause={model.pause(session)},
                    onStop={model.stop(session)},onInspect={model.inspect(session)},onDelete={deleting=session},onExport={
                        exportId=session.id.toString();create.launch("solsleep-motion-${session.id}.solmotion")
                    },onExportHistory={model.showExportHistory(session.id)})
            }
            item {FlowRow(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                if(state.page>0) TextButton(onClick={model.page(-1)},enabled=!state.busy) {Text(stringResource(R.string.capture_newer))}
                if(state.older) TextButton(onClick={model.page(1)},enabled=!state.busy) {Text(stringResource(R.string.capture_older))}
                TextButton(onClick=model::reload,enabled=!state.busy) {Text(stringResource(R.string.capture_reload))}
            }}
            item {
                HorizontalDivider()
                Text(stringResource(R.string.motion_archive_title),style=MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.motion_archive_hint))
                OutlinedButton(onClick={open.launch(arrayOf("application/octet-stream"))},enabled=!state.busy && !state.recoveryPresent,
                    modifier=Modifier.fillMaxWidth().semantics {testTag="motion_import"}) {Text(stringResource(R.string.motion_import))}
                if(state.recoveryPresent) {
                    Text(stringResource(R.string.motion_recovery_hint))
                    if(state.recoveryError) Text(stringResource(R.string.motion_recovery_error),color=MaterialTheme.colorScheme.error)
                    state.pending?.let {pending->
                        val preview=pending.preview
                        Text(formatter.format(preview.recording.session.startedAt.atZone(ZoneId.systemDefault())))
                        Text(stringResource(R.string.capture_file_count,preview.chunks))
                        Text(stringResource(R.string.capture_sample_count,preview.samples))
                        Text(stringResource(R.string.capture_byte_count,preview.rawBytes))
                        if(pending.conflict) Text(stringResource(R.string.motion_restore_conflict))
                        if(state.active!=null) Text(stringResource(R.string.motion_finish_before_restore))
                        Button(onClick=model::restore,enabled=!state.busy && !pending.conflict && state.active==null,
                            modifier=Modifier.semantics {testTag="motion_restore"}) {Text(stringResource(R.string.motion_restore))}
                    }
                    TextButton(onClick={discard=true},enabled=!state.busy) {Text(stringResource(R.string.motion_discard))}
                }
            }
            item {
                HorizontalDivider()
                Text(stringResource(R.string.recording_deletions_title),style=MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.recording_deletions_hint))
                OutlinedButton(onClick={exportDeletions.launch("solsleep-recording-deletions.soldelete")},enabled=!state.busy && !state.unavailable,
                    modifier=Modifier.fillMaxWidth().semantics {testTag="recording_deletions_export"}) {
                    Text(stringResource(R.string.recording_deletions_export))
                }
                OutlinedButton(onClick={openDeletions.launch(arrayOf("application/octet-stream"))},enabled=!state.busy && !state.unavailable && state.active==null,
                    modifier=Modifier.fillMaxWidth().semantics {testTag="recording_deletions_open"}) {
                    Text(stringResource(R.string.recording_deletions_open))
                }
                if(state.active!=null) Text(stringResource(R.string.motion_finish_before_restore))
                state.deletionResult?.let {result->
                    Text(stringResource(R.string.recording_deletions_result,result.added,result.alreadyKnown,result.liveConflicts,result.identityConflicts),
                        Modifier.semantics {testTag="recording_deletions_result"})
                }
            }
            item {Text(stringResource(R.string.capture_limits),style=MaterialTheme.typography.bodySmall)}
        }
    }
    if(discard) AlertDialog(onDismissRequest={discard=false},title={Text(stringResource(R.string.motion_discard))},
        text={Text(stringResource(R.string.motion_discard_hint))},
        confirmButton={TextButton(onClick={discard=false;model.discardPending()}) {Text(stringResource(R.string.motion_discard))}},
        dismissButton={TextButton(onClick={discard=false}) {Text(stringResource(R.string.cancel))}})
    if(consent) AlertDialog(onDismissRequest={consent=false},title={Text(stringResource(R.string.capture_start))},
        text={Text(stringResource(R.string.capture_consent),Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()))},
        confirmButton={TextButton(onClick={consent=false;model.start()}) {Text(stringResource(R.string.capture_begin))}},
        dismissButton={TextButton(onClick={consent=false}) {Text(stringResource(R.string.cancel))}})
    deleting?.let {session->AlertDialog(onDismissRequest={deleting=null},title={Text(stringResource(R.string.capture_delete_title))},
        text={Text(stringResource(R.string.capture_delete_body))},
        confirmButton={TextButton(onClick={deleting=null;model.delete(session)}) {Text(stringResource(R.string.delete))}},
        dismissButton={TextButton(onClick={deleting=null}) {Text(stringResource(R.string.cancel))}})}
    state.details?.let {details->
        AlertDialog(onDismissRequest=model::closeDetails,title={Text(stringResource(R.string.capture_details))},
            text={Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.capture_file_count,details.summary.chunks))
                Text(stringResource(R.string.capture_sample_count,details.summary.samples))
                Text(stringResource(R.string.capture_byte_count,details.summary.bytes))
                Text(stringResource(R.string.capture_issue_count,details.summary.issues.size))
                if(details.coverage!=null || details.coverageUnavailable) Button(onClick={showCoverage=true},
                    modifier=Modifier.semantics {testTag="coverage_open"}) {Text(stringResource(R.string.coverage_open))}
                details.streams.forEach {stream->
                    Text(stringResource(R.string.capture_source,stream.source.name,stream.source.vendor))
                    Text(stream.provenance?.let {stringResource(R.string.coverage_requested,it.requestedPeriodMicros)}
                        ?: stringResource(R.string.capture_cadence_unknown))
                    Text(stringResource(R.string.capture_index_samples,stream.committedSamples))
                    Text(stringResource(R.string.capture_index_rejected,stream.rejectedSamples))
                    Text(stringResource(R.string.capture_index_gaps,stream.gaps))
                    Text(stringResource(R.string.capture_index_explanation))
                }
                Text(stringResource(R.string.capture_event_count,details.events.size))
                details.events.lastOrNull()?.let {Text(stringResource(reasonString(it.reason)))}
                if(details.summary.issues.isNotEmpty()) Text(stringResource(R.string.capture_retained_damage),color=MaterialTheme.colorScheme.error)
            }},confirmButton={TextButton(onClick=model::closeDetails) {Text(stringResource(R.string.close))}})
    }
}

@Composable private fun CaptureCard(session: CaptureSession,formatter: DateTimeFormatter,enabled: Boolean,
    onResume: ()->Unit,onPause: ()->Unit,onStop: ()->Unit,onInspect: ()->Unit,onDelete: ()->Unit,onExport: ()->Unit,onExportHistory: ()->Unit) {
    Card(Modifier.fillMaxWidth().semantics {testTag="capture_${session.id}"}) {
        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(formatter.format(session.startedAt.atZone(ZoneId.systemDefault())),style=MaterialTheme.typography.titleMedium)
            Text(stringResource(when(session.state) {
                CaptureState.PREPARING->R.string.capture_preparing
                CaptureState.RECORDING->R.string.capture_recording
                CaptureState.PAUSED->R.string.capture_paused
                CaptureState.INTERRUPTED->R.string.capture_interrupted
                CaptureState.COMPLETED->R.string.capture_completed
                CaptureState.FAILED->R.string.capture_failed
                CaptureState.DELETING->R.string.capture_deleting
            }),modifier=Modifier.semantics {testTag="capture_state_${session.id}"})
            if(session.state==CaptureState.INTERRUPTED) Text(stringResource(R.string.capture_gap_explanation))
            FlowRow(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                if(session.state in setOf(CaptureState.PREPARING,CaptureState.RECORDING)) TextButton(onClick=onPause,enabled=enabled) {Text(stringResource(R.string.capture_pause))}
                if(session.state in setOf(CaptureState.PAUSED,CaptureState.INTERRUPTED)) TextButton(onClick=onResume,enabled=enabled) {Text(stringResource(R.string.capture_resume))}
                if(!session.terminal) TextButton(onClick=onStop,enabled=enabled) {Text(stringResource(R.string.capture_stop))}
                if(session.state !in setOf(CaptureState.PREPARING,CaptureState.RECORDING,CaptureState.DELETING)) TextButton(onClick=onInspect,enabled=enabled) {Text(stringResource(R.string.capture_details))}
                if(session.state in setOf(CaptureState.COMPLETED,CaptureState.FAILED)) TextButton(onClick=onExport,enabled=enabled,
                    modifier=Modifier.semantics {testTag="motion_export_${session.id}"}) {Text(stringResource(R.string.motion_export))}
                if(session.state in setOf(CaptureState.COMPLETED,CaptureState.FAILED)) TextButton(onClick=onExportHistory,enabled=enabled,
                    modifier=Modifier.semantics {testTag="motion_export_history_${session.id}"}) {Text(stringResource(R.string.motion_receipts_title))}
                if(session.terminal) TextButton(onClick=onDelete,enabled=enabled) {Text(stringResource(R.string.delete))}
            }
        }
    }
}

private fun reasonString(reason: CaptureReason)=when(reason) {
    CaptureReason.LOW_SPACE->R.string.capture_low_space
    CaptureReason.MAX_DURATION->R.string.capture_time_limit
    CaptureReason.CLOCK_CHANGE->R.string.capture_clock_change
    CaptureReason.PROCESS_LOSS,CaptureReason.SERVICE_STOP->R.string.capture_process_loss
    CaptureReason.NO_SENSOR,CaptureReason.SENSOR_STALE->R.string.capture_stale
    CaptureReason.STORAGE->R.string.capture_storage_error
    else->R.string.capture_gap_explanation
}
