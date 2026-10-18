// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.challenge

import androidx.lifecycle.*
import kotlinx.coroutines.Job

/** Main-thread owner of bounded capture/playback jobs. Pause cancels directly,
 * even when a stopped Compose recomposer cannot process a state update. */
internal class VisibleChallengeJobs(private val lifecycle: Lifecycle): AutoCloseable {
    private val jobs=mutableMapOf<Int,Job>()
    private var closed=false
    private val observer=LifecycleEventObserver {_,event->
        if(event==Lifecycle.Event.ON_PAUSE || event==Lifecycle.Event.ON_STOP || event==Lifecycle.Event.ON_DESTROY) cancelAll()
    }
    init {lifecycle.addObserver(observer)}
    fun track(slot: Int,job: Job): Boolean {
        if(closed || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {job.cancel();return false}
        jobs.put(slot,job)?.takeUnless {it===job}?.cancel()
        return true
    }
    /** Return ownership so an old finally block cannot reset a replacement's UI. */
    fun untrack(slot: Int,job: Job): Boolean {
        if(jobs[slot]!==job) return false
        jobs.remove(slot);return true
    }
    private fun cancelAll() {jobs.values.toList().forEach {it.cancel()};jobs.clear()}
    override fun close() {closed=true;lifecycle.removeObserver(observer);cancelAll()}
}
