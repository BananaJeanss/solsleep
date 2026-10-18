// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.*

class VoiceMatchTest {
    private fun voice(gain: Double=.3,speed: Double=1.0)=ShortArray((16000/speed).toInt()) {i->
        val t=i/8000.0*speed;val envelope=if((t*5).toInt()%3==0) .12 else 1.0
        (sin(2*PI*(160*t+12*t*t))*gain*envelope*32767).toInt().toShort()
    }
    private fun reject(block: ()->Unit) {try {block();fail("Must reject")} catch(_: IllegalArgumentException) {}}
    @Test fun quietBaselineSuppressesSteadyAlarmWithoutDestroyingLouderSpeech() {
        val speech=voice();val samples=ShortArray(48000) {i->(sin(2*PI*220*i/8000)*300).toInt().toShort()}
        speech.forEachIndexed {i,x->samples[4000+i]=(samples[4000+i]+x).toShort()}
        val clean=VoiceMatch.suppressBaseline(samples)
        assertTrue(clean.take(4000).all {it==0.toShort()});assertTrue(clean.drop(20000).all {it==0.toShort()})
        assertTrue(VoiceMatch.score(speech,clean)>=70)
        val alarm=ShortArray(48000) {i->(sin(2*PI*220*i/8000)*300).toInt().toShort()}
        assertTrue(VoiceMatch.suppressBaseline(alarm).all {it==0.toShort()})
    }
    @Test fun identicalVoicedDeliveryScoresOneHundred() {val x=voice();assertEquals(100,VoiceMatch.score(x,x))}
    @Test fun loweringVolumeDoesNotLowerScoreAndShoutingDoesNotImproveIt() {
        val x=voice();assertTrue(VoiceMatch.score(x,voice(.08))>=98);assertTrue(VoiceMatch.score(x,voice(.7))>=98)
    }
    @Test fun modestTempoChangeCanMatchButExtremeDurationCannot() {
        val x=voice();assertTrue(VoiceMatch.score(x,voice(speed=1.1))>=70);assertEquals(0,VoiceMatch.score(x,voice(speed=.4)))
    }
    @Test fun silenceNoiseAndClippingCannotCompleteVoiceover() {
        val x=voice();val rng=Random(42)
        assertEquals(0,VoiceMatch.score(x,ShortArray(16000)))
        assertEquals(0,VoiceMatch.score(x,ShortArray(16000) {(rng.nextInt(16000)-8000).toShort()}))
        assertEquals(0,VoiceMatch.score(x,ShortArray(16000) {if(it%2==0) Short.MAX_VALUE else Short.MIN_VALUE}))
    }
    @Test fun referenceAndWavRoundTripExactPcmAndTitle() {
        val x=voice();val clip=VoiceClip.from("My original line",x)
        assertArrayEquals(x,clip.samples());assertArrayEquals(x,VoiceWav.read(ByteArrayInputStream(VoiceWav.encode(x))))
        val policy=ChallengePolicy(setOf(ChallengeKind.VOICEOVER),rounds=1,voiceClip=clip)
        assertEquals(policy,ChallengePolicyCodec.decode(ChallengePolicyCodec.encode(policy)))
        val rule=AlarmRule("voice",java.time.LocalTime.of(7,0),Repeat.Weekly(setOf(java.time.DayOfWeek.MONDAY)),challenge=policy)
        val bytes=java.io.ByteArrayOutputStream().also {AlarmArchive.write(listOf(rule),it)}.toByteArray()
        val restored=AlarmArchive.inspect(ByteArrayInputStream(bytes)).alarms.single()
        assertEquals(clip,restored.challenge!!.voiceClip)
    }
    @Test fun voiceoverHostRequiresMatchingSamplesAndNeverTrustsANumericScore() {
        val p=ChallengePolicy(setOf(ChallengeKind.VOICEOVER),rounds=1,voiceClip=VoiceClip.from("Original",voice()))
        val s=WakeChallenge(java.util.UUID.randomUUID().toString(),1,p.fingerprint(),ChallengeAction.DISMISS,java.time.Instant.EPOCH)
        assertFalse(WakeChallenges.respond(p,s,ChallengeResponse.Text("100")).satisfied(p))
        assertFalse(WakeChallenges.respond(p,s,ChallengeResponse.Voiceover(ShortArray(16000))).satisfied(p))
        assertTrue(WakeChallenges.respond(p,s,ChallengeResponse.Voiceover(voice(.1))).satisfied(p))
    }
    @Test fun malformedWavHeadersLengthsFormatsAndTruncationAreRefused() {
        val good=VoiceWav.encode(voice())
        for(offset in listOf(0,8,20,22,24,28,32,34,40)) {
            val bad=good.copyOf();bad[offset]=0x7f
            reject {VoiceWav.read(ByteArrayInputStream(bad))}
        }
        reject {VoiceWav.read(ByteArrayInputStream(good.copyOf(good.size-1)))}
        reject {VoiceWav.read(ByteArrayInputStream(good+byteArrayOf(0)))}
        reject {VoiceWav.read(ByteArrayInputStream(ByteArray(VoiceWav.MAX_BYTES+1)))}
    }
    @Test fun stereoSixteenKhzIsDownsampledAndUnknownMetadataIsSkipped() {
        val x=voice();val raw=ByteBuffer.allocate(x.size*8).order(ByteOrder.LITTLE_ENDIAN)
        x.forEach {repeat(4) {_->raw.putShort(it)}}
        val b=ByteBuffer.allocate(56+raw.capacity()).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray());b.putInt(b.capacity()-8);b.put("WAVEfmt ".toByteArray());b.putInt(16)
        b.putShort(1);b.putShort(2);b.putInt(16000);b.putInt(64000);b.putShort(4);b.putShort(16)
        b.put("JUNK".toByteArray());b.putInt(4);b.putInt(42);b.put("data".toByteArray());b.putInt(raw.capacity());b.put(raw.array())
        assertArrayEquals(x,VoiceWav.read(ByteArrayInputStream(b.array())))
    }
    @Test fun inputAndStoredReferenceLimitsRejectTooShortTooLongAndNoncanonicalBase64() {
        reject {VoiceClip.from("x",ShortArray(3999))};reject {VoiceClip.from("x",ShortArray(48001))}
        reject {VoiceClip("x","A".repeat(128001))};reject {VoiceClip("x","!".repeat(12000))}
        reject {VoiceMatch.score(ShortArray(1),voice())}
    }
    @Test fun archiveCannotSilentlyDropLargeVoiceDefinitions() {
        val p=ChallengePolicy(setOf(ChallengeKind.VOICEOVER),voiceClip=VoiceClip.from("Original",voice(speed=.4)))
        val rules=(0..9).map {AlarmRule(it.toString(),java.time.LocalTime.of(7,0),Repeat.Weekly(setOf(java.time.DayOfWeek.MONDAY)),challenge=p)}
        val out=java.io.ByteArrayOutputStream();reject {AlarmArchive.write(rules,out)};assertEquals(0,out.size())
    }
}
