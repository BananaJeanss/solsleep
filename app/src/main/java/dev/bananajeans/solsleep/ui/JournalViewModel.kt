// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.bananajeans.solsleep.R
import dev.bananajeans.solsleep.core.*
import dev.bananajeans.solsleep.data.JournalStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class JournalState(val records: List<SleepRecord> = emptyList(),val busy: Boolean=false,
    val error: Int?=null,val notice: Int?=null,val preview: JournalPreview?=null,val existing: Int=0,val history: List<SleepRecord>?=null)

class JournalViewModel(app: Application): AndroidViewModel(app) {
    private val mutable=MutableStateFlow(JournalState())
    val state=mutable.asStateFlow()
    private val mutex=Mutex()
    // A new helper is closed after each operation; no alarm component references this class.
    private suspend fun <T> store(block: (JournalStore)->T)=withContext(Dispatchers.IO) {
        JournalStore(getApplication()).use(block)
    }
    init {reload()}
    private fun launch(block: suspend ()->Unit) {
        viewModelScope.launch {
            mutex.withLock {
                mutable.update {it.copy(busy=true,error=null,notice=null)}
                try {block()}
                catch(_: RevisionConflict) {mutable.update {it.copy(error=R.string.journal_conflict)}}
                catch(c: CancellationException) {throw c}
                catch(_: Exception) {mutable.update {it.copy(error=R.string.journal_error)}}
                finally {mutable.update {it.copy(busy=false)}}
            }
        }
    }
    fun reload()=launch {val records=store {it.current()};mutable.update {it.copy(records=records)}}
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
    fun export(uri: Uri)=launch {
        val records=store {it.snapshot()}
        withContext(Dispatchers.IO) {
            val resolver=getApplication<Application>().contentResolver
            requireNotNull(resolver.openOutputStream(uri,"wt")).use {JournalArchive.write(records,it)}
        }
        mutable.update {it.copy(notice=R.string.journal_exported)}
    }
    fun inspect(uri: Uri)=launch {
        val preview=withContext(Dispatchers.IO) {
            requireNotNull(getApplication<Application>().contentResolver.openInputStream(uri)).use(JournalArchive::inspect)
        }
        val existing=store {it.current().map {r->r.id}.toSet()}
        mutable.update {it.copy(preview=preview,existing=preview.records.map {r->r.id}.distinct().count {id->id in existing})}
    }
    fun restore()=launch {
        val preview=requireNotNull(mutable.value.preview)
        val records=store {it.restore(preview);it.current()}
        mutable.update {it.copy(records=records,preview=null,notice=R.string.journal_restored)}
    }
    fun closePreview() {if(!mutable.value.busy) mutable.update {it.copy(preview=null)}}
}
