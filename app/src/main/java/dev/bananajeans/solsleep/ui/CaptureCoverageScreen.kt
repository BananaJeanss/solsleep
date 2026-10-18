@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.bananajeans.solsleep.R
import dev.bananajeans.solsleep.core.*
import java.text.NumberFormat
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Composable internal fun CaptureCoverageScreen(details: CaptureDetails,onBack: ()->Unit) {
    BackHandler(onBack=onBack)
    val locale=LocalConfiguration.current.locales[0]
    val use24=android.text.format.DateFormat.is24HourFormat(LocalContext.current)
    val formatter=remember(locale,use24) {DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale,
        if(use24) "yMMMdHmsSSS" else "yMMMdhmsSSS"),locale)}
    val report=details.coverage
    val rows=report?.streams?.associateBy {it.streamId}.orEmpty()
    var provenance by remember {mutableStateOf(false)}
    Scaffold(topBar={TopAppBar(title={Text(stringResource(R.string.coverage_title),maxLines=1,overflow=TextOverflow.Ellipsis)},navigationIcon={
        IconButton(onClick=onBack) {AppIcon(R.drawable.ic_close,stringResource(R.string.close))}
    })}) {padding->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("coverage_screen"),
            contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            item {
                Text(stringResource(R.string.coverage_heading),style=MaterialTheme.typography.headlineMedium)
                Text(stringResource(R.string.coverage_explanation))
            }
            if(details.coverageUnavailable) item {Text(stringResource(R.string.coverage_unavailable),
                Modifier.testTag("coverage_unavailable"),color=MaterialTheme.colorScheme.error)}
            report?.let {value->
                item {Text(stringResource(R.string.coverage_readings,value.samples),Modifier.testTag("coverage_total"),style=MaterialTheme.typography.titleLarge)}
                if(value.incomplete) item {Text(stringResource(R.string.coverage_incomplete),color=MaterialTheme.colorScheme.error)}
                if(value.overlappingStreams) item {Text(stringResource(R.string.coverage_overlap),color=MaterialTheme.colorScheme.error)}
                if(value.streams.isEmpty()) item {Text(stringResource(R.string.coverage_no_segments))}
            }
            itemsIndexed(details.streams,key={_,stream->stream.id}) {index,stream->
                val row=rows[stream.id]
                Card(Modifier.fillMaxWidth().testTag("coverage_segment_$index")) {
                    Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.coverage_segment,index+1),style=MaterialTheme.typography.titleLarge)
                        Text(stringResource(R.string.capture_source,stream.source.name,stream.source.vendor))
                        val originalZone=stream.provenance?.zoneId
                        val zone=originalZone?.let(ZoneId::of) ?: ZoneOffset.UTC
                        Text(if(originalZone==null) stringResource(R.string.coverage_zone_unknown) else stringResource(R.string.coverage_zone,originalZone))
                        if(row!=null) {
                            Text(stringResource(R.string.coverage_readings,row.samples))
                            row.firstNanos?.let {first->
                                val start=stream.wall.plusNanos(first-stream.elapsedNanos).atZone(zone)
                                val end=stream.wall.plusNanos(requireNotNull(row.lastNanos)-stream.elapsedNanos).atZone(zone)
                                Text(stringResource(R.string.coverage_first,formatter.format(start)))
                                Text(stringResource(R.string.coverage_last,formatter.format(end)))
                            } ?: Text(stringResource(R.string.coverage_no_readings))
                            if(row.supportedNanos==null) Text(stringResource(R.string.coverage_cadence_unknown))
                            else {
                                Text(stringResource(R.string.coverage_supported,coverageDuration(requireNotNull(row.supportedNanos))),Modifier.testTag("coverage_supported_$index"))
                                Text(stringResource(R.string.coverage_gap_time,coverageDuration(requireNotNull(row.gapNanos))))
                                Text(stringResource(R.string.coverage_threshold,coverageDuration(requireNotNull(row.thresholdNanos))))
                                Text(stringResource(R.string.coverage_gaps,row.gaps))
                            }
                            if(row.missingChunks>0) Text(stringResource(R.string.coverage_missing_chunks,row.missingChunks),color=MaterialTheme.colorScheme.error)
                            if(row.missingIndexedSamples>0) Text(stringResource(R.string.coverage_missing_index,row.missingIndexedSamples),color=MaterialTheme.colorScheme.error)
                            if(row.samples>stream.committedSamples) Text(stringResource(R.string.coverage_index_lag,row.samples-stream.committedSamples))
                        }
                        Text(stringResource(R.string.capture_index_rejected,stream.rejectedSamples))
                    }
                }
            }
            item {
                Text(stringResource(R.string.coverage_boundaries))
                Text(stringResource(R.string.coverage_events,details.events.count {it.reason in setOf(CaptureReason.PAUSE,
                    CaptureReason.PROCESS_LOSS,CaptureReason.SERVICE_STOP,CaptureReason.CLOCK_CHANGE,CaptureReason.STORAGE,
                    CaptureReason.LOW_SPACE,CaptureReason.SENSOR_STALE,CaptureReason.MAX_DURATION)}))
                TextButton(onClick={provenance=!provenance}) {Text(stringResource(if(provenance) R.string.coverage_hide_provenance else R.string.coverage_provenance))}
            }
            if(provenance) {
                report?.let {item {SelectionContainer {Column {
                    Text(stringResource(R.string.coverage_policy,it.policyVersion))
                    Text(stringResource(R.string.coverage_fingerprint,it.inputSha256))
                }}}}
                itemsIndexed(details.streams,key={_,s->"provenance_${s.id}"}) {index,stream->
                    SelectionContainer {Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.coverage_segment,index+1),style=MaterialTheme.typography.titleMedium)
                        stream.provenance?.let {
                            Text(stringResource(R.string.coverage_requested,it.requestedPeriodMicros))
                            Text(stringResource(R.string.coverage_apk,it.baseApkSha256))
                        } ?: Text(stringResource(R.string.coverage_provenance_unknown))
                    }}
                }
            }
        }
    }
}

@Composable private fun coverageDuration(nanos: Long): String {
    val locale=LocalConfiguration.current.locales[0]
    val number=remember(locale) {NumberFormat.getNumberInstance(locale).apply {maximumFractionDigits=3;isGroupingUsed=false}}
    val millis=nanos/1_000_000+(if(nanos%1_000_000>=500_000) 1 else 0)
    val seconds=number.format((millis%60_000)/1000.0)
    val minutes=(millis/60_000)%60
    val hours=millis/3_600_000
    return when {
        hours>0->stringResource(R.string.coverage_duration_hours,hours,minutes,seconds)
        minutes>0->stringResource(R.string.coverage_duration_minutes,minutes,seconds)
        else->stringResource(R.string.coverage_duration_seconds,seconds)
    }
}
