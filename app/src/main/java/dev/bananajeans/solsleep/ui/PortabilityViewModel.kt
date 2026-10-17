// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.bananajeans.solsleep.R
import dev.bananajeans.solsleep.alarm.AlarmGraph
import dev.bananajeans.solsleep.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.Instant

data class PortabilityState(
    val busy: Boolean=false,
    val lastAttempt: String?=null,
    val lastSuccess: String?=null,
    val preview: AlarmArchivePreview?=null,
    val existingCount: Int=0,
    val message: Int?=null,
    val restored: AlarmRestoreResult?=null,
)

class PortabilityViewModel(application: Application) : AndroidViewModel(application) {
    private val context get()=getApplication<Application>()
    private val audit=context.getSharedPreferences("archive_audit_v1",0)
    private val mutable=MutableStateFlow(PortabilityState(
        lastAttempt=audit.getString("attempt",null),lastSuccess=audit.getString("success",null)))
    val state=mutable.asStateFlow()

    fun export(uri: Uri) = runOperation {
        val attempt=Instant.now().toString()
        persistAudit("attempt",attempt)
        mutable.update {it.copy(lastAttempt=attempt)}
        val rules=AlarmGraph.engine(context).entries().map {it.rule}
        // Success is only recorded after the provider stream closes successfully.
        requireNotNull(context.contentResolver.openOutputStream(uri,"wt")).use {AlarmArchive.write(rules,it)}
        val success=Instant.now().toString()
        persistAudit("success",success)
        mutable.update {it.copy(lastSuccess=success,message=R.string.export_success)}
    }
    fun inspect(uri: Uri) = runOperation {
        val preview=requireNotNull(context.contentResolver.openInputStream(uri)).use {AlarmArchive.inspect(it)}
        val ids=AlarmGraph.engine(context).entries().map {it.rule.id}.toSet()
        mutable.update {it.copy(preview=preview,existingCount=preview.alarms.count {r->r.id in ids},restored=null)}
    }
    fun closePreview() {if(!mutable.value.busy) mutable.update {it.copy(preview=null)}}
    fun restore(onRestored: ()->Unit) = runOperation {
        val preview=mutable.value.preview ?: return@runOperation
        val result=AlarmGraph.engine(context).restore(preview)
        mutable.update {it.copy(preview=null,restored=result,message=null)}
        withContext(Dispatchers.Main) {onRestored()}
    }
    // KTX edit returns Unit; audit acknowledgements must check the commit result.
    @android.annotation.SuppressLint("UseKtx")
    private fun persistAudit(key: String,value: String) {check(audit.edit().putString(key,value).commit())}
    private fun runOperation(block: suspend ()->Unit) = viewModelScope.launch {
        if(mutable.value.busy) return@launch
        mutable.update {it.copy(busy=true,message=null,restored=null)}
        try {withContext(Dispatchers.IO) {block()}}
        catch(e: CancellationException) {throw e}
        catch(_: Exception) {mutable.update {it.copy(message=R.string.archive_error)}}
        finally {mutable.update {it.copy(busy=false)}}
    }
}
