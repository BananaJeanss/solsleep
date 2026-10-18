// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.core

import kotlin.math.abs
import kotlin.math.log10

/** Relative PCM level evidence only; not calibrated sound pressure, words or liveness. */
object VoiceLevel {
    fun threshold(level: ChallengeDifficulty)=listOf(-34.0,-30.0,-26.0)[level.ordinal]
    fun delta(level: ChallengeDifficulty)=listOf(8.0,10.0,12.0)[level.ordinal]
    fun evidence(samples: ShortArray,level: ChallengeDifficulty): ChallengeResponse.Voice {
        require(samples.size in 4000..48000)
        fun rms(from: Int,to: Int): Double {
            var sum=0.0
            for(i in from until to) {val v=samples[i]/32768.0;sum+=v*v}
            return 10*log10((sum/(to-from)).coerceAtLeast(1e-10))
        }
        val baseline=rms(0,4000)
        if(samples.count {abs(it.toInt())>32000}>samples.size/100)
            return ChallengeResponse.Voice(0,baseline,Double.NaN)
        var run=0;var best=0;var sum=0.0;var qualified=0
        // Include the final complete 100 ms window. Incomplete trailing windows stay uncounted.
        for(from in 4000..samples.size-800 step 800) {
            val signal=rms(from,from+800)
            if(signal>=threshold(level) && signal-baseline>=delta(level) && signal< -1) {
                run++;qualified++;sum+=signal;best=maxOf(best,run)
            } else run=0
        }
        return ChallengeResponse.Voice(best*100L,baseline,if(qualified==0) Double.NaN else sum/qualified)
    }
}
