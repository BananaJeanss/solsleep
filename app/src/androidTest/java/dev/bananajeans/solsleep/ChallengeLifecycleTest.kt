// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.bananajeans.solsleep.challenge.VisibleChallengeJobs
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ChallengeLifecycleTest {
    @Test fun actualActivityPauseCancelsCaptureAndPlaybackJobsWithoutAComposeFrame() {
        ActivityScenario.launch(MainActivity::class.java).use {scenario->
            lateinit var owner: VisibleChallengeJobs
            val released=CountDownLatch(2)
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
            val jobs=List(2) {scope.launch(start=CoroutineStart.UNDISPATCHED) {try {awaitCancellation()} finally {released.countDown()}}}
            try {
                scenario.onActivity {owner=VisibleChallengeJobs(it.lifecycle);jobs.forEachIndexed {i,job->assertTrue(owner.track(i,job))}}
                scenario.moveToState(Lifecycle.State.CREATED)
                assertTrue("Both resource finally blocks must execute after real Stop",released.await(5,TimeUnit.SECONDS))
                assertTrue(jobs.all {it.isCancelled})
                scenario.onActivity {owner.close()}
            } finally {scope.cancel()}
        }
    }
    @Test fun pausedActivityCannotStartOrResumeADeferredPermissionCapture() {
        ActivityScenario.launch(MainActivity::class.java).use {scenario->
            scenario.moveToState(Lifecycle.State.CREATED)
            val job=Job()
            scenario.onActivity {val owner=VisibleChallengeJobs(it.lifecycle)
                assertFalse(owner.track(0,job));assertTrue(job.isCancelled);owner.close()}
        }
    }
    @Test fun staleCompletionCannotUnregisterAReplacementAndCloseCancelsTheCurrentJob() {
        ActivityScenario.launch(MainActivity::class.java).use {scenario->
            val old=Job();val next=Job()
            scenario.onActivity {val owner=VisibleChallengeJobs(it.lifecycle)
                assertTrue(owner.track(0,old));assertTrue(owner.track(0,next));assertTrue(old.isCancelled)
                owner.untrack(0,old);assertTrue(next.isActive)
                owner.close();assertTrue(next.isCancelled)
                val late=Job();assertFalse(owner.track(1,late));assertTrue(late.isCancelled)}
        }
    }
}
