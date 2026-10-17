@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.ui

import android.Manifest
import android.content.*
import androidx.core.net.toUri
import android.os.Build
import android.provider.Settings
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.*
import dev.bananajeans.solsleep.R
import dev.bananajeans.solsleep.alarm.*
import dev.bananajeans.solsleep.core.*
import java.time.*
import java.time.format.*
import java.util.*
import kotlinx.coroutines.delay

@Composable fun AppIcon(id: Int,description: String?,modifier: Modifier = Modifier) {
    Icon(painterResource(id),description,modifier.size(24.dp))
}

@Composable fun AlarmHome(model: AlarmViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val context=LocalContext.current
    var tab by rememberSaveable {mutableIntStateOf(0)}
    var editingId by rememberSaveable {mutableStateOf<String?>(null)}
    var showEditor by rememberSaveable {mutableStateOf(false)}
    var showSetup by rememberSaveable {mutableStateOf(false)}
    var deleting by remember {mutableStateOf<AlarmEntry?>(null)}
    val snackbar=remember {SnackbarHostState()}
    val notice=state.notice?.let {stringResource(it)}
    LaunchedEffect(notice) {if(notice!=null) {model.consumeNotice();snackbar.showSnackbar(notice)}}
    val ordered=state.entries.sortedWith(compareBy<AlarmEntry> {!it.rule.enabled}.thenBy {it.pending?.at ?: Instant.MAX})
    val next=ordered.firstOrNull {it.rule.enabled && it.pending?.state in setOf(DeliveryState.SCHEDULED,DeliveryState.SNOOZED)}
    fun edit(rule: AlarmRule?) {editingId=rule?.id;showEditor=true}

    Scaffold(
        containerColor=MaterialTheme.colorScheme.background,
        topBar={CenterAlignedTopAppBar(
            title={Text(stringResource(R.string.app_name),fontWeight=FontWeight.SemiBold)},
            actions={IconButton(onClick={showSetup=true}) {AppIcon(R.drawable.ic_settings,stringResource(R.string.settings))}},
        )},
        bottomBar={NavigationBar(containerColor=MaterialTheme.colorScheme.surfaceContainer) {
            listOf(R.string.tonight to R.drawable.ic_moon,R.string.alarms to R.drawable.ic_alarm).forEachIndexed {i,(label,icon)->
                NavigationBarItem(selected=tab==i,onClick={tab=i},
                    icon={AppIcon(icon,null)},label={Text(stringResource(label))})
            }
        }},
        snackbarHost={SnackbarHost(snackbar)},
    ) {padding->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("alarm_home"),
            contentPadding=PaddingValues(start=24.dp,end=24.dp,top=12.dp,bottom=28.dp),
            verticalArrangement=Arrangement.spacedBy(16.dp)) {
            if(state.loading) item {LinearProgressIndicator(Modifier.fillMaxWidth())}
            if(state.error!=null) item {
                Card {Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(state.error!!))
                    Button(onClick={model.reload(reconcile=true)}) {Text(stringResource(R.string.retry))}
                }}
            } else {
                if(tab==0) item {TonightCard(next,state.entries.isEmpty(),onAdd={edit(null)})}
                if(state.entries.any {it.rule.enabled} && state.readiness?.ready==false) item {
                    Card(onClick={showSetup=true},colors=CardDefaults.cardColors(
                        containerColor=MaterialTheme.colorScheme.secondaryContainer)) {
                        Row(Modifier.padding(20.dp),verticalAlignment=Alignment.CenterVertically,
                            horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                            AppIcon(R.drawable.ic_settings,null)
                            Column {Text(stringResource(R.string.setup_needed),fontWeight=FontWeight.SemiBold)
                                Text(stringResource(R.string.setup_hint),style=MaterialTheme.typography.bodyMedium)}
                        }
                    }
                }
                if(tab==1 && ordered.isEmpty()) item {TonightCard(null,true,onAdd={edit(null)})}
                if(ordered.isNotEmpty()) item {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Text(stringResource(R.string.all_alarms),style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f))
                        FilledTonalIconButton(onClick={edit(null)},enabled=!state.busy) {
                            AppIcon(R.drawable.ic_plus,stringResource(R.string.add_alarm))
                        }
                    }
                }
                items(ordered,key={it.rule.id}) {entry->
                    AlarmCard(entry,state.busy,onEdit={edit(entry.rule)},
                        onEnabled={model.enabled(entry.rule.id,it)},
                        onSkip={model.skip(entry.rule.id,entry.rule.skipDate!=null)},
                        onDelete={deleting=entry})
                }
                if(ordered.isNotEmpty()) item {
                    Text(stringResource(R.string.private_footer),style=MaterialTheme.typography.bodySmall,
                        color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.fillMaxWidth().padding(top=12.dp),
                        textAlign=TextAlign.Center)
                }
            }
        }
    }
    if(showEditor && !state.loading && state.error==null) {
        val editing=state.entries.firstOrNull {it.rule.id==editingId}?.rule
        if(editingId==null || editing!=null) AlarmEditor(editing,state.busy,
            onDismiss={if(!state.busy) showEditor=false},onSave={rule->model.save(rule) {showEditor=false}})
    }
    if(showSetup) SetupSheet(state.readiness,onDismiss={showSetup=false},onRefresh={model.reload(reconcile=true)})
    deleting?.let {entry->
        AlertDialog(onDismissRequest={deleting=null},title={Text(stringResource(R.string.delete_title))},
            text={Text(stringResource(R.string.delete_body))},
            confirmButton={TextButton(onClick={model.delete(entry.rule.id);deleting=null}) {Text(stringResource(R.string.delete))}},
            dismissButton={TextButton(onClick={deleting=null}) {Text(stringResource(R.string.cancel))}})
    }
}

@Composable private fun TonightCard(entry: AlarmEntry?,empty: Boolean,onAdd: ()->Unit) {
    val colors=MaterialTheme.colorScheme
    val locale=LocalConfiguration.current.locales[0]
    Surface(shape=RoundedCornerShape(36.dp),color=colors.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(28.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(entry!=null) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text(stringResource(R.string.next_alarm),style=MaterialTheme.typography.labelLarge,
                        color=colors.secondary,modifier=Modifier.weight(1f))
                    Image(painterResource(R.drawable.ic_sleeping_android),null,Modifier.size(64.dp))
                }
                val zone=entry.rule.zoneId?.let(ZoneId::of) ?: ZoneId.systemDefault()
                val pending=requireNotNull(entry.pending)
                val due=pending.at.atZone(zone)
                TimeDisplay(due.toLocalTime(),large=true)
                Text(due.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale)),
                    style=MaterialTheme.typography.titleMedium)
                var now by remember {mutableStateOf(Instant.now())}
                LaunchedEffect(pending.at) {while(true) {now=Instant.now();delay(30000)}}
                val minutes=Duration.between(now,pending.at).toMinutes().coerceAtLeast(1)
                Text(stringResource(R.string.countdown,
                    stringResource(R.string.hours_minutes,minutes/60,minutes%60)),
                    style=MaterialTheme.typography.bodyLarge,color=colors.onSurfaceVariant)
                if(entry.rule.label.isNotBlank()) Text(entry.rule.label,style=MaterialTheme.typography.bodyLarge)
            } else {
                Image(painterResource(R.drawable.ic_sleeping_android),null,
                    Modifier.size(192.dp).align(Alignment.CenterHorizontally))
                Text(stringResource(if(empty) R.string.empty_title else R.string.no_enabled),
                    style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.SemiBold)
                Text(stringResource(if(empty) R.string.empty_body else R.string.no_enabled_body),
                    style=MaterialTheme.typography.bodyLarge,color=colors.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Button(onClick=onAdd,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) {
                    AppIcon(R.drawable.ic_plus,null);Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.add_alarm))
                }
            }
        }
    }
}

@Composable fun TimeDisplay(time: LocalTime,large: Boolean = false) {
    val use24=DateFormat.is24HourFormat(LocalContext.current)
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        BasicText(time.format(DateTimeFormatter.ofPattern(if(use24) "HH:mm" else "h:mm")),
            modifier=Modifier.weight(1f,fill=false),
            style=MaterialTheme.typography.displayLarge.copy(color=LocalContentColor.current,
                fontWeight=FontWeight.Medium,letterSpacing=(-2).sp,fontFeatureSettings="tnum"),
            maxLines=1,autoSize=TextAutoSize.StepBased(minFontSize=24.sp,maxFontSize=if(large) 68.sp else 42.sp))
        if(!use24) Text(time.format(DateTimeFormatter.ofPattern("a")),style=MaterialTheme.typography.titleMedium,
            modifier=Modifier.padding(bottom=if(large) 14.dp else 8.dp))
    }
}

@Composable private fun AlarmCard(entry: AlarmEntry,busy: Boolean,onEdit: ()->Unit,
    onEnabled: (Boolean)->Unit,onSkip: ()->Unit,onDelete: ()->Unit) {
    val rule=entry.rule
    val locale=LocalConfiguration.current.locales[0]
    val active=entry.pending?.state==DeliveryState.RINGING
    val switchLabel=stringResource(R.string.alarm_switch,rule.time.toString())
    Card(onClick=onEdit,shape=RoundedCornerShape(28.dp),
        colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    TimeDisplay(rule.time)
                    if(rule.label.isNotBlank()) Text(rule.label,style=MaterialTheme.typography.titleMedium)
                }
                Switch(checked=rule.enabled,onCheckedChange=onEnabled,enabled=!busy && !active,
                    modifier=Modifier.semantics {
                        contentDescription=switchLabel
                    })
            }
            val days=when(val repeat=rule.repeat) {
                is Repeat.Once -> repeat.date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
                is Repeat.Weekly -> if(repeat.days.size==7) stringResource(R.string.every_day)
                    else repeat.days.sortedBy {it.value}.joinToString(" · ") {it.getDisplayName(TextStyle.SHORT,locale)}
                else -> rule.repeat.toString()
            }
            Text(days,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(rule.enabled && entry.pending?.armed!=true && !active) Text(stringResource(R.string.not_armed),
                color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.labelLarge)
            if(active) Text(stringResource(R.string.ringing),color=MaterialTheme.colorScheme.primary)
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                if(rule.enabled && rule.repeat !is Repeat.Once && !active)
                    TextButton(onClick=onSkip,enabled=!busy,modifier=Modifier.weight(1f)) {
                        Text(stringResource(if(rule.skipDate==null) R.string.skip_next else R.string.undo_skip))
                    }
                else Spacer(Modifier.weight(1f))
                IconButton(onClick=onEdit,enabled=!busy && !active) {AppIcon(R.drawable.ic_edit,stringResource(R.string.edit_alarm))}
                IconButton(onClick=onDelete,enabled=!busy && !active) {AppIcon(R.drawable.ic_trash,stringResource(R.string.delete))}
            }
        }
    }
}

@Composable private fun AlarmEditor(rule: AlarmRule?,busy: Boolean,onDismiss: ()->Unit,onSave: (AlarmRule)->Unit) {
    val context=LocalContext.current
    val keyboard=LocalSoftwareKeyboardController.current
    val locale=LocalConfiguration.current.locales[0]
    val time=rememberTimePickerState(initialHour=rule?.time?.hour ?: 7,initialMinute=rule?.time?.minute ?: 0,
        is24Hour=DateFormat.is24HourFormat(context))
    var label by rememberSaveable {mutableStateOf(rule?.label ?: "")}
    var weekly by rememberSaveable {mutableStateOf(rule?.repeat is Repeat.Weekly)}
    var dayMask by rememberSaveable {mutableIntStateOf(
        (rule?.repeat as? Repeat.Weekly)?.days?.sumOf {1 shl (it.value-1)} ?: 31)}
    val days=DayOfWeek.entries.filter {dayMask and (1 shl (it.value-1))!=0}.toSet()
    var vibrate by rememberSaveable {mutableStateOf(rule?.vibrate ?: true)}
    var gentle by rememberSaveable {mutableStateOf((rule?.rampSeconds ?: 20)>0)}
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberBottomSheetState(initialValue=SheetValue.Hidden,enabledValues=setOf(SheetValue.Hidden,SheetValue.Expanded))) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding()
            .padding(start=24.dp,end=24.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text(stringResource(if(rule==null) R.string.new_alarm else R.string.edit_alarm),
                    style=MaterialTheme.typography.headlineMedium,modifier=Modifier.weight(1f))
                IconButton(onClick=onDismiss,enabled=!busy) {AppIcon(R.drawable.ic_close,stringResource(R.string.close))}
            }
            Box(Modifier.fillMaxWidth(),contentAlignment=Alignment.Center) {TimeInput(time)}
            OutlinedTextField(value=label,onValueChange={if(it.length<=120) label=it},
                modifier=Modifier.fillMaxWidth().testTag("alarm_label"),singleLine=true,
                keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(imeAction=androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions=androidx.compose.foundation.text.KeyboardActions(onDone={keyboard?.hide()}),
                label={Text(stringResource(R.string.label))},placeholder={Text(stringResource(R.string.label_placeholder))})
            Text(stringResource(R.string.repeat),style=MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                FilterChip(selected=!weekly,onClick={weekly=false},label={Text(stringResource(R.string.once))})
                FilterChip(selected=weekly,onClick={weekly=true},label={Text(stringResource(R.string.weekly))})
            }
            if(weekly) {
                FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp),maxItemsInEachRow=7) {
                    DayOfWeek.entries.forEach {day->
                        FilterChip(selected=day in days,onClick={dayMask=dayMask xor (1 shl (day.value-1))},
                            label={Text(day.getDisplayName(TextStyle.SHORT,locale))},
                            modifier=Modifier.semantics {contentDescription=day.getDisplayName(TextStyle.FULL,locale)})
                    }
                }
                if(days.isEmpty()) Text(stringResource(R.string.repeat_hint),color=MaterialTheme.colorScheme.error)
            }
            ToggleRow(stringResource(R.string.vibrate),null,vibrate) {vibrate=it}
            ToggleRow(stringResource(R.string.gentle),stringResource(R.string.gentle_detail),gentle) {gentle=it}
            Text(stringResource(R.string.snooze_detail),style=MaterialTheme.typography.bodyMedium,
                color=MaterialTheme.colorScheme.onSurfaceVariant)
            Button(enabled=!busy && (!weekly || days.isNotEmpty()),onClick={
                val local=LocalTime.of(time.hour,time.minute)
                val repeat=if(weekly) Repeat.Weekly(days) else Repeat.Once(Recurrence.nextOneShotDate(local,
                    Instant.now(),rule?.zoneId?.let(ZoneId::of) ?: ZoneId.systemDefault()))
                val base=rule ?: AlarmRule(UUID.randomUUID().toString(),local,repeat)
                onSave(base.copy(time=local,repeat=repeat,label=label.trim(),vibrate=vibrate,rampSeconds=if(gentle) 20 else 0))
            },modifier=Modifier.fillMaxWidth().heightIn(min=56.dp).testTag("save_alarm")) {
                Text(stringResource(R.string.save))
            }
        }
    }
}

@Composable private fun ToggleRow(title: String,detail: String?,checked: Boolean,onChange: (Boolean)->Unit) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,
        horizontalArrangement=Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f)) {Text(title,style=MaterialTheme.typography.titleMedium)
            if(detail!=null) Text(detail,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        Switch(checked,onChange,Modifier.semantics {contentDescription=title})
    }
}

@Composable private fun SetupSheet(ready: AlarmReadiness?,onDismiss: ()->Unit,onRefresh: ()->Unit) {
    val context=LocalContext.current
    val launcher=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {onRefresh()}
    var preview by remember {mutableStateOf(false)}
    var about by remember {mutableStateOf(false)}
    fun settings(action: String) {
        val intent=Intent(action)
        if(action!=Settings.ACTION_SOUND_SETTINGS) intent.data=("package:"+context.packageName).toUri()
        runCatching {context.startActivity(intent)}
    }
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberBottomSheetState(initialValue=SheetValue.Hidden,enabledValues=setOf(SheetValue.Hidden,SheetValue.Expanded))) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=24.dp)
            .padding(bottom=32.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text(stringResource(R.string.settings),style=MaterialTheme.typography.headlineMedium,modifier=Modifier.weight(1f))
                IconButton(onClick=onDismiss) {AppIcon(R.drawable.ic_close,stringResource(R.string.close))}
            }
            SetupRow(R.string.exact_title,R.string.exact_body,ready?.exact==true) {
                if(Build.VERSION.SDK_INT>=31) settings(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            }
            SetupRow(R.string.notification_title,R.string.notification_body,ready?.notifications==true) {
                if(Build.VERSION.SDK_INT>=33 && androidx.core.content.ContextCompat.checkSelfPermission(context,
                    Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                else context.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID,RingingService.CHANNEL))
            }
            SetupRow(R.string.fullscreen_title,R.string.fullscreen_body,ready?.fullScreen==true) {
                if(Build.VERSION.SDK_INT>=34) settings(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
            }
            if(ready?.audible==false) SetupRow(R.string.volume_title,R.string.volume_body,false,R.string.sound_settings) {
                settings(Settings.ACTION_SOUND_SETTINGS)
            }
            HorizontalDivider()
            Text(stringResource(R.string.test_sound_hint),style=MaterialTheme.typography.bodyMedium)
            FilledTonalButton(onClick={preview=true},modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)) {
                Text(stringResource(R.string.test_sound))
            }
            TextButton(onClick=onRefresh) {Text(stringResource(R.string.retry))}
            TextButton(onClick={about=true}) {Text(stringResource(R.string.about))}
        }
    }
    if(preview) SoundPreview {preview=false}
    if(about) AlertDialog(onDismissRequest={about=false},title={Text(stringResource(R.string.about))},
        text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.about_body));Text(stringResource(R.string.attribution),style=MaterialTheme.typography.bodySmall)
        }},confirmButton={TextButton(onClick={about=false}) {Text(stringResource(R.string.close))}})
}

@Composable private fun SetupRow(title: Int,body: Int,allowed: Boolean,actionLabel: Int = R.string.allow,onClick: ()->Unit) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {Text(stringResource(title),style=MaterialTheme.typography.titleMedium)
            Text(stringResource(body),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        if(allowed) AppIcon(R.drawable.ic_check,stringResource(R.string.enabled))
        else TextButton(onClick=onClick) {Text(stringResource(actionLabel))}
    }
}

@Composable private fun SoundPreview(onDismiss: ()->Unit) {
    val context=LocalContext.current
    val lifecycle=androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val audio=AlarmAudio(context,0)
        audio.start()
        val observer=LifecycleEventObserver {_,event-> if(event==Lifecycle.Event.ON_STOP) audio.close()}
        lifecycle.addObserver(observer)
        onDispose {lifecycle.removeObserver(observer);audio.close()}
    }
    LaunchedEffect(Unit) {delay(5000);onDismiss()}
    AlertDialog(onDismissRequest=onDismiss,title={Text(stringResource(R.string.test_sound))},
        text={Text(stringResource(R.string.test_sound_hint))},
        confirmButton={TextButton(onClick=onDismiss) {Text(stringResource(R.string.close))}})
}
