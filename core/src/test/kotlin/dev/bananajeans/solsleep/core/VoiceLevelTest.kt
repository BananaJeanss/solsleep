// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.core

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.UUID
import kotlin.math.*

class VoiceLevelTest {
    private fun samples()=ShortArray(48000) {i->(sin(2*PI*200*i/8000)*100).toInt().toShort()}
    private fun speak(pcm: ShortArray,start: Int,end: Int) {for(i in start until end) pcm[i]=(sin(2*PI*200*i/8000)*9000).toInt().toShort()}
    private fun passes(value: ShortArray,level: ChallengeDifficulty): Boolean {
        val p=ChallengePolicy(setOf(ChallengeKind.VOICE),level,rounds=1)
        val s=WakeChallenge(UUID.randomUUID().toString(),42,p.fingerprint(),ChallengeAction.DISMISS,Instant.EPOCH)
        return WakeChallenges.respond(p,s,VoiceLevel.evidence(value,level)).satisfied(p)
    }
    @Test fun finalTwoSecondsAreFullyCountedRatherThanLosingTheLastFrame() {
        val pcm=samples();speak(pcm,32000,48000)
        assertEquals(2000L,VoiceLevel.evidence(pcm,ChallengeDifficulty.HARD).sustainedMillis)
        assertTrue(passes(pcm,ChallengeDifficulty.HARD))
    }
    @Test fun sustainedSpeechImmediatelyAfterBaselineIsCountedAtEveryLevel() {
        val pcm=samples();speak(pcm,4000,20000)
        for(level in ChallengeDifficulty.entries) {assertEquals(2000L,VoiceLevel.evidence(pcm,level).sustainedMillis);assertTrue(passes(pcm,level))}
    }
    @Test fun separateBurstsDoNotInventAContinuousTwoSecondAttempt() {
        val pcm=samples();speak(pcm,4000,12000);speak(pcm,20000,28000)
        assertEquals(1000L,VoiceLevel.evidence(pcm,ChallengeDifficulty.HARD).sustainedMillis)
        assertFalse(passes(pcm,ChallengeDifficulty.HARD))
    }
    @Test fun silenceAndAnUnchangingRoomOrAlarmLevelCannotPass() {
        for(level in ChallengeDifficulty.entries) {
            assertFalse(passes(ShortArray(48000),level));assertFalse(passes(samples(),level))
            val constant=ShortArray(48000) {i->(sin(2*PI*200*i/8000)*9000).toInt().toShort()}
            assertFalse(passes(constant,level))
        }
    }
    @Test fun excessiveClippingIsRejectedDespiteLoudSustainedEvidence() {
        val pcm=samples();speak(pcm,4000,48000)
        for(i in 4000..5000) pcm[i]=Short.MAX_VALUE
        for(level in ChallengeDifficulty.entries) assertFalse(passes(pcm,level))
    }
    @Test fun boundedPcmRejectsPartialBaselineAndUnboundedInput() {
        for(size in listOf(0,3999,48001)) try {VoiceLevel.evidence(ShortArray(size),ChallengeDifficulty.EASY);fail("Must reject invalid count")}
        catch(_: IllegalArgumentException) {}
        assertEquals(0L,VoiceLevel.evidence(ShortArray(4000),ChallengeDifficulty.EASY).sustainedMillis)
    }
}
