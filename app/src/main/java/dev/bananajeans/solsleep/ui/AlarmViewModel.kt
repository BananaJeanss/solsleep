// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.ui

import android.content.Context
import android.app.Application
import androidx.lifecycle.*
import dev.bananajeans.solsleep.R
import dev.bananajeans.solsleep.alarm.*
import dev.bananajeans.solsleep.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class AlarmUiState(
    val entries: List<AlarmEntry> = emptyList(),
    val readiness: AlarmReadiness? = null,
    val loading: Boolean = true,
    val busy: Boolean = false,
    val error: Int? = null,
    val notice: Int? = null,
)

class AlarmViewModel(application: Application) : AndroidViewModel(application) {
    private val context get() = getApplication<Application>()
    private val mutable=MutableStateFlow(AlarmUiState())
    val state=mutable.asStateFlow()
    init { RingingService.createChannel(context) }

    fun reload(reconcile: Boolean = false) = viewModelScope.launch {
        try {
            val value=withContext(Dispatchers.IO) {
                val engine=AlarmGraph.engine(context)
                if(reconcile) engine.reconcile(recoverRinging=!RingingService.running)
                engine.entries()
            }
            mutable.update {it.copy(entries=value,readiness=readiness(context),loading=false,error=null)}
        } catch(e: CancellationException) {throw e}
        catch(_: Exception) {mutable.update {it.copy(loading=false,error=R.string.storage_error)}}
    }
    fun consumeNotice() {mutable.update {it.copy(notice=null)}}
    fun save(rule: AlarmRule,onSaved: ()->Unit) = act(onSaved) {AlarmGraph.engine(context).save(rule)}
    fun enabled(id: String,on: Boolean) = act {AlarmGraph.engine(context).setEnabled(id,on)}
    fun skip(id: String,undo: Boolean) = act {AlarmGraph.engine(context).skip(id,undo)}
    fun delete(id: String) = act {AlarmGraph.engine(context).delete(id);null}
    private fun act(onSuccess: ()->Unit = {},block: ()->ArmResult?) = viewModelScope.launch {
        if(mutable.value.busy) return@launch
        mutable.update {it.copy(busy=true)}
        try {
            val result=withContext(Dispatchers.IO) {block()}
            val notice=when(result) {
                ArmResult.ARMED -> R.string.saved
                ArmResult.ACCESS_REQUIRED -> R.string.saved_blocked
                ArmResult.FAILED -> R.string.schedule_error
                null -> null
            }
            val entries=withContext(Dispatchers.IO) {AlarmGraph.engine(context).entries()}
            mutable.update {it.copy(entries=entries,readiness=readiness(context),notice=notice,error=null,loading=false)}
            onSuccess()
        } catch(e: CancellationException) {throw e}
        catch(_: AlarmBusyException) {mutable.update {it.copy(notice=R.string.ringing_first)}}
        catch(_: Exception) {mutable.update {it.copy(notice=R.string.action_error)}}
        finally {mutable.update {it.copy(busy=false)}}
    }
    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T: ViewModel> create(modelClass: Class<T>): T = AlarmViewModel(context.applicationContext as Application) as T
    }
}
