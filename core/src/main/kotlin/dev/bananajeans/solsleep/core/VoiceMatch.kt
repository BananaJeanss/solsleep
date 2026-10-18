// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.core

import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.math.*

/** Frozen, portable reference. No executable content, live URI, speech upload or account. */
data class VoiceClip(val title: String,val pcm: String) {
    init {ChallengeText.check(title,120);require(pcm.length in 10668..128000);samples()}
    fun samples(): ShortArray {
        require(pcm.length<=128000)
        val bytes=Base64.getDecoder().decode(pcm)
        require(bytes.size in 8000..96000 && bytes.size%2==0)
        require(Base64.getEncoder().encodeToString(bytes)==pcm)
        return ShortArray(bytes.size/2) {i->((bytes[i*2].toInt() and 255) or (bytes[i*2+1].toInt() shl 8)).toShort()}
    }
    companion object {
        fun from(title: String,samples: ShortArray): VoiceClip {
            require(samples.size in 4000..48000)
            val bytes=ByteArray(samples.size*2)
            samples.forEachIndexed {i,v->bytes[2*i]=v.toByte();bytes[2*i+1]=(v.toInt() shr 8).toByte()}
            return VoiceClip(title,Base64.getEncoder().encodeToString(bytes))
        }
    }
}

/** Bounded PCM WAV only. Unknown chunks are skipped, never executed or externally resolved. */
object VoiceWav {
    const val MAX_BYTES=2*1024*1024
    fun read(input: InputStream): ShortArray {
        val out=ByteArrayOutputStream();val block=ByteArray(8192)
        while(true) {val n=input.read(block,0,minOf(block.size,MAX_BYTES+1-out.size()));if(n<0) break
            require(n>0);out.write(block,0,n);require(out.size()<=MAX_BYTES)}
        val bytes=out.toByteArray();require(bytes.size>=44)
        val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun text(at: Int)=String(bytes,at,4,Charsets.US_ASCII)
        require(text(0)=="RIFF" && text(8)=="WAVE" && b.getInt(4).toLong()+8==bytes.size.toLong())
        var at=12;var rate=0;var channels=0;var data: Pair<Int,Int>?=null;var format=false
        while(at<bytes.size) {
            require(at<=bytes.size-8);val size=b.getInt(at+4);require(size>=0 && size.toLong()+at+8<=bytes.size)
            when(text(at)) {
                "fmt "->{require(!format && size==16);format=true
                    require(b.getShort(at+8).toInt()==1);channels=b.getShort(at+10).toInt();rate=b.getInt(at+12)
                    require(channels in 1..2 && rate in 8000..48000 && b.getShort(at+22).toInt()==16)
                    require(b.getInt(at+16)==rate*channels*2 && b.getShort(at+20).toInt()==channels*2)}
                "data"->{require(data==null);data=at+8 to size}
            }
            at=Math.addExact(at,8+size+(size%2));require(at<=bytes.size)
        }
        require(format);val (start,size)=requireNotNull(data);require(size%(channels*2)==0)
        val frames=size/(channels*2);require(frames.toLong()*2>=rate && frames<=rate*6)
        // Average all input samples in each 8 kHz bin; no platform decoder or unbounded allocation.
        val count=(frames.toLong()*8000/rate).toInt()
        return ShortArray(count) {i->
            val first=(i.toLong()*rate/8000).toInt();val end=minOf(frames,((i+1L)*rate/8000).toInt()).coerceAtLeast(first+1)
            var sum=0L
            for(frame in first until end) for(c in 0 until channels) sum+=b.getShort(start+(frame*channels+c)*2)
            (sum/((end-first)*channels)).toInt().toShort()
        }
    }
    fun encode(samples: ShortArray): ByteArray {
        require(samples.size in 4000..48000)
        return ByteBuffer.allocate(44+samples.size*2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray());putInt(36+samples.size*2);put("WAVEfmt ".toByteArray());putInt(16)
            putShort(1);putShort(1);putInt(8000);putInt(16000);putShort(2);putShort(16)
            put("data".toByteArray());putInt(samples.size*2);samples.forEach {putShort(it)}
        }.array()
    }
}

/** Experimental rhythm/pitch similarity, NOT word recognition, speaker identity or wakefulness. */
object VoiceMatch {
    /** First half-second is a user-requested quiet baseline. Suppress steady alarm/room energy.
     * This is a conservative level gate, not source separation or proof against replay. */
    fun suppressBaseline(samples: ShortArray): ShortArray {
        require(samples.size in 4000..48000)
        fun power(from: Int,to: Int): Double {var sum=0.0;for(i in from until to) {val x=samples[i]/32768.0;sum+=x*x};return sum/(to-from)}
        val floor=power(0,4000)*6.31 // 8 dB above the initial room/alarm baseline.
        val result=ShortArray(samples.size)
        for(from in 4000 until samples.size step 800) {
            val to=minOf(from+800,samples.size)
            if(power(from,to)>maxOf(floor,1e-7)) samples.copyInto(result,from,from,to)
        }
        return result
    }
    private data class Frame(val energy: Double,val pitch: Double)
    private fun features(samples: ShortArray): List<Frame> {
        require(samples.size in 4000..48000)
        if(samples.count {abs(it.toInt())>32000}>samples.size/100) return emptyList()
        val frames=(0..samples.size-320 step 160).map {start->
            val x=DoubleArray(320) {samples[start+it]/32768.0}
            val mean=x.average();for(i in x.indices) x[i]-=mean
            val power=x.sumOf {it*it};var best=0.0;var lag=0
            for(d in 20..114) {
                var dot=0.0;var a=0.0;var b=0.0
                for(i in 0 until 320-d) {dot+=x[i]*x[i+d];a+=x[i]*x[i];b+=x[i+d]*x[i+d]}
                val correlation=dot/sqrt((a*b).coerceAtLeast(1e-16))
                if(correlation>best) {best=correlation;lag=d}
            }
            Frame(10*log10((power/320).coerceAtLeast(1e-10)),if(best>.65 && power/320>1e-6) 8000.0/lag else 0.0)
        }
        val peak=frames.maxOfOrNull {it.energy} ?: return emptyList()
        if(peak< -50) return emptyList()
        val first=frames.indexOfFirst {it.energy>peak-25};val last=frames.indexOfLast {it.energy>peak-25}
        if(first<0 || last-first<12) return emptyList()
        val trimmed=frames.subList(first,last+1)
        if(trimmed.count {it.pitch>0}<8) return emptyList()
        val pitches=trimmed.filter {it.pitch>0}.map {it.pitch}.sorted();val median=pitches[pitches.size/2]
        return trimmed.map {Frame(((it.energy-peak)/25).coerceAtLeast(-2.0),if(it.pitch>0) log2(it.pitch/median) else -4.0)}
    }
    fun score(reference: ShortArray,attempt: ShortArray): Int {
        val a=features(reference);val b=features(attempt)
        if(a.isEmpty() || b.isEmpty()) return 0
        val ratio=b.size.toDouble()/a.size;if(ratio !in .55..1.8) return 0
        var previous=DoubleArray(b.size+1) {Double.POSITIVE_INFINITY};previous[0]=0.0
        a.forEachIndexed {i,x->
            val next=DoubleArray(b.size+1) {Double.POSITIVE_INFINITY}
            b.forEachIndexed {j,y->
                if(abs((i+.5)/a.size-(j+.5)/b.size)<=.2) {
                    val pitch=if(x.pitch== -4.0 && y.pitch== -4.0) 0.0 else if(x.pitch== -4.0 || y.pitch== -4.0) 1.0 else abs(x.pitch-y.pitch).coerceAtMost(2.0)
                    val cost=abs(x.energy-y.energy)*.6+pitch*.4
                    next[j+1]=cost+minOf(previous[j],previous[j+1]+.03,next[j]+.03)
                }
            }
            previous=next
        }
        val cost=previous.last()/maxOf(a.size,b.size)
        if(!cost.isFinite()) return 0
        return (100*(1-cost)-abs(ln(ratio))*20).roundToInt().coerceIn(0,100)
    }
    fun threshold(level: ChallengeDifficulty)=listOf(40,55,70)[level.ordinal]
}
