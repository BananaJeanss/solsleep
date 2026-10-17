// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.bananajeans.solsleep.R
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable fun PortabilityCard(onRestored: ()->Unit) {
    val model: PortabilityViewModel=viewModel()
    val state by model.state.collectAsStateWithLifecycle()
    val create=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {uri->
        if(uri!=null) model.export(uri)
    }
    val open=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri->
        if(uri!=null) model.inspect(uri)
    }
    val locale=LocalConfiguration.current.locales[0]
    val formatter=remember(locale) {DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale).withZone(ZoneId.systemDefault())}
    fun whenText(value: String?)=value?.let {formatter.format(Instant.parse(it))}
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.portability_title),style=MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.portability_body),style=MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.archive_plaintext),style=MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.archive_attempt,whenText(state.lastAttempt) ?: stringResource(R.string.never)),style=MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.archive_success,whenText(state.lastSuccess) ?: stringResource(R.string.never)),style=MaterialTheme.typography.bodySmall)
        if(state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        OutlinedButton(onClick={create.launch("solsleep-alarms-${LocalDate.now()}.json")},enabled=!state.busy,modifier=Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.export_alarms))
        }
        OutlinedButton(onClick={open.launch(arrayOf("application/json","application/octet-stream"))},enabled=!state.busy,modifier=Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.inspect_archive))
        }
        state.message?.let {Text(stringResource(it),style=MaterialTheme.typography.bodyMedium)}
        state.restored?.let {Text(pluralStringResource(R.plurals.restore_added,it.added,it.added)+" "+pluralStringResource(R.plurals.archive_conflicts,it.skippedExisting,it.skippedExisting),style=MaterialTheme.typography.bodyMedium)}
    }
    state.preview?.let {preview->
        AlertDialog(onDismissRequest=model::closePreview,
            title={Text(stringResource(R.string.archive_review))},
            text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(pluralStringResource(R.plurals.archive_count,preview.alarms.size,preview.alarms.size))
                Text(pluralStringResource(R.plurals.archive_conflicts,state.existingCount,state.existingCount))
                Text(stringResource(R.string.archive_created,formatter.format(preview.createdAt)))
                Text(stringResource(R.string.restore_disabled))
                if(preview.alarms.isEmpty()) Text(stringResource(R.string.archive_empty))
                preview.alarms.take(10).forEach {r->Text(r.time.toString()+" · "+r.label,style=MaterialTheme.typography.bodyMedium)}
                if(preview.alarms.size>10) Text(pluralStringResource(R.plurals.archive_more,preview.alarms.size-10,preview.alarms.size-10))
                if(state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.message?.let {Text(stringResource(it),color=MaterialTheme.colorScheme.error)}
            }},
            confirmButton={TextButton(onClick={model.restore(onRestored)},enabled=!state.busy && preview.alarms.size>state.existingCount) {Text(stringResource(R.string.restore_alarms))}},
            dismissButton={TextButton(onClick=model::closePreview,enabled=!state.busy) {Text(stringResource(R.string.close))}})
    }
}
