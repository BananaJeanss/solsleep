// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.alarm

import android.content.Context
import android.media.*
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import dev.bananajeans.solsleep.R

/** Also used for a user-initiated, activity-visible preview. */
class AlarmAudio(private val context: Context,private val rampSeconds: Int) : AutoCloseable {
    private val handler=Handler(Looper.getMainLooper())
    private val manager=context.getSystemService(AudioManager::class.java)
    private val attributes=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private val focus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(attributes).setOnAudioFocusChangeListener { }.build()
    private var player: MediaPlayer?=null
    private var tone: ToneGenerator?=null
    private var started=0L
    private var closed=false
    fun start() {
        if(closed) return
        manager.requestAudioFocus(focus)
        started=SystemClock.elapsedRealtime()
        player=runCatching {
            MediaPlayer.create(context,R.raw.dawn,attributes,0)?.apply {
                isLooping=true
                setWakeMode(context,PowerManager.PARTIAL_WAKE_LOCK)
                setVolume(if(rampSeconds==0) 1f else .12f,if(rampSeconds==0) 1f else .12f)
                start()
            }
        }.getOrNull()
        if(player==null) player=runCatching {
            MediaPlayer().apply {
                setAudioAttributes(attributes)
                setDataSource(context,RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
                isLooping=true;prepare();start()
            }
        }.getOrNull()
        if(player==null) {
            @Suppress("DEPRECATION")
            tone=runCatching { ToneGenerator(AudioManager.STREAM_ALARM,80) }.getOrNull()
            handler.post(object: Runnable {
                override fun run() { if(!closed) {tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD,800);handler.postDelayed(this,2000)} }
            })
        } else if(rampSeconds>0) handler.post(object: Runnable {
            override fun run() {
                if(closed) return
                val gain=(.12f+(SystemClock.elapsedRealtime()-started)/(rampSeconds*1000f)*.88f).coerceAtMost(1f)
                runCatching { player?.setVolume(gain,gain) }
                if(gain<1f) handler.postDelayed(this,100)
            }
        })
    }
    override fun close() {
        closed=true
        handler.removeCallbacksAndMessages(null)
        runCatching {player?.stop()};runCatching {player?.release()};player=null
        tone?.release();tone=null
        manager.abandonAudioFocusRequest(focus)
    }
}
