// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.challenge

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.bananajeans.solsleep.R
import dev.bananajeans.solsleep.core.*

fun challengeName(kind: ChallengeKind): Int=when(kind) {
    ChallengeKind.MATH->R.string.task_math
    ChallengeKind.SHEEP->R.string.task_sheep
    ChallengeKind.TAP_ORDER->R.string.task_order
    ChallengeKind.MEMORY_DIGITS->R.string.task_memory
    ChallengeKind.MEMORY_PATTERN->R.string.task_pattern
    ChallengeKind.MAZE->R.string.task_maze
    ChallengeKind.REVERSE_DIGITS->R.string.task_reverse
    ChallengeKind.COPY_CODE->R.string.task_copy
    ChallengeKind.NFC->R.string.task_nfc
    ChallengeKind.VOICE->R.string.task_voice
    ChallengeKind.VOICEOVER->R.string.task_voiceover
    ChallengeKind.CUSTOM->R.string.task_custom
}
fun difficultyName(level: ChallengeDifficulty)=when(level) {
    ChallengeDifficulty.EASY->R.string.task_easy
    ChallengeDifficulty.NORMAL->R.string.task_normal
    ChallengeDifficulty.HARD->R.string.task_hard
}

/** Shared by real ringing and rehearsal. All completion decisions remain in core. */
@Composable fun ChallengeContent(policy: ChallengePolicy,state: WakeChallenge,busy: Boolean,onResponse: (ChallengeResponse)->Unit) {
    if(state.satisfied(policy)) {Text(stringResource(R.string.task_complete));return}
    val q=remember(policy,state.seed,state.stage,state.fallback) {WakeChallenges.question(policy,state)}
    var answer by rememberSaveable(state.id,state.stage,state.fallback) {mutableStateOf("")}
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(stringResource(challengeName(q.kind)),style=MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.task_progress,state.stage+1,state.total(policy),pluralStringResource(R.plurals.task_misses,state.failures,state.failures)))
        when(q.kind) {
            ChallengeKind.MATH,ChallengeKind.COPY_CODE,ChallengeKind.REVERSE_DIGITS,ChallengeKind.MEMORY_DIGITS,ChallengeKind.CUSTOM->{
                if(q.kind==ChallengeKind.CUSTOM) Text(requireNotNull(q.task).prompt)
                else Text(when(q.kind) {
                    ChallengeKind.MATH->if(q.numbers.size==3) "${q.numbers[0]} × ${q.numbers[1]} + ${q.numbers[2]} = ?" else "${q.numbers[0]} ${q.symbol} ${q.numbers[1]} = ?"
                    ChallengeKind.COPY_CODE->q.expected
                    ChallengeKind.REVERSE_DIGITS->stringResource(R.string.task_reverse_hint,q.expected)
                    else->if(state.hidden) stringResource(R.string.task_recall) else q.expected
                },style=MaterialTheme.typography.headlineMedium)
                if(q.kind==ChallengeKind.MEMORY_DIGITS && !state.hidden) {
                    Button(onClick={onResponse(ChallengeResponse.Ready)},enabled=!busy) {Text(stringResource(R.string.task_ready))}
                } else if(q.task?.kind in setOf(PackTaskKind.CHOICE,PackTaskKind.ORDER)) {
                    q.task!!.choices.forEachIndexed {i,choice->
                        OutlinedButton(onClick={onResponse(ChallengeResponse.Select(i))},enabled=!busy,modifier=Modifier.fillMaxWidth()) {Text(choice)}
                    }
                    if(q.task!!.kind==PackTaskKind.ORDER) Text(stringResource(R.string.task_steps,state.step,q.task!!.order.size))
                } else {
                    OutlinedTextField(answer,{if(it.length<=120) answer=it},label={Text(stringResource(R.string.task_answer))},
                        modifier=Modifier.fillMaxWidth(),singleLine=true,enabled=!busy,
                        keyboardOptions=KeyboardOptions(keyboardType=if(q.kind==ChallengeKind.CUSTOM) KeyboardType.Text else KeyboardType.Number))
                    Button(onClick={onResponse(ChallengeResponse.Text(answer));answer=""},enabled=!busy && answer.isNotBlank()) {Text(stringResource(R.string.task_check))}
                }
            }
            ChallengeKind.SHEEP->{
                Text(stringResource(R.string.task_sheep_hint))
                FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp),verticalArrangement=Arrangement.spacedBy(6.dp),maxItemsInEachRow=5) {
                    repeat(q.count) {i->
                        val sleeping=i==q.target
                        val description=stringResource(if(sleeping) R.string.task_sleeping_sheep else R.string.task_awake_sheep,i+1)
                        FilledTonalButton(onClick={onResponse(ChallengeResponse.Select(i))},enabled=!busy,
                            contentPadding=PaddingValues(4.dp),modifier=Modifier.size(56.dp).semantics {contentDescription=description}) {
                            Sheep(sleeping)
                        }
                    }
                }
            }
            ChallengeKind.TAP_ORDER->{
                Text(stringResource(R.string.task_order_hint,state.step+1))
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),maxItemsInEachRow=4) {
                    q.numbers.forEachIndexed {i,n->OutlinedButton(onClick={onResponse(ChallengeResponse.Select(i))},enabled=!busy && n>state.step,
                        modifier=Modifier.heightIn(min=56.dp)) {Text(n.toString())}}
                }
            }
            ChallengeKind.MEMORY_PATTERN->{
                if(!state.hidden) {
                    Text(q.numbers.joinToString(" → ") {(it+1).toString()},style=MaterialTheme.typography.headlineMedium)
                    Button(onClick={onResponse(ChallengeResponse.Ready)},enabled=!busy) {Text(stringResource(R.string.task_ready))}
                } else {
                    Text(stringResource(R.string.task_steps,state.step,q.numbers.size))
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        repeat(4) {i->OutlinedButton(onClick={onResponse(ChallengeResponse.Select(i))},enabled=!busy,modifier=Modifier.sizeIn(minWidth=64.dp,minHeight=64.dp)) {Text((i+1).toString())}}
                    }
                }
            }
            ChallengeKind.MAZE->{
                Text(stringResource(R.string.task_maze_hint,state.position/q.side+1,state.position%q.side+1,q.side))
                val ink=MaterialTheme.colorScheme.onSurface;val marker=MaterialTheme.colorScheme.primary
                Canvas(Modifier.fillMaxWidth().aspectRatio(1f).heightIn(max=300.dp).semantics {contentDescription=""}) {
                    val cell=minOf(size.width,size.height)/q.side
                    for(p in 0 until q.count) {
                        val x=(p%q.side)*cell;val y=(p/q.side)*cell
                        if(p<q.side || (p to p-q.side) !in q.maze) drawLine(ink,Offset(x,y),Offset(x+cell,y),2f)
                        if(p%q.side==0 || (p to p-1) !in q.maze) drawLine(ink,Offset(x,y),Offset(x,y+cell),2f)
                        if(p/q.side==q.side-1) drawLine(ink,Offset(x,y+cell),Offset(x+cell,y+cell),2f)
                        if(p%q.side==q.side-1) drawLine(ink,Offset(x+cell,y),Offset(x+cell,y+cell),2f)
                    }
                    val p=state.position
                    drawCircle(marker,cell*.22f,Offset((p%q.side+.5f)*cell,(p/q.side+.5f)*cell))
                    drawRect(marker,Offset((q.side-.8f)*cell,(q.side-.8f)*cell),Size(cell*.6f,cell*.6f),alpha=.45f)
                }
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    listOf(R.string.task_up,R.string.task_right,R.string.task_down,R.string.task_left).forEachIndexed {i,id->
                        val next=when(i) {0->state.position-q.side;1->state.position+1;2->state.position+q.side;else->state.position-1}
                        // Spoken availability makes the same maze playable without seeing the drawing.
                        OutlinedButton(onClick={onResponse(ChallengeResponse.Move(i))},enabled=!busy && (state.position to next) in q.maze) {Text(stringResource(id))}
                    }
                }
            }
            ChallengeKind.NFC->key(state.id,state.stage,state.fallback) {NfcTask(busy,onResponse)}
            ChallengeKind.VOICE,ChallengeKind.VOICEOVER->key(state.id,state.stage,state.fallback) {MicrophoneTask(policy,q.kind,busy,onResponse)}
        }
        if(q.kind in setOf(ChallengeKind.MEMORY_DIGITS,ChallengeKind.MEMORY_PATTERN) && state.hidden)
            TextButton(onClick={onResponse(ChallengeResponse.Replay)},enabled=!busy) {Text(stringResource(R.string.task_replay))}
        if(!state.fallback) TextButton(onClick={onResponse(ChallengeResponse.Fallback)},enabled=!busy) {Text(stringResource(R.string.task_fallback))}
    }
}

/** Original code-drawn sheep; no generated bitmap or third-party illustration. */
@Composable private fun Sheep(sleeping: Boolean) {
    Canvas(Modifier.size(44.dp)) {
        val w=size.width;val h=size.height
        repeat(5) {i->drawCircle(Color(0xffe7ecf2),w*.18f,Offset(w*(.22f+i*.12f),h*(if(i%2==0) .43f else .35f)))}
        drawOval(Color(0xffd5dfeb),Offset(w*.13f,h*.33f),Size(w*.63f,h*.43f))
        drawOval(Color(0xff45465f),Offset(w*.60f,h*.33f),Size(w*.30f,h*.38f))
        repeat(2) {i->val x=w*(.23f+i*.31f);drawLine(Color(0xff45465f),Offset(x,h*.69f),Offset(x,h*.91f),w*.07f)}
        if(sleeping) drawLine(Color.White,Offset(w*.69f,h*.48f),Offset(w*.81f,h*.48f),w*.04f)
        else drawCircle(Color.White,w*.045f,Offset(w*.75f,h*.47f))
    }
}
