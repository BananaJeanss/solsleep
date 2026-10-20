@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import dev.bananajeans.solsleep.R
import dev.bananajeans.solsleep.core.*

@Composable fun RecordingDeletionPreviewScreen(preview: RecordingDeletionPreview,result: RecordingDeletionMerge?,
    busy: Boolean,onImport: ()->Unit,onClose: ()->Unit) {
    BackHandler {if(!busy) onClose()}
    Scaffold(topBar={TopAppBar(title={Text(stringResource(R.string.recording_deletions_title))},navigationIcon={
        IconButton(onClick=onClose,enabled=!busy,modifier=Modifier.semantics {testTag="recording_deletions_close"}) {
            AppIcon(R.drawable.ic_close,stringResource(R.string.close))
        }
    })},bottomBar={
        Surface {
            Button(onClick=onImport,enabled=!busy && result!=null,
                modifier=Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).semantics {testTag="recording_deletions_confirm"}) {
                Text(stringResource(R.string.recording_deletions_confirm))
            }
        }
    }) {padding->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp)
            .semantics {testTag="recording_deletions_preview"},verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.recording_deletions_preview),style=MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.recording_deletions_preview_hint))
            Text(stringResource(R.string.recording_deletions_count,preview.snapshot.markers.size))
            result?.let {
                Text(stringResource(R.string.recording_deletions_result,it.added,it.alreadyKnown,it.liveConflicts,it.identityConflicts))
            }
            Text(stringResource(R.string.recording_deletions_digest))
            SelectionContainer {Text(preview.fingerprint)}
            Text(stringResource(R.string.recording_deletions_trust))
        }
    }
}
