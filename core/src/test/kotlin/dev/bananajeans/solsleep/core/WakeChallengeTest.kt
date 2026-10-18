// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.core

import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.time.Instant
import java.util.UUID

class WakeChallengeTest {
    private fun state(p: ChallengePolicy,seed: Long=123)=WakeChallenge(UUID.randomUUID().toString(),seed,p.fingerprint(),ChallengeAction.SNOOZE,Instant.parse("2026-10-18T00:00:00Z"))
    private fun reject(block: ()->Unit) {try {block();fail("Must reject unsafe input")} catch(_: IllegalArgumentException) {} catch(_: java.nio.charset.CharacterCodingException) {}}
    private fun answer(p: ChallengePolicy,s: WakeChallenge): WakeChallenge {
        val q=WakeChallenges.question(p,s)
        return when(q.kind) {
            ChallengeKind.MATH,ChallengeKind.COPY_CODE->WakeChallenges.respond(p,s,ChallengeResponse.Text(q.expected))
            ChallengeKind.REVERSE_DIGITS->WakeChallenges.respond(p,s,ChallengeResponse.Text(q.expected.reversed()))
            ChallengeKind.SHEEP->WakeChallenges.respond(p,s,ChallengeResponse.Select(q.target))
            ChallengeKind.MEMORY_DIGITS->WakeChallenges.respond(p,WakeChallenges.respond(p,s,ChallengeResponse.Ready),ChallengeResponse.Text(q.expected))
            ChallengeKind.TAP_ORDER->{var next=s;for(i in 1..q.count) next=WakeChallenges.respond(p,next,ChallengeResponse.Select(q.numbers.indexOf(i)));next}
            ChallengeKind.MEMORY_PATTERN->{var next=WakeChallenges.respond(p,s,ChallengeResponse.Ready);q.numbers.forEach {next=WakeChallenges.respond(p,next,ChallengeResponse.Select(it))};next}
            else->error("Special task")
        }
    }
    @Test fun eachPuzzleAtEveryDifficultyHasADeterministicAnswerAcrossHundredsOfSeeds() {
        val kinds=setOf(ChallengeKind.MATH,ChallengeKind.COPY_CODE,ChallengeKind.REVERSE_DIGITS,ChallengeKind.SHEEP,ChallengeKind.MEMORY_DIGITS,ChallengeKind.TAP_ORDER,ChallengeKind.MEMORY_PATTERN)
        for(k in kinds) for(d in ChallengeDifficulty.entries) for(seed in 0L..99L) {
            val p=ChallengePolicy(setOf(k),d,rounds=1);val s=state(p,seed)
            assertEquals(WakeChallenges.question(p,s),WakeChallenges.question(p,WakeChallengeCodec.decode(WakeChallengeCodec.encode(s))))
            assertTrue("$k $d $seed",answer(p,s).satisfied(p))
        }
    }
    @Test fun difficultyChangesPuzzleWorkRatherThanJustTheLabel() {
        for(k in listOf(ChallengeKind.SHEEP,ChallengeKind.TAP_ORDER,ChallengeKind.MEMORY_PATTERN,ChallengeKind.MAZE)) {
            val count=ChallengeDifficulty.entries.map {d->val p=ChallengePolicy(setOf(k),d);WakeChallenges.question(p,state(p)).let {if(k==ChallengeKind.MEMORY_PATTERN) it.numbers.size else it.count}}
            assertTrue(count[0]<count[1] && count[1]<count[2])
        }
    }
    @Test fun missesPreserveTheQuestionAndOriginalDeadlineAndIncreaseGain() {
        val p=ChallengePolicy(rounds=1);val s=state(p);val wrong=WakeChallenges.respond(p,s,ChallengeResponse.Text("invalid"))
        assertEquals(s.startedAt,wrong.startedAt);assertEquals(s.id,wrong.id);assertEquals(s.seed,wrong.seed)
        assertEquals(WakeChallenges.question(p,s),WakeChallenges.question(p,wrong));assertEquals(1,wrong.failures)
        assertTrue(wrong.gain(p,s.startedAt)>s.gain(p,s.startedAt))
    }
    @Test fun volumeNeverMutesAndOneMinuteEndsQuietPeriodEvenAfterReopen() {
        for(q in 5..40) {val p=ChallengePolicy(quietPercent=q);val s=state(p)
            assertEquals(q/100f,s.gain(p,s.startedAt),.0001f)
            assertEquals(1f,s.gain(p,s.startedAt.plusSeconds(60)),0f)
            assertEquals(1f,WakeChallengeCodec.decode(WakeChallengeCodec.encode(s)).gain(p,s.startedAt.plusSeconds(61)),0f)
            assertEquals(1f,s.gain(p,s.startedAt.minusMillis(1)),0f)
        }
    }
    @Test fun repeatedFailuresReachFullGainWithoutExtendingRinging() {
        val p=ChallengePolicy();val s=state(p);var next=s
        repeat(8) {next=WakeChallenges.respond(p,next,ChallengeResponse.Text("wrong"))}
        assertEquals(1f,next.gain(p,s.startedAt),0f);assertEquals(s.startedAt,next.startedAt);assertFalse(next.satisfied(p))
    }
    @Test fun allConfiguredTypesOccurBeforeRepeating() {
        val p=ChallengePolicy(setOf(ChallengeKind.MATH,ChallengeKind.SHEEP,ChallengeKind.COPY_CODE),rounds=6);var s=state(p)
        val observed=mutableListOf<ChallengeKind>()
        repeat(6) {observed+=WakeChallenges.question(p,s).kind;s=answer(p,s)}
        assertEquals(3,observed.take(3).distinct().size);assertEquals(observed.take(3),observed.drop(3));assertTrue(s.satisfied(p))
    }
    @Test fun memoryCannotBeAnsweredBeforeDurableHidingAndReplayRecordsAMiss() {
        for(k in listOf(ChallengeKind.MEMORY_DIGITS,ChallengeKind.MEMORY_PATTERN)) {
            val p=ChallengePolicy(setOf(k));val s=state(p)
            assertEquals(s,WakeChallenges.respond(p,s,ChallengeResponse.Text(WakeChallenges.question(p,s).expected)))
            val hidden=WakeChallenges.respond(p,s,ChallengeResponse.Ready)
            val next=WakeChallenges.respond(p,WakeChallengeCodec.decode(WakeChallengeCodec.encode(hidden)),ChallengeResponse.Replay)
            assertFalse(next.hidden);assertEquals(1,next.failures);assertEquals(s.seed,next.seed)
        }
    }
    @Test fun orderedMistakeResetsOnlyTheCurrentRoundAndKeepsItsShuffle() {
        val p=ChallengePolicy(setOf(ChallengeKind.TAP_ORDER));val s=state(p);val q=WakeChallenges.question(p,s)
        val one=WakeChallenges.respond(p,s,ChallengeResponse.Select(q.numbers.indexOf(1)))
        val wrong=WakeChallenges.respond(p,one,ChallengeResponse.Select(q.numbers.indexOf(1)))
        assertEquals(0,wrong.step);assertEquals(0,wrong.stage);assertEquals(1,wrong.failures);assertEquals(q,WakeChallenges.question(p,wrong))
    }
    @Test fun everyMazeIsConnectedAcyclicAndSolvableAndWallHitsDoNotMove() {
        for(level in ChallengeDifficulty.entries) for(seed in 0L..99L) {
            val p=ChallengePolicy(setOf(ChallengeKind.MAZE),level,rounds=1);var s=state(p,seed);val q=WakeChallenges.question(p,s)
            assertEquals((q.count-1)*2,q.maze.size)
            val parent=mutableMapOf(0 to -1);val todo=java.util.ArrayDeque<Int>();todo.add(0)
            while(todo.isNotEmpty()) {val from=todo.removeFirst();q.maze.filter {it.first==from}.forEach {(_,to)->if(to !in parent) {parent[to]=from;todo.add(to)}}}
            assertEquals(q.count,parent.size)
            val wrong=WakeChallenges.respond(p,s,ChallengeResponse.Move(0));assertEquals(0,wrong.position);assertEquals(1,wrong.failures)
            val path=mutableListOf(q.count-1);while(path.last()!=0) path+=parent.getValue(path.last())
            path.asReversed().drop(1).forEach {next->val delta=next-s.position;val direction=when(delta) {-q.side->0;1->1;q.side->2;else->3}
                s=WakeChallenges.respond(p,s,ChallengeResponse.Move(direction))}
            assertTrue(s.satisfied(p))
        }
    }
    @Test fun fallbackIsStillAChallengeWithTwoExtraRoundsAndCannotBeRestarted() {
        val p=ChallengePolicy(setOf(ChallengeKind.SHEEP),rounds=2);val first=answer(p,state(p))
        var s=WakeChallenges.respond(p,first,ChallengeResponse.Fallback)
        assertEquals(1,s.stage);assertEquals(4,s.total(p));assertEquals(first.startedAt,s.startedAt)
        assertEquals(s,WakeChallenges.respond(p,s,ChallengeResponse.Fallback))
        repeat(3) {assertEquals(ChallengeKind.COPY_CODE,WakeChallenges.question(p,s).kind);s=answer(p,s)}
        assertTrue(s.satisfied(p));assertEquals(s,WakeChallenges.respond(p,s,ChallengeResponse.Text("wrong")))
    }
    @Test fun responseKindCannotSpoofCompletionAndPolicyChangesCannotReuseProof() {
        val p=ChallengePolicy(rounds=1);val s=state(p)
        assertFalse(WakeChallenges.respond(p,s,ChallengeResponse.Select(0)).satisfied(p))
        reject {s.copy(stage=14).satisfied(p)}
        reject {s.satisfied(p.copy(difficulty=ChallengeDifficulty.HARD))}
    }
    @Test fun arabicIndicMathAnswersWorkButExpressionsAndOverflowDoNot() {
        val p=ChallengePolicy(rounds=1);val s=state(p);val expected=WakeChallenges.question(p,s).expected
        assertTrue(WakeChallenges.respond(p,s,ChallengeResponse.Text(expected.map {('٠'.code+(it-'0')).toChar()}.joinToString(""))).satisfied(p))
        for(value in listOf("1+1","999999999999999999999","NaN","2.0","${expected}x")) assertFalse(WakeChallenges.respond(p,s,ChallengeResponse.Text(value)).satisfied(p))
    }
    @Test fun tagProofIsDomainHashedAndWrongTagsDoNotPass() {
        val uid=byteArrayOf(1,2,3,4);val hash=WakeChallenges.tagHash(uid)
        assertEquals(64,hash.length);assertEquals(hash,WakeChallenges.tagHash(uid));assertNotEquals(hash,WakeChallenges.tagHash(byteArrayOf(4,3,2,1)))
        val p=ChallengePolicy(setOf(ChallengeKind.NFC),rounds=1,nfcHash=hash);val s=state(p)
        assertFalse(WakeChallenges.respond(p,s,ChallengeResponse.Nfc("0".repeat(64))).satisfied(p))
        assertTrue(WakeChallenges.respond(p,s,ChallengeResponse.Nfc(hash)).satisfied(p))
        reject {WakeChallenges.tagHash(byteArrayOf())};reject {WakeChallenges.tagHash(ByteArray(4))}
    }
    @Test fun microphoneEvidenceRejectsSilenceClippingInvalidNumbersAndTooShortAttempts() {
        val p=ChallengePolicy(setOf(ChallengeKind.VOICE),rounds=1);val s=state(p)
        for(r in listOf(ChallengeResponse.Voice(500,-50.0,-20.0),ChallengeResponse.Voice(3000,-50.0,-90.0),ChallengeResponse.Voice(3000,-50.0,0.0),ChallengeResponse.Voice(3000,Double.NaN,-20.0),ChallengeResponse.Voice(3000,-21.0,-20.0)))
            assertFalse(WakeChallenges.respond(p,s,r).satisfied(p))
        assertTrue(WakeChallenges.respond(p,s,ChallengeResponse.Voice(2000,-50.0,-20.0)).satisfied(p))
    }
    @Test fun challengeStateAndPolicyRoundTripWithoutLosingCountersOrIdentity() {
        val p=ChallengePolicy(setOf(ChallengeKind.CUSTOM,ChallengeKind.MEMORY_DIGITS),pack=ChallengePackCodec.example())
        assertEquals(p,ChallengePolicyCodec.decode(ChallengePolicyCodec.encode(p)))
        val s=state(p).copy(failures=9,stage=2,step=1,hidden=true,position=4,revision=17,fallback=true)
        assertEquals(s,WakeChallengeCodec.decode(WakeChallengeCodec.encode(s)))
        reject {WakeChallengeCodec.decode(WakeChallengeCodec.encode(s).replace("\"revision\":17","\"revision\":0"))}
    }
    @Test fun independentlyAuthoredPackFixtureDefinesAllThreeLevelsWithoutCodecRewriting() {
        val pack=javaClass.getResourceAsStream("/challenge-pack-v1.json")!!.use(ChallengePackCodec::read)
        assertEquals("A small morning pack",pack.title)
        assertEquals("dawn",pack.levels.getValue(ChallengeDifficulty.EASY).single().answer)
        assertEquals(listOf(1),pack.levels.getValue(ChallengeDifficulty.NORMAL).single().order)
        assertEquals(listOf(1,2,0),pack.levels.getValue(ChallengeDifficulty.HARD).single().order)
        assertEquals(pack,ChallengePackCodec.decode(ChallengePackCodec.encode(pack)))
    }
    @Test fun nativePackExecutesTextChoiceAndSequenceTasksAtEveryLevel() {
        for(kind in PackTaskKind.entries) {
            val task=when(kind) {PackTaskKind.TEXT->PackTask(kind,"Say café",answer="café",caseSensitive=false)
                PackTaskKind.CHOICE->PackTask(kind,"Even",choices=listOf("1","2"),order=listOf(1))
                PackTaskKind.ORDER->PackTask(kind,"Order",choices=listOf("a","b","c"),order=listOf(2,0,1))}
            val pack=ChallengePack("Pack",ChallengeDifficulty.entries.associateWith {listOf(task)})
            for(level in ChallengeDifficulty.entries) {
                val p=ChallengePolicy(setOf(ChallengeKind.CUSTOM),level,rounds=1,pack=pack);var s=state(p)
                if(kind==PackTaskKind.TEXT) s=WakeChallenges.respond(p,s,ChallengeResponse.Text("CAFE\u0301"))
                else task.order.forEach {s=WakeChallenges.respond(p,s,ChallengeResponse.Select(it))}
                assertTrue(s.satisfied(p))
            }
        }
    }
    @Test fun declarativePacksHaveStrictBoundedUtf8AndRejectScriptsUnknownFieldsAndDuplicateKeys() {
        val original=ChallengePackCodec.encode(ChallengePackCodec.example())
        assertEquals(ChallengePackCodec.example(),ChallengePackCodec.read(ByteArrayInputStream(original.toByteArray())))
        for(value in listOf(original.replace("\"version\":1","\"version\":99"),original.replace("\"version\":1","\"version\":1,\"script\":\"fetch()\""),original.replace("\"version\":1","\"version\":1,\"vers\\u0069on\":1"),"[".repeat(1000)+"]".repeat(1000)))
            reject {ChallengePackCodec.decode(value)}
        reject {ChallengePackCodec.read(ByteArrayInputStream(byteArrayOf(0xc3.toByte(),0x28)))}
        reject {ChallengePackCodec.read(ByteArrayInputStream(ByteArray(ChallengePackCodec.MAX_BYTES+1)))}
        reject {ChallengePackCodec.read(object: InputStream() {override fun read()=0;override fun read(b: ByteArray,off: Int,len: Int)=0})}
    }
    @Test fun malformedPackRulesAndUnicodeControlsAreRefused() {
        reject {PackTask(PackTaskKind.ORDER,"Order",choices=listOf("a","b"),order=listOf(0,0)).validate()}
        reject {PackTask(PackTaskKind.CHOICE,"Choose",choices=listOf("a","a"),order=listOf(0)).validate()}
        reject {PackTask(PackTaskKind.TEXT,"\u0000",answer="x").validate()}
        reject {PackTask(PackTaskKind.TEXT,"\uD800",answer="x").validate()}
        reject {ChallengePack("No levels",emptyMap()).validate()}
        // Markup/URLs are plain text, never interpreted as HTML or a network request.
        PackTask(PackTaskKind.TEXT,"<script>fetch('https://example.invalid')</script>",answer="x").validate()
    }
    @Test fun versionTwoAlarmBackupContainsExactFrozenPackAndRestoresDisabledDefinition() {
        val p=ChallengePolicy(setOf(ChallengeKind.CUSTOM),pack=ChallengePackCodec.example())
        val rule=AlarmRule("pack",java.time.LocalTime.of(7,0),Repeat.Once(java.time.LocalDate.of(2030,1,1)),challenge=p)
        assertEquals(rule,AlarmRuleCodec.decode(AlarmRuleCodec.encode(rule)))
        val bytes=ByteArrayOutputStream().also {AlarmArchive.write(listOf(rule),it)}.toByteArray()
        assertTrue(String(bytes).contains("\"version\":2"));assertEquals(listOf(rule),AlarmArchive.inspect(ByteArrayInputStream(bytes)).alarms)
    }
}
