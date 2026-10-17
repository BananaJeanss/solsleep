@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.ui

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.bananajeans.solsleep.R
import dev.bananajeans.solsleep.core.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit

@Composable fun JournalHome(padding: PaddingValues,model: JournalViewModel=viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    var editing by rememberSaveable {mutableStateOf<String?>(null)}
    var editor by rememberSaveable {mutableStateOf(false)}
    var deleting by remember {mutableStateOf<SleepRecord?>(null)}
    val create=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {it?.let(model::export)}
    val open=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {it?.let(model::inspect)}
    val locale=LocalConfiguration.current.locales[0]
    val formatter=remember(locale) {DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM,FormatStyle.SHORT).withLocale(locale)}
    LazyColumn(Modifier.fillMaxSize().padding(padding).semantics {testTag="journal_home"},
        contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item {
            Text(stringResource(R.string.journal_title),style=MaterialTheme.typography.headlineLarge)
            Text(stringResource(R.string.journal_explanation),style=MaterialTheme.typography.bodyMedium)
        }
        item {Button(onClick={editing=null;editor=true},enabled=!state.busy,modifier=Modifier.fillMaxWidth().semantics {testTag="journal_add"}) {
            AppIcon(R.drawable.ic_plus,null);Spacer(Modifier.width(8.dp));Text(stringResource(R.string.journal_add))
        }}
        if(state.busy) item {LinearProgressIndicator(Modifier.fillMaxWidth())}
        state.error?.let {message->item {
            Text(stringResource(message),color=MaterialTheme.colorScheme.error)
            TextButton(onClick=model::reload,enabled=!state.busy) {Text(stringResource(R.string.retry))}
        }}
        state.notice?.let {message->item {Text(stringResource(message))}}
        if(state.records.isEmpty() && !state.busy && state.error==null) item {Text(stringResource(R.string.journal_empty))}
        items(state.records,key={it.id}) {record->
            Card(onClick={editing=record.id;editor=true},enabled=!state.busy,modifier=Modifier.semantics {testTag="journal_${record.id}"}) {
                Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(formatter.format(record.start.atZone(ZoneId.of(record.zone))),style=MaterialTheme.typography.titleMedium)
                    Text(formatter.format(record.end.atZone(ZoneId.of(record.zone))))
                    val minutes=record.reportedDuration.toMinutes()
                    Text(stringResource(R.string.journal_duration,minutes/60,minutes%60))
                    Text(record.zone,style=MaterialTheme.typography.labelMedium)
                    record.rating?.let {Text(stringResource(R.string.journal_rating_value,it))}
                    if(record.note.isNotBlank()) Text(record.note,maxLines=3,overflow=TextOverflow.Ellipsis)
                    if(record.tags.isNotEmpty()) Text(record.tags.sorted().joinToString(" · "))
                    Text(stringResource(R.string.journal_revision,record.revision),style=MaterialTheme.typography.labelSmall)
                    TextButton(onClick={model.showHistory(record.id)},enabled=!state.busy) {Text(stringResource(R.string.journal_history))}
                    TextButton(onClick={deleting=record},enabled=!state.busy) {Text(stringResource(R.string.delete))}
                }
            }
        }
        item {
            HorizontalDivider();Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.journal_portability),style=MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.journal_archive_detail),style=MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick={create.launch("solsleep-journal-${LocalDate.now()}.json")},enabled=!state.busy,
                modifier=Modifier.fillMaxWidth()) {Text(stringResource(R.string.journal_export))}
            OutlinedButton(onClick={open.launch(arrayOf("application/json","application/octet-stream"))},enabled=!state.busy,
                modifier=Modifier.fillMaxWidth()) {Text(stringResource(R.string.journal_import))}
        }
    }
    if(editor) {
        val existing=state.records.firstOrNull {it.id==editing}
        if(editing==null || existing!=null) JournalEditor(existing,state.busy,state.error,
            onClose={if(!state.busy) editor=false},onSave={r->model.save(r) {editor=false}})
    }
    deleting?.let {record->AlertDialog(onDismissRequest={deleting=null},title={Text(stringResource(R.string.journal_delete_title))},
        text={Text(stringResource(R.string.journal_delete_body))},
        confirmButton={TextButton(onClick={model.delete(record);deleting=null}) {Text(stringResource(R.string.delete))}},
        dismissButton={TextButton(onClick={deleting=null}) {Text(stringResource(R.string.cancel))}})}
    state.history?.let {history->
        AlertDialog(onDismissRequest=model::closeHistory,title={Text(stringResource(R.string.journal_history))},
            text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.journal_history_hint))
                history.asReversed().forEach {revision->
                    Text(stringResource(R.string.journal_revision,revision.revision),style=MaterialTheme.typography.titleSmall)
                    Text(formatter.format(revision.start.atZone(ZoneId.of(revision.zone)))+" – "+formatter.format(revision.end.atZone(ZoneId.of(revision.zone))))
                    if(revision.note.isNotBlank()) Text(revision.note,maxLines=3,overflow=TextOverflow.Ellipsis)
                    if(revision.revision!=history.last().revision) OutlinedButton(onClick={model.restoreRevision(revision)},
                        enabled=!state.busy,modifier=Modifier.semantics {testTag="restore_revision_${revision.revision}"}) {
                        Text(stringResource(R.string.journal_use_revision,revision.revision))
                    }
                    HorizontalDivider()
                }
                state.error?.let {Text(stringResource(it),color=MaterialTheme.colorScheme.error)}
            }},confirmButton={TextButton(onClick=model::closeHistory,enabled=!state.busy) {Text(stringResource(R.string.close))}})
    }
    state.preview?.let {preview->
        AlertDialog(onDismissRequest=model::closePreview,title={Text(stringResource(R.string.journal_import))},
            text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.journal_preview,preview.records.map {it.id}.distinct().size,preview.records.size,state.existing))
                Text(stringResource(R.string.journal_restore_rule))
                state.error?.let {Text(stringResource(it),color=MaterialTheme.colorScheme.error)}
            }},
            confirmButton={TextButton(onClick=model::restore,enabled=!state.busy) {Text(stringResource(R.string.journal_restore))}},
            dismissButton={TextButton(onClick=model::closePreview,enabled=!state.busy) {Text(stringResource(R.string.cancel))}})
    }
}

@Composable private fun JournalEditor(existing: SleepRecord?,busy: Boolean,error: Int?,onClose: ()->Unit,onSave: (SleepRecord)->Unit) {
    val basePayload=rememberSaveable(existing?.id) {existing?.let(SleepRecordCodec::encode) ?: ""}
    val base=remember(basePayload) {basePayload.takeIf {it.isNotEmpty()}?.let {SleepRecordCodec.decode(it)}}
    val zoneName=rememberSaveable(existing?.id) {base?.zone ?: ZoneId.systemDefault().id}
    val zone=remember(zoneName) {ZoneId.of(zoneName)}
    val now=remember {Instant.now().truncatedTo(ChronoUnit.MINUTES)}
    var start by rememberSaveable(existing?.id) {mutableStateOf((base?.start ?: now.minus(8,ChronoUnit.HOURS)).atZone(zone).toLocalDateTime().toString())}
    var end by rememberSaveable(existing?.id) {mutableStateOf((base?.end ?: now).atZone(zone).toLocalDateTime().toString())}
    var startOffset by rememberSaveable(existing?.id) {mutableStateOf(base?.start?.atZone(zone)?.offset?.id)}
    var endOffset by rememberSaveable(existing?.id) {mutableStateOf(base?.end?.atZone(zone)?.offset?.id)}
    var note by rememberSaveable(existing?.id) {mutableStateOf(base?.note ?: "")}
    var rating by rememberSaveable(existing?.id) {mutableIntStateOf(base?.rating ?: 0)}
    var tags by rememberSaveable(existing?.id) {mutableStateOf(base?.tags?.sorted()?.joinToString(", ") ?: "")}
    var picker by rememberSaveable {mutableIntStateOf(0)} // 1/2 start date/time, 3/4 end date/time
    val startLocal=LocalDateTime.parse(start);val endLocal=LocalDateTime.parse(end)
    val startInstant=runCatching {reportedInstant(startLocal,zone,startOffset?.let(ZoneOffset::of))}.getOrNull()
    val endInstant=runCatching {reportedInstant(endLocal,zone,endOffset?.let(ZoneOffset::of))}.getOrNull()
    val cleanTags=tags.split(',').map {it.trim()}.filter {it.isNotEmpty()}.toSet()
    val valid=startInstant!=null && endInstant!=null && endInstant>startInstant &&
        Duration.between(startInstant,endInstant)<=Duration.ofDays(7) &&
        startInstant.atOffset(ZoneOffset.UTC).year in 1900..9999 && endInstant.atOffset(ZoneOffset.UTC).year in 1900..9999 && note.length<=16000 &&
        cleanTags.size<=32 && cleanTags.all {it.length<=40 && '\u0000' !in it} && '\u0000' !in note
    val locale=LocalConfiguration.current.locales[0]
    val dateFormat=DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
    val timeFormat=DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
    ModalBottomSheet(onDismissRequest=onClose,sheetState=rememberBottomSheetState(initialValue=SheetValue.Hidden,
        enabledValues=setOf(SheetValue.Hidden,SheetValue.Expanded))) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp).imePadding(),
            verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(stringResource(if(existing==null) R.string.journal_add else R.string.journal_edit),style=MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.journal_zone,zone.id),style=MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.journal_start),style=MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick={picker=1},enabled=!busy,modifier=Modifier.fillMaxWidth()) {Text(dateFormat.format(startLocal))}
            OutlinedButton(onClick={picker=2},enabled=!busy,modifier=Modifier.fillMaxWidth()) {Text(timeFormat.format(startLocal))}
            OffsetChoice(startLocal,zone,startOffset) {startOffset=it}
            Text(stringResource(R.string.journal_end),style=MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick={picker=3},enabled=!busy,modifier=Modifier.fillMaxWidth()) {Text(dateFormat.format(endLocal))}
            OutlinedButton(onClick={picker=4},enabled=!busy,modifier=Modifier.fillMaxWidth()) {Text(timeFormat.format(endLocal))}
            OffsetChoice(endLocal,zone,endOffset) {endOffset=it}
            if(!valid) Text(stringResource(R.string.journal_invalid),color=MaterialTheme.colorScheme.error)
            Text(stringResource(R.string.journal_rating),style=MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                (0..5).forEach {value->FilterChip(selected=rating==value,onClick={rating=value},enabled=!busy,
                    label={Text(if(value==0) stringResource(R.string.journal_unrated) else value.toString())})}
            }
            OutlinedTextField(note,{note=it},label={Text(stringResource(R.string.journal_note))},enabled=!busy,
                modifier=Modifier.fillMaxWidth().semantics {testTag="journal_note"},minLines=3,isError=note.length>16000)
            OutlinedTextField(tags,{tags=it},label={Text(stringResource(R.string.journal_tags))},enabled=!busy,
                supportingText={Text(stringResource(R.string.journal_tags_hint))},modifier=Modifier.fillMaxWidth())
            error?.let {Text(stringResource(it),color=MaterialTheme.colorScheme.error)}
            Button(onClick={
                val modified=maxOf(Instant.now().truncatedTo(ChronoUnit.MILLIS),base?.modifiedAt ?: Instant.MIN)
                onSave(SleepRecord(id=base?.id ?: java.util.UUID.randomUUID().toString(),revision=(base?.revision ?: 0)+1,
                    createdAt=base?.createdAt ?: modified,modifiedAt=modified,start=requireNotNull(startInstant),
                    end=requireNotNull(endInstant),zone=zone.id,note=note,rating=rating.takeIf {it!=0},tags=cleanTags))
            },enabled=valid && !busy,modifier=Modifier.fillMaxWidth().semantics {testTag="journal_save"}) {Text(stringResource(R.string.save))}
            TextButton(onClick=onClose,enabled=!busy,modifier=Modifier.fillMaxWidth()) {Text(stringResource(R.string.cancel))}
        }
    }
    if(picker!=0) {
        val local=if(picker<=2) startLocal else endLocal
        if(picker%2==1) {
            val state=rememberDatePickerState(initialSelectedDateMillis=local.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),yearRange=1900..9999)
            DatePickerDialog(onDismissRequest={picker=0},confirmButton={TextButton(onClick={
                state.selectedDateMillis?.let {millis->
                    val changed=local.with(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
                    if(picker==1) {start=changed.toString();startOffset=null} else {end=changed.toString();endOffset=null}
                };picker=0
            }) {Text(stringResource(R.string.save))}},dismissButton={TextButton(onClick={picker=0}) {Text(stringResource(R.string.cancel))}}) {
                DatePicker(state)
            }
        } else {
            val state=rememberTimePickerState(local.hour,local.minute,android.text.format.DateFormat.is24HourFormat(androidx.compose.ui.platform.LocalContext.current))
            AlertDialog(onDismissRequest={picker=0},title={Text(stringResource(if(picker==2) R.string.journal_start else R.string.journal_end))},
                text={TimeInput(state)},confirmButton={TextButton(onClick={
                    val changed=local.withHour(state.hour).withMinute(state.minute).withSecond(0).withNano(0)
                    if(picker==2) {start=changed.toString();startOffset=null} else {end=changed.toString();endOffset=null};picker=0
                }) {Text(stringResource(R.string.save))}},dismissButton={TextButton(onClick={picker=0}) {Text(stringResource(R.string.cancel))}})
        }
    }
}

@Composable private fun OffsetChoice(local: LocalDateTime,zone: ZoneId,selected: String?,onSelect: (String)->Unit) {
    val offsets=zone.rules.getValidOffsets(local)
    if(offsets.isEmpty()) Text(stringResource(R.string.journal_dst_gap),color=MaterialTheme.colorScheme.error)
    if(offsets.size>1) {
        Text(stringResource(R.string.journal_dst_overlap))
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            offsets.forEach {offset->FilterChip(selected=selected==offset.id,onClick={onSelect(offset.id)},label={Text("UTC${offset.id}")})}
        }
    }
}
