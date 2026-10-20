@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.bananajeans.solsleep.R
import dev.bananajeans.solsleep.core.*
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable fun MotionExportHistoryScreen(summary: MotionExportSummary?,unavailable: Boolean,onClose: ()->Unit) {
    BackHandler(onBack=onClose)
    val context=LocalContext.current
    val locale=LocalConfiguration.current.locales[0]
    val use24=android.text.format.DateFormat.is24HourFormat(context)
    val zone=ZoneId.systemDefault()
    val formatter=remember(locale,use24,zone) {DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale,
        if(use24) "yMMMdHms" else "yMMMdhms"),locale).withZone(zone)}
    Scaffold(topBar={TopAppBar(title={Text(stringResource(R.string.motion_receipts_title),maxLines=1,overflow=TextOverflow.Ellipsis)},
        navigationIcon={IconButton(onClick=onClose) {AppIcon(R.drawable.ic_close,stringResource(R.string.close))}})}) {padding->
        LazyColumn(Modifier.fillMaxSize().padding(padding).semantics {testTag="motion_receipts"},
            contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            item {Text(stringResource(R.string.motion_receipts_hint))}
            when {
                unavailable->item {Text(stringResource(R.string.motion_receipts_unavailable),color=MaterialTheme.colorScheme.error)}
                summary==null->item {CircularProgressIndicator()}
                summary.lastAttempt==null->item {Text(stringResource(R.string.motion_receipts_empty))}
                else->{
                    item {Text(stringResource(R.string.motion_receipts_last_attempt),style=MaterialTheme.typography.titleLarge)}
                    item {MotionReceipt(requireNotNull(summary.lastAttempt),formatter)}
                    item {HorizontalDivider();Text(stringResource(R.string.motion_receipts_last_verified),style=MaterialTheme.typography.titleLarge)}
                    item {summary.lastVerified?.let {MotionReceipt(it,formatter)} ?: Text(stringResource(R.string.motion_receipts_no_verified))}
                }
            }
        }
    }
}

@Composable private fun MotionReceipt(value: MotionExportAttempt,formatter: DateTimeFormatter) {
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(stringResource(when(value.stage) {
            MotionExportStage.STARTED->R.string.motion_receipts_started
            MotionExportStage.WRITTEN->R.string.motion_receipts_written
            MotionExportStage.VERIFIED->R.string.motion_receipts_verified
            MotionExportStage.FAILED_WRITE->R.string.motion_receipts_failed_write
            MotionExportStage.UNVERIFIED->R.string.motion_receipts_unverified
        }))
        Text(stringResource(R.string.motion_receipts_date,formatter.format(value.updatedAt),formatter.zone.id))
        val destination=value.destination
        Text(destination?.displayName?.let {stringResource(R.string.motion_receipts_name,it)} ?: stringResource(R.string.motion_receipts_unknown_name))
        destination?.authority?.let {Text(stringResource(R.string.motion_receipts_provider,it))}
        SelectionContainer {Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(destination?.let {stringResource(R.string.motion_receipts_identity,it.uriSha256)} ?: stringResource(R.string.motion_receipts_unknown_destination))
            value.fingerprint?.let {Text(stringResource(R.string.motion_receipts_fingerprint,it))}
        }}
    }
}
