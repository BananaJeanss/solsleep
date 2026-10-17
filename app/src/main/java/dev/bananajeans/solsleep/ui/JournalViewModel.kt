// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.bananajeans.solsleep.R
import dev.bananajeans.solsleep.core.*
import dev.bananajeans.solsleep.data.JournalStore
import dev.bananajeans.solsleep.data.JournalExportHistory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class JournalState(val records: List<SleepRecord> = emptyList(),val busy: Boolean=false,
    val error: Int?=null,val notice: Int?=null,val preview: JournalPreview?=null,val existing: Int=0,
    val markerConflicts: Int=0,val restoreResult: JournalRestoreResult?=null,val history: List<SleepRecord>?=null,
    val exportSummary: JournalExportSummary?=null,val exportHistoryWarning: Boolean=false)

class JournalViewModel(app: Application): AndroidViewModel(app) {
    private val mutable=MutableStateFlow(JournalState())
    val state=mutable.asStateFlow()
    private val mutex=Mutex()
    // A new helper is closed after each operation; no alarm component references this class.
    private suspend fun <T> store(block: (JournalStore)->T)=withContext(Dispatchers.IO) {
        JournalStore(getApplication()).use(block)
    }
    init {reload()}
    private fun launch(errorMessage: Int=R.string.journal_error,block: suspend ()->Unit) {
        viewModelScope.launch {
            mutex.withLock {
                mutable.update {it.copy(busy=true,error=null,notice=null,restoreResult=null)}
                try {block()}
                catch(_: RevisionConflict) {mutable.update {it.copy(error=R.string.journal_conflict)}}
                catch(_: JournalVerificationException) {mutable.update {it.copy(error=R.string.journal_export_verify_error)}}
                catch(_: JournalExportLimitException) {mutable.update {it.copy(error=R.string.journal_export_limit)}}
                catch(c: CancellationException) {throw c}
                catch(_: Exception) {mutable.update {it.copy(error=errorMessage)}}
                finally {mutable.update {it.copy(busy=false)}}
            }
        }
    }
    private suspend fun refreshExportHistory(recordingFailed: Boolean=false) {
        val summary=withContext(Dispatchers.IO) {
            try {JournalExportHistory(getApplication()).use {it.summary()}}
            catch(c: CancellationException) {throw c}
            catch(_: Exception) {null}
        }
        mutable.update {it.copy(exportSummary=summary,exportHistoryWarning=recordingFailed || summary==null)}
    }
    fun reload()=launch {val records=store {it.current()};mutable.update {it.copy(records=records)};refreshExportHistory()}
    fun save(record: SleepRecord,onSaved: ()->Unit)=launch {
        val records=store {it.save(record,record.revision-1);it.current()}
        mutable.update {it.copy(records=records)};onSaved()
    }
    fun delete(record: SleepRecord)=launch {
        val records=store {it.delete(record.id,record.revision);it.current()}
        mutable.update {it.copy(records=records)}
    }
    fun showHistory(id: String)=launch {
        val history=store {it.history(id)}
        mutable.update {it.copy(history=history)}
    }
    fun closeHistory() {if(!mutable.value.busy) mutable.update {it.copy(history=null)}}
    fun restoreRevision(record: SleepRecord)=launch {
        val base=requireNotNull(mutable.value.history).last()
        require(base.id==record.id)
        val revised=record.copy(revision=base.revision+1,
            modifiedAt=maxOf(java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS),base.modifiedAt))
        val records=store {it.save(revised,base.revision);it.current()}
        mutable.update {it.copy(records=records,history=null)}
    }
    fun export(uri: Uri)=launch(R.string.journal_export_error) {
        val result=withContext(Dispatchers.IO) {
            val resolver=getApplication<Application>().contentResolver
            val name=try {
                resolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use {cursor->
                    if(!cursor.moveToFirst()) null else cursor.getString(0)?.takeIf {value->
                        value.length<=256 && hasValidUnicode(value) && value.isNotBlank() && value.none {it<' '}
                    }
                }
            } catch(c: CancellationException) {throw c} catch(_: Exception) {null}
            JournalExportHistory(getApplication()).use {audit->
                AuditedJournalExport.run({JournalStore(getApplication()).use {it.archiveSnapshot()}},
                    {requireNotNull(resolver.openOutputStream(uri,"wt"))},
                    {requireNotNull(resolver.openInputStream(uri))},audit,name)
            }
        }
        refreshExportHistory(!result.auditRecorded)
        when(result) {
            is AuditedJournalResult.Verified->mutable.update {it.copy(notice=R.string.journal_exported)}
            is AuditedJournalResult.Failed->throw result.error
        }
    }
    fun inspect(uri: Uri)=launch(R.string.journal_archive_error) {
        val preview=withContext(Dispatchers.IO) {
            requireNotNull(getApplication<Application>().contentResolver.openInputStream(uri)).use(JournalArchive::inspect)
        }
        val local=store {it.identities()}
        val live=local.live
        val existing=live+local.deleted
        mutable.update {it.copy(preview=preview,existing=preview.records.map {r->r.id}.distinct().count {id->id in existing},
            markerConflicts=preview.deletedIds.count {id->id in live})}
    }
    fun restore()=launch {
        val preview=requireNotNull(mutable.value.preview)
        val (result,records)=store {it.restore(preview) to it.current()}
        mutable.update {it.copy(records=records,preview=null,notice=R.string.journal_restored,restoreResult=result)}
    }
    fun closePreview() {if(!mutable.value.busy) mutable.update {it.copy(preview=null)}}
}
