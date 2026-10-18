// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.core

import kotlinx.serialization.json.*
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.text.Normalizer
import java.util.Locale

/** Declarative content only: no code, URLs, file references, regular expressions or platform calls. */
enum class PackTaskKind { TEXT, CHOICE, ORDER }
data class PackTask(val kind: PackTaskKind,val prompt: String,val answer: String="",val choices: List<String> = emptyList(),
    val order: List<Int> = emptyList(),val caseSensitive: Boolean=true) {
    fun validate() {
        ChallengeText.check(prompt,600);ChallengeText.check(answer,120,empty=true)
        require(choices.size<=9 && choices.distinct().size==choices.size)
        choices.forEach {ChallengeText.check(it,120)}
        when(kind) {
            PackTaskKind.TEXT->require(answer.isNotBlank() && choices.isEmpty() && order.isEmpty())
            PackTaskKind.CHOICE->require(choices.size in 2..9 && order.size==1 && order[0] in choices.indices && answer.isEmpty())
            PackTaskKind.ORDER->require(choices.size in 2..9 && order.sorted()==choices.indices.toList() && answer.isEmpty())
        }
    }
}
data class ChallengePack(val title: String,val levels: Map<ChallengeDifficulty,List<PackTask>>) {
    fun validate() {
        ChallengeText.check(title,80);require(levels.keys==ChallengeDifficulty.entries.toSet())
        levels.values.forEach {tasks->require(tasks.size in 1..24);tasks.forEach {it.validate()}}
    }
}
object ChallengeText {
    fun check(value: String,max: Int,empty: Boolean=false) {
        require(value.length<=max && (empty || value.isNotBlank()))
        var i=0
        while(i<value.length) {
            val c=value[i]
            require(!c.isISOControl() || c=='\n')
            if(c.isHighSurrogate()) {require(i+1<value.length && value[i+1].isLowSurrogate());i++}
            else require(!c.isLowSurrogate())
            i++
        }
    }
    fun matches(actual: String,expected: String,caseSensitive: Boolean=true): Boolean {
        if(actual.length>120) return false
        fun normal(value: String)=Normalizer.normalize(value.trim(),Normalizer.Form.NFC).let {if(caseSensitive) it else it.lowercase(Locale.ROOT)}
        return normal(actual)==normal(expected)
    }
}
/** Detect duplicate object keys/depth before kotlinx JSON creates any logical object. */
internal fun strictJson(text: String,maxDepth: Int=12): JsonObject {
    val stack=mutableListOf<MutableSet<String>?>();var i=0
    while(i<text.length) {
        when(text[i]) {
            '{'->{stack.add(mutableSetOf());require(stack.size<=maxDepth)}
            '['->{stack.add(null);require(stack.size<=maxDepth)}
            '}',']'->{require(stack.isNotEmpty());stack.removeAt(stack.lastIndex)}
            '"'->{
                val start=i++;var escaped=false
                while(i<text.length) {
                    val c=text[i]
                    if(!escaped && c=='"') break
                    if(c=='\\' && !escaped) escaped=true else escaped=false
                    i++
                }
                require(i<text.length)
                var next=i+1;while(next<text.length && text[next].isWhitespace()) next++
                if(next<text.length && text[next]==':') {
                    val keys=requireNotNull(stack.lastOrNull())
                    require(keys.add(Json.parseToJsonElement(text.substring(start,i+1)).jsonPrimitive.content)) {"Duplicate object key"}
                }
            }
        }
        i++
    }
    require(stack.isEmpty());return Json.parseToJsonElement(text).jsonObject
}
object ChallengePackCodec {
    const val MAX_BYTES=48*1024
    fun read(input: InputStream): ChallengePack {
        val out=java.io.ByteArrayOutputStream();val block=ByteArray(4096)
        while(true) {val n=input.read(block,0,minOf(block.size,MAX_BYTES+1-out.size()));if(n<0) break
            require(n>0);out.write(block,0,n);require(out.size()<=MAX_BYTES)}
        val bytes=out.toByteArray()
        val text=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
        return decode(text)
    }
    fun encode(pack: ChallengePack): String=element(pack).toString().also {require(it.toByteArray(Charsets.UTF_8).size<=MAX_BYTES)}
    fun decode(text: String): ChallengePack {
        require(text.toByteArray(Charsets.UTF_8).size<=MAX_BYTES)
        return decode(strictJson(text,7))
    }
    internal fun element(pack: ChallengePack): JsonObject {
        pack.validate()
        return buildJsonObject {
            put("format","solsleep-challenge-pack");put("version",1);put("title",pack.title)
            put("levels",buildJsonObject {ChallengeDifficulty.entries.forEach {level->
                put(level.name,JsonArray(pack.levels.getValue(level).map {task->buildJsonObject {
                    put("kind",task.kind.name);put("prompt",task.prompt)
                    when(task.kind) {
                        PackTaskKind.TEXT->{put("answer",task.answer);put("caseSensitive",task.caseSensitive)}
                        else->{put("choices",JsonArray(task.choices.map(::JsonPrimitive)));put("order",JsonArray(task.order.map(::JsonPrimitive)))}
                    }
                }}))
            }})
        }
    }
    internal fun decode(root: JsonObject): ChallengePack {
        require(root.keys==setOf("format","version","title","levels") && root.text("format")=="solsleep-challenge-pack" && root.number("version")==1L)
        val levels=root.getValue("levels").jsonObject
        require(levels.keys==ChallengeDifficulty.entries.map {it.name}.toSet())
        val value=ChallengePack(root.text("title"),ChallengeDifficulty.entries.associateWith {level->
            val array=levels.getValue(level.name).jsonArray;require(array.size in 1..24)
            array.map {element->
                val j=element.jsonObject;val kind=PackTaskKind.valueOf(j.text("kind"))
                val fields=if(kind==PackTaskKind.TEXT) setOf("kind","prompt","answer","caseSensitive") else setOf("kind","prompt","choices","order")
                require(j.keys==fields)
                if(kind==PackTaskKind.TEXT) PackTask(kind,j.text("prompt"),answer=j.text("answer"),caseSensitive=j.flag("caseSensitive"))
                else {
                    val choices=j.getValue("choices").jsonArray;val order=j.getValue("order").jsonArray
                    require(choices.size in 2..9 && order.size in 1..9)
                    PackTask(kind,j.text("prompt"),choices=choices.map {it.jsonPrimitive.also {p->require(p.isString)}.content},
                        order=order.map {it.jsonPrimitive.also {p->require(!p.isString)}.int})
                }
            }
        })
        value.validate();require(encode(value).toByteArray().size<=MAX_BYTES);return value
    }
    fun example()=ChallengePack("My morning tasks",ChallengeDifficulty.entries.associateWith {level->
        listOf(PackTask(PackTaskKind.TEXT,"Type the morning code: ${listOf("MOON","SUNRISE","GOOD MORNING")[level.ordinal]}",
            answer=listOf("MOON","SUNRISE","GOOD MORNING")[level.ordinal],caseSensitive=false),
            PackTask(PackTaskKind.CHOICE,"Which number is even?",choices=listOf("7","12","19"),order=listOf(1)),
            PackTask(PackTaskKind.ORDER,"Put the day in order",choices=listOf("Evening","Morning","Afternoon"),order=listOf(1,2,0)))
    })
}
