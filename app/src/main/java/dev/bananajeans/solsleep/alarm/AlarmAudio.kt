// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.alarm

import android.content.Context
import android.media.*
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import dev.bananajeans.solsleep.R

/** Local gain only; never changes STREAM_ALARM or fights a phone call's audio focus. */
class AlarmAudio(private val context: Context,private val rampSeconds: Int) : AutoCloseable {
    private val handler=Handler(Looper.getMainLooper())
    private val manager=context.getSystemService(AudioManager::class.java)
    private val attributes=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private var capExpires=0L
    @Volatile private var held=true;private var duck=false;private var cap=1f
    private val focus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(attributes).setOnAudioFocusChangeListener {change->
            held=change==AudioManager.AUDIOFOCUS_GAIN || change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
            duck=change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
            if(!held) {runCatching {player?.pause()};tone?.stopTone()}
            else runCatching {player?.start()}
            applyGain()
        }.build()
    private var player: MediaPlayer?=null;private var tone: ToneGenerator?=null
    private var toneVolume= -1;private var started=0L;@Volatile private var closed=false
    val hasFocus: Boolean get()=held && !closed
    fun setGainLimit(value: Float) {cap=value.coerceIn(.05f,1f);capExpires=SystemClock.elapsedRealtime()+2000;applyGain()}
    private fun gain(): Float {
        val ramp=if(rampSeconds==0) 1f else (.12f+(SystemClock.elapsedRealtime()-started)/(rampSeconds*1000f)*.88f).coerceAtMost(1f)
        return minOf(ramp,if(SystemClock.elapsedRealtime()<capExpires) cap else 1f)*(if(duck) .2f else 1f)
    }
    private fun applyGain() {
        if(closed) return
        val gain=gain();runCatching {player?.setVolume(gain,gain)}
        if(player==null && started!=0L) {
            val volume=(80*gain).toInt().coerceIn(1,80)
            if(toneVolume!=volume) {
                tone?.stopTone();tone?.release()
                @Suppress("DEPRECATION")
                val replacement=runCatching {ToneGenerator(AudioManager.STREAM_ALARM,volume)}.getOrNull()
                tone=replacement;toneVolume=volume
            }
        }
    }
    fun start() {
        if(closed || started!=0L) return
        held=manager.requestAudioFocus(focus)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        started=SystemClock.elapsedRealtime()
        player=runCatching {MediaPlayer.create(context,R.raw.dawn,attributes,0)?.apply {
            isLooping=true;setWakeMode(context,PowerManager.PARTIAL_WAKE_LOCK)
            setVolume(gain(),gain());if(held) start()
        }}.getOrNull()
        if(player==null) {
            val candidate=MediaPlayer()
            player=try {
                candidate.apply {setAudioAttributes(attributes);setDataSource(context,RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
                    isLooping=true;prepare();setVolume(gain(),gain());if(held) start()}
            } catch(_: Exception) {candidate.release();null}
        }
        applyGain()
        handler.post(object: Runnable {
            private var lastTone= -2000L
            override fun run() {
                if(closed) return
                applyGain()
                if(held && player==null && SystemClock.elapsedRealtime()-lastTone>=2000) {
                    tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD,800);lastTone=SystemClock.elapsedRealtime()
                }
                handler.postDelayed(this,100)
            }
        })
    }
    override fun close() {
        closed=true;handler.removeCallbacksAndMessages(null)
        runCatching {player?.stop()};runCatching {player?.release()};player=null
        tone?.release();tone=null;manager.abandonAudioFocusRequest(focus)
    }
}
