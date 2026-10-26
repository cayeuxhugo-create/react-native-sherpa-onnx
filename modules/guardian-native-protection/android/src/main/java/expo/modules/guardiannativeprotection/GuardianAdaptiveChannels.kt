package expo.modules.guardiannativeprotection

import kotlin.math.*

/** Capture-channel ranking, not a speech detector. No gain and no spatial sum.
 * All channels refer to the SAME AudioRecord clock. No physical mic mapping is assumed. */
internal class GuardianAdaptiveChannels(private val channels: Int, private val sampleRate: Int = 16000) {
    data class Result(val pcm: ShortArray, val selected: Int, val scores: List<Float>,
        val valid: List<Boolean>, val transitioned: Boolean, val transition: String)
    private val previous = FloatArray(channels)
    private val highpass = FloatArray(channels)
    private val lowpass = FloatArray(channels)
    private val noise = FloatArray(channels)
    private val smoothed = FloatArray(channels) { -60f }
    private var observed = 0L
    private var selected = 0
    private var pending = -1
    private var pendingSamples = 0
    private var sinceSwitch = sampleRate
    init { require(channels in 1..30 && sampleRate >= 8000) }

    fun process(input: ShortArray, count: Int, speech: Boolean): Result {
        require(count in 0..input.size && count % channels == 0)
        val n=count/channels
        if (n==0) return Result(ShortArray(0),selected,smoothed.toList(),List(channels){false},false,"NONE")
        val energy=DoubleArray(channels);val clipped=IntArray(channels);val peak=FloatArray(channels)
        val hpA=exp(-2.0*PI*120/sampleRate).toFloat()
        val lpA=(1-exp(-2.0*PI*3800/sampleRate)).toFloat()
        for (i in 0 until n) for (c in 0 until channels) {
            val raw=input[i*channels+c].toFloat()/32768f
            if (abs(raw)>=.979f) clipped[c]++
            peak[c]=max(peak[c],abs(raw))
            highpass[c]=hpA*(highpass[c]+raw-previous[c]);previous[c]=raw
            lowpass[c]+=lpA*(highpass[c]-lowpass[c]);energy[c]+=lowpass[c].toDouble()*lowpass[c]
        }
        val valid=BooleanArray(channels)
        val score=FloatArray(channels)
        for (c in 0 until channels) {
            val rms=sqrt(energy[c]/n).toFloat()
            valid[c]=peak[c]>1f/32768 && clipped[c]<=n/50
            if (rms>0 && noise[c]==0f) noise[c]=rms
            if (!speech && rms>0) {
                val alpha=if (rms<noise[c]) .12f else .003f
                noise[c]+=alpha*(rms-noise[c])
            }
            val ratio=sqrt(max(0f,rms*rms-noise[c]*noise[c]))/noise[c].coerceAtLeast(.00002f)
            val value=(20*log10(ratio.coerceAtLeast(.001f))).coerceIn(-60f,80f)-min(12f,600f*clipped[c]/n)
            // Time-based smoothing, independent of short AudioRecord reads.
            val alpha=(1-exp(-n.toDouble()/(sampleRate*.15))).toFloat()
            smoothed[c]+=alpha*(value-smoothed[c]);score[c]=smoothed[c]
        }
        // Suppress duplicated streams; they add no acoustic information.
        // Keep the active member to avoid toggling between identical copies.
        for (c in 0 until channels) if (valid[c] && c!=selected) {
            var dot=0.0;var aa=0.0;var bb=0.0
            for (i in 0 until n) {
                val a=input[i*channels+selected].toDouble();val b=input[i*channels+c].toDouble()
                dot+=a*b;aa+=a*a;bb+=b*b
            }
            if (valid[selected] && aa>0 && bb>0 && abs(dot)/sqrt(aa*bb)>.9995) valid[c]=false
        }
        val winner=(0 until channels).filter { valid[it] }.maxByOrNull { score[it] } ?: selected
        val unavailable=!valid[selected] && valid[winner]
        val ready=observed>=sampleRate/2
        val better=winner!=selected && ready && score[winner]>=score[selected]+3f && sinceSwitch>=sampleRate/2
        if (better) {
            if (pending!=winner) { pending=winner;pendingSamples=0 }
            pendingSamples+=n
        } else { pending=-1;pendingSamples=0 }
        val change=winner!=selected && (unavailable || pendingSamples>=sampleRate/5)
        val old=selected
        if (change) { selected=winner;sinceSwitch=0;pending=-1;pendingSamples=0 }
        observed+=n;sinceSwitch+=n
        val out=ShortArray(n) { input[it*channels+selected] }
        var transition="NONE"
        if (change) {
            val fade=min(n,sampleRate/100) // 10 ms, no inserted/dropped samples.
            var mixedEnergy=0.0;var minimumEnergy=0.0
            for (i in 0 until fade) {
                val a=input[i*channels+old].toDouble();val b=input[i*channels+selected].toDouble()
                val t=(i+1).toDouble()/fade;val mixed=a*(1-t)+b*t
                out[i]=mixed.roundToInt().coerceIn(-32768,32767).toShort()
                mixedEnergy+=mixed*mixed;minimumEnergy+=min(a*a,b*b)
            }
            transition="CROSSFADE"
            // Opposed/delayed channels can cancel during a crossfade. Prefer a
            // low-discontinuity splice rather than inserting an energy hole.
            if (mixedEnergy<minimumEnergy*.8 || unavailable) {
                var cut=0;var difference=Double.POSITIVE_INFINITY
                for (i in 0 until fade) {
                    val d=abs(input[i*channels+old].toDouble()-input[i*channels+selected])
                    if (d<difference) { difference=d;cut=i }
                }
                for (i in 0 until n) out[i]=input[i*channels+if (i<cut) old else selected]
                transition="LOW_DISCONTINUITY_SPLICE"
            }
        }
        return Result(out,selected,score.toList(),valid.toList(),change,transition)
    }
}
