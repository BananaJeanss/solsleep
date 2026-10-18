// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.core

import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Random
import java.util.UUID

enum class ChallengeDifficulty { EASY, NORMAL, HARD }
enum class ChallengeKind { MATH, SHEEP, TAP_ORDER, MEMORY_DIGITS, MEMORY_PATTERN, MAZE, REVERSE_DIGITS, COPY_CODE, NFC, VOICE, VOICEOVER, CUSTOM }
enum class ChallengeAction { SNOOZE, DISMISS }
data class ChallengePolicy(val kinds: Set<ChallengeKind> = setOf(ChallengeKind.MATH),val difficulty: ChallengeDifficulty=ChallengeDifficulty.NORMAL,
    val rounds: Int=3,val quietPercent: Int=15,val fallbackKind: ChallengeKind=ChallengeKind.COPY_CODE,
    val nfcHash: String?=null,val pack: ChallengePack?=null,val voiceClip: VoiceClip?=null) {
    init {
        require(kinds.isNotEmpty() && rounds in 1..12 && quietPercent in 5..40)
        require(fallbackKind in setOf(ChallengeKind.COPY_CODE,ChallengeKind.MATH))
        require(nfcHash==null || Regex("[0-9a-f]{64}").matches(nfcHash))
        require(ChallengeKind.NFC !in kinds || nfcHash!=null)
        require(ChallengeKind.CUSTOM !in kinds || pack!=null)
        require(ChallengeKind.VOICEOVER !in kinds || voiceClip!=null)
        pack?.let {ChallengePackCodec.encode(it)}
    }
    fun fingerprint(): String=MessageDigest.getInstance("SHA-256").digest(ChallengePolicyCodec.encode(this).toByteArray())
        .joinToString("") {"%02x".format(it)}
}
data class WakeChallenge(val id: String,val seed: Long,val policyHash: String,val action: ChallengeAction,val startedAt: Instant,
    val revision: Long=1,val stage: Int=0,val step: Int=0,val position: Int=0,val hidden: Boolean=false,
    val failures: Int=0,val fallback: Boolean=false) {
    init {
        require(UUID.fromString(id).toString()==id && Regex("[0-9a-f]{64}").matches(policyHash))
        require(startedAt.nano%1_000_000==0 && startedAt>=Instant.parse("1900-01-01T00:00:00Z") && startedAt<Instant.parse("+10000-01-01T00:00:00Z"))
        require(revision>=1 && stage in 0..14 && step in 0..64 && position in 0..63 && failures in 0..100_000)
    }
    fun total(policy: ChallengePolicy)=policy.rounds+if(fallback) 2 else 0
    fun satisfied(policy: ChallengePolicy): Boolean {require(policyHash==policy.fingerprint() && stage<=total(policy));return stage==total(policy)}
    fun gain(policy: ChallengePolicy,now: Instant): Float {
        if(satisfied(policy) || now<startedAt) return 1f
        val seconds=Duration.between(startedAt,now).toMillis()/1000f
        return (policy.quietPercent/100f+failures*.15f+seconds/60f*(1-policy.quietPercent/100f)).coerceIn(.05f,1f)
    }
}
sealed interface ChallengeResponse {
    data class Text(val value: String): ChallengeResponse
    data class Select(val index: Int): ChallengeResponse
    data class Move(val direction: Int): ChallengeResponse
    data class Nfc(val hash: String): ChallengeResponse
    data class Voice(val sustainedMillis: Long,val baselineDb: Double,val signalDb: Double): ChallengeResponse
    data class Voiceover(val samples: ShortArray): ChallengeResponse
    data object Ready: ChallengeResponse
    data object Replay: ChallengeResponse
    data object Fallback: ChallengeResponse
}
data class ChallengeQuestion(val kind: ChallengeKind,val numbers: List<Int> = emptyList(),val symbol: String="",val expected: String="",
    val target: Int=0,val count: Int=0,val maze: Set<Pair<Int,Int>> = emptySet(),val side: Int=0,val task: PackTask?=null)

/** Deterministic prompts + host validation. No client-reported 'complete', script or network result. */
object WakeChallenges {
    private fun random(state: WakeChallenge)=Random(state.seed xor (state.stage.toLong()*-7046029254386353131L))
    fun question(policy: ChallengePolicy,state: WakeChallenge): ChallengeQuestion {
        require(!state.satisfied(policy) && state.stage<state.total(policy))
        val level=policy.difficulty.ordinal;val rng=random(state)
        val pool=policy.kinds.sortedBy {it.ordinal}.toMutableList()
        java.util.Collections.shuffle(pool,Random(state.seed))
        val kind=if(state.fallback) policy.fallbackKind else pool[state.stage%pool.size]
        fun digits(n: Int)=buildString {repeat(n) {append(rng.nextInt(10))}}
        return when(kind) {
            ChallengeKind.MATH->{
                val a=1+rng.nextInt(listOf(10,90,30)[level]);val b=1+rng.nextInt(listOf(10,12,12)[level])
                if(level==2) {val c=1+rng.nextInt(99);ChallengeQuestion(kind,listOf(a,b,c),"× +",(a*b+c).toString())}
                else if(level==1 && rng.nextBoolean()) ChallengeQuestion(kind,listOf(a,b),"×",(a*b).toString())
                else ChallengeQuestion(kind,listOf(a,b),"+",(a+b).toString())
            }
            ChallengeKind.SHEEP->{val count=listOf(9,16,25)[level];ChallengeQuestion(kind,target=rng.nextInt(count),count=count)}
            ChallengeKind.TAP_ORDER->{val count=listOf(6,9,12)[level];val values=(1..count).toMutableList();java.util.Collections.shuffle(values,rng);ChallengeQuestion(kind,numbers=values,count=count)}
            ChallengeKind.MEMORY_DIGITS,ChallengeKind.REVERSE_DIGITS,ChallengeKind.COPY_CODE->{
                val value=digits(listOf(4,6,8)[level]+if(kind==ChallengeKind.COPY_CODE) 2 else 0)
                ChallengeQuestion(kind,expected=value)
            }
            ChallengeKind.MEMORY_PATTERN->ChallengeQuestion(kind,numbers=List(listOf(3,5,7)[level]) {rng.nextInt(4)},count=4)
            ChallengeKind.MAZE->{
                val side=listOf(4,6,8)[level];val edges=mutableSetOf<Pair<Int,Int>>();val visited=mutableSetOf(0);val stack=mutableListOf(0)
                while(stack.isNotEmpty()) {
                    val current=stack.last()
                    val options=neighbors(current,side).filter {it !in visited}
                    if(options.isEmpty()) stack.removeAt(stack.lastIndex)
                    else {val next=options[rng.nextInt(options.size)];visited+=next;edges+=current to next;edges+=next to current;stack+=next}
                }
                ChallengeQuestion(kind,maze=edges,side=side,count=side*side)
            }
            ChallengeKind.CUSTOM->{val tasks=requireNotNull(policy.pack).levels.getValue(policy.difficulty);ChallengeQuestion(kind,task=tasks[rng.nextInt(tasks.size)])}
            else->ChallengeQuestion(kind)
        }
    }
    private fun neighbors(p: Int,n: Int)=listOfNotNull(if(p>=n) p-n else null,if(p%n<n-1) p+1 else null,
        if(p<n*(n-1)) p+n else null,if(p%n>0) p-1 else null)
    fun respond(policy: ChallengePolicy,state: WakeChallenge,response: ChallengeResponse): WakeChallenge {
        if(state.satisfied(policy)) return state
        val revision=Math.addExact(state.revision,1)
        if(response==ChallengeResponse.Fallback) return if(state.fallback) state else state.copy(revision=revision,fallback=true,step=0,position=0,hidden=false)
        val q=question(policy,state)
        fun fail()=state.copy(revision=revision,failures=(state.failures+1).coerceAtMost(100_000),
            step=if(q.kind in setOf(ChallengeKind.TAP_ORDER,ChallengeKind.MEMORY_PATTERN,ChallengeKind.CUSTOM)) 0 else state.step)
        fun done()=state.copy(revision=revision,stage=state.stage+1,step=0,position=0,hidden=false)
        fun step(total: Int)=if(state.step+1==total) done() else state.copy(revision=revision,step=state.step+1)
        val memory=q.kind in setOf(ChallengeKind.MEMORY_DIGITS,ChallengeKind.MEMORY_PATTERN)
        if(response==ChallengeResponse.Ready) return if(memory && !state.hidden) state.copy(revision=revision,hidden=true,step=0) else state
        if(response==ChallengeResponse.Replay) return if(memory && state.hidden) fail().copy(hidden=false,step=0) else state
        return when(q.kind) {
            ChallengeKind.MATH->if(response is ChallengeResponse.Text && numeric(response.value)==q.expected) done() else fail()
            ChallengeKind.COPY_CODE->if(response is ChallengeResponse.Text && response.value.trim()==q.expected) done() else fail()
            ChallengeKind.REVERSE_DIGITS->if(response is ChallengeResponse.Text && response.value.trim()==q.expected.reversed()) done() else fail()
            ChallengeKind.MEMORY_DIGITS->if(!state.hidden) state else if(response is ChallengeResponse.Text && response.value.trim()==q.expected) done() else fail()
            ChallengeKind.SHEEP->if(response is ChallengeResponse.Select && response.index==q.target) done() else fail()
            ChallengeKind.TAP_ORDER->if(response is ChallengeResponse.Select && response.index in q.numbers.indices && q.numbers[response.index]==state.step+1) step(q.count) else fail()
            ChallengeKind.MEMORY_PATTERN->if(!state.hidden) state else if(response is ChallengeResponse.Select && state.step<q.numbers.size && response.index==q.numbers[state.step]) step(q.numbers.size) else fail()
            ChallengeKind.MAZE->{
                if(response !is ChallengeResponse.Move || response.direction !in 0..3) fail()
                else {
                    val next=when(response.direction) {0->state.position-q.side;1->state.position+1;2->state.position+q.side;else->state.position-1}
                    if((state.position to next) !in q.maze) fail()
                    else if(next==q.count-1) done() else state.copy(revision=revision,position=next)
                }
            }
            ChallengeKind.NFC->if(response is ChallengeResponse.Nfc && response.hash.length==64 &&
                MessageDigest.isEqual(response.hash.toByteArray(),requireNotNull(policy.nfcHash).toByteArray())) done() else fail()
            ChallengeKind.VOICE->{
                val required=listOf(1000L,1500L,2000L)[policy.difficulty.ordinal]
                if(response is ChallengeResponse.Voice && response.sustainedMillis in required..15000 && response.baselineDb.isFinite() &&
                    response.signalDb.isFinite() && response.signalDb>=listOf(-34.0,-30.0,-26.0)[policy.difficulty.ordinal] &&
                    response.signalDb-response.baselineDb>=listOf(8.0,10.0,12.0)[policy.difficulty.ordinal] && response.signalDb< -1.0) done() else fail()
            }
            ChallengeKind.VOICEOVER->if(response is ChallengeResponse.Voiceover && response.samples.size in 4000..48000 &&
                VoiceMatch.score(requireNotNull(policy.voiceClip).samples(),response.samples)>=VoiceMatch.threshold(policy.difficulty)) done() else fail()
            ChallengeKind.CUSTOM->{
                val task=requireNotNull(q.task)
                when(task.kind) {
                    PackTaskKind.TEXT->if(response is ChallengeResponse.Text && ChallengeText.matches(response.value,task.answer,task.caseSensitive)) done() else fail()
                    PackTaskKind.CHOICE->if(response is ChallengeResponse.Select && response.index==task.order.single()) done() else fail()
                    PackTaskKind.ORDER->if(response is ChallengeResponse.Select && state.step<task.order.size && response.index==task.order[state.step]) step(task.order.size) else fail()
                }
            }
        }
    }
    private fun numeric(value: String): String? {
        if(value.length !in 1..120) return null
        val converted=value.trim().map {c->Character.digit(c,10).takeIf {it>=0}?.let {('0'.code+it).toChar()} ?: c}.joinToString("")
        return converted.toIntOrNull()?.toString()
    }
    fun tagHash(bytes: ByteArray): String {
        require(bytes.size in 1..32 && bytes.any {it!=0.toByte()})
        return MessageDigest.getInstance("SHA-256").digest("SolSleep NFC task v1".toByteArray()+bytes).joinToString("") {"%02x".format(it)}
    }
}
object ChallengePolicyCodec {
    fun encode(value: ChallengePolicy)=element(value).toString()
    fun decode(value: String): ChallengePolicy {require(value.toByteArray().size<=192*1024);return decode(strictJson(value))}
    internal fun element(value: ChallengePolicy)=buildJsonObject {
        put("version",1);put("kinds",JsonArray(value.kinds.sortedBy {it.ordinal}.map {JsonPrimitive(it.name)}))
        put("difficulty",value.difficulty.name);put("rounds",value.rounds);put("quietPercent",value.quietPercent)
        put("fallback",value.fallbackKind.name);put("nfcHash",value.nfcHash?.let(::JsonPrimitive) ?: JsonNull)
        put("pack",value.pack?.let(ChallengePackCodec::element) ?: JsonNull)
        put("voiceClip",value.voiceClip?.let {buildJsonObject {put("title",it.title);put("pcm",it.pcm)}} ?: JsonNull)
    }
    internal fun decode(j: JsonObject): ChallengePolicy {
        require(j.keys==setOf("version","kinds","difficulty","rounds","quietPercent","fallback","nfcHash","pack","voiceClip") && j.number("version")==1L)
        val kinds=j.getValue("kinds").jsonArray.map {require(it.jsonPrimitive.isString);ChallengeKind.valueOf(it.jsonPrimitive.content)}
        require(kinds.size in 1..ChallengeKind.entries.size && kinds.distinct().size==kinds.size)
        return ChallengePolicy(kinds.toSet(),ChallengeDifficulty.valueOf(j.text("difficulty")),j.integer("rounds"),j.integer("quietPercent"),
            ChallengeKind.valueOf(j.text("fallback")),j.nullableText("nfcHash"),j["pack"].takeUnless {it==null || it==JsonNull}?.jsonObject?.let(ChallengePackCodec::decode),
            j["voiceClip"].takeUnless {it==null || it==JsonNull}?.jsonObject?.let {
                require(it.keys==setOf("title","pcm"));VoiceClip(it.text("title"),it.text("pcm"))})
    }
}
object WakeChallengeCodec {
    fun encode(v: WakeChallenge)=buildJsonObject {
        put("version",1);put("id",v.id);put("seed",v.seed);put("policyHash",v.policyHash);put("action",v.action.name)
        put("started",v.startedAt.toEpochMilli());put("revision",v.revision);put("stage",v.stage);put("step",v.step)
        put("position",v.position);put("hidden",v.hidden);put("failures",v.failures);put("fallback",v.fallback)
    }.toString()
    fun decode(value: String): WakeChallenge {
        require(value.length<=2048);val j=strictJson(value,2)
        require(j.keys==setOf("version","id","seed","policyHash","action","started","revision","stage","step","position","hidden","failures","fallback") && j.number("version")==1L)
        return WakeChallenge(j.text("id"),j.number("seed"),j.text("policyHash"),ChallengeAction.valueOf(j.text("action")),
            Instant.ofEpochMilli(j.number("started")),j.number("revision"),j.integer("stage"),j.integer("step"),j.integer("position"),j.flag("hidden"),j.integer("failures"),j.flag("fallback"))
    }
}
