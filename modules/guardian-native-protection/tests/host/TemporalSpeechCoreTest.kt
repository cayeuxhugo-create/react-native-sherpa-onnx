package expo.modules.guardiannativeprotection

/** Synthetic acoustic-posterior counterexamples. These assert rescue admission,
 * not the lexical correctness of any text or the accuracy of a speech model. */
internal object TemporalSpeechCoreTest {
    private var checks = 0

    @JvmStatic fun main(args: Array<String>) {
        emptyAndSilenceHaveNoCore()
        scatteredSpeechCannotStandInForAContinuousCore()
        islandAndCoreBoundariesAreInclusive()
        groupGapAndDensityBoundariesAreInclusive()
        discardedFlickerCannotBridgeGroups()
        qualifyingCoreWinsOverLargerSparseGroup()
        endingIslandAndSampleCoordinatesArePreserved()
        posteriorBoundaryMatchesFsmnFloatArithmetic()
        invalidPosteriorFailsClosed()
        everyRescuePrerequisiteIsMandatory()
        println("PASS $checks temporal-core/rescue checks: scattered noise, island/gap/density boundaries, discarded flicker, complete inference and source prerequisites")
    }

    private fun verify(value: Boolean, message: String) {
        check(value) { message }
        checks++
    }

    /** Signed lengths: positive is speech (p0=0), negative is silence (p0=1). */
    private fun sequence(vararg runs: Int): FloatArray {
        val result = FloatArray(runs.sumOf { kotlin.math.abs(it) })
        var offset = 0
        for (run in runs) repeat(kotlin.math.abs(run)) { result[offset++] = if (run > 0) 0f else 1f }
        return result
    }

    private fun emptyAndSilenceHaveNoCore() {
        for (input in listOf(floatArrayOf(), FloatArray(800) { 1f })) {
            val result = GuardianTemporalSpeechCore.evaluate(input)
            verify(!result.accepted && result.positiveFrames == 0 && result.corePositiveFrames == 0 &&
                result.coreSpanFrames == 0 && result.coreDensity == 0.0 &&
                result.coreStartFrame == -1 && result.coreEndFrameExclusive == -1,
                "Empty/silent posterior must not invent a temporal core")
        }
    }

    private fun scatteredSpeechCannotStandInForAContinuousCore() {
        val scattered = FloatArray(800) { if (it % 2 == 0) 0f else 1f }
        val result = GuardianTemporalSpeechCore.evaluate(scattered)
        verify(!result.accepted && result.positiveFrames == 400 && result.corePositiveFrames == 0,
            "Four hundred isolated positives must not count as coherent speech")
        val shortIslands = (1..20).flatMap { List(14) { 0f } + 1f }.toFloatArray()
        val short = GuardianTemporalSpeechCore.evaluate(shortIslands)
        verify(!short.accepted && short.positiveFrames == 280 && short.corePositiveFrames == 0,
            "Repeated 140 ms islands must remain excluded even with large total speech count")
    }

    private fun islandAndCoreBoundariesAreInclusive() {
        val tooShort = GuardianTemporalSpeechCore.evaluate(sequence(14))
        verify(tooShort.corePositiveFrames == 0, "A 14-frame island must be discarded")
        val minimumIsland = GuardianTemporalSpeechCore.evaluate(sequence(15))
        verify(!minimumIsland.accepted && minimumIsland.corePositiveFrames == 15,
            "A 15-frame island is retained but cannot rescue a short refusal")
        verify(!GuardianTemporalSpeechCore.evaluate(sequence(99)).accepted,
            "Ninety-nine retained positives are below the rescue threshold")
        val minimumCore = GuardianTemporalSpeechCore.evaluate(sequence(100))
        verify(minimumCore.accepted && minimumCore.corePositiveFrames == 100 && minimumCore.coreDensity == 1.0,
            "A coherent 100-frame core reaches the inclusive threshold")
    }

    private fun groupGapAndDensityBoundariesAreInclusive() {
        val join = GuardianTemporalSpeechCore.evaluate(sequence(50, -120, 50))
        verify(join.accepted && join.corePositiveFrames == 100 && join.coreSpanFrames == 220,
            "A gap of exactly 120 frames must join qualifying islands")
        val split = GuardianTemporalSpeechCore.evaluate(sequence(50, -121, 50))
        verify(!split.accepted && split.corePositiveFrames == 50 && split.coreSpanFrames == 50,
            "A gap of 121 frames must create independent groups")
        val densityEdge = GuardianTemporalSpeechCore.evaluate(sequence(25, -100, 25, -100, 25, -100, 25))
        verify(densityEdge.accepted && densityEdge.corePositiveFrames == 100 &&
            densityEdge.coreSpanFrames == 400 && densityEdge.coreDensity == .25,
            "Density of exactly one quarter must be accepted")
        val sparse = GuardianTemporalSpeechCore.evaluate(sequence(25, -101, 25, -100, 25, -100, 25))
        verify(!sparse.accepted && sparse.corePositiveFrames == 100 && sparse.coreSpanFrames == 401,
            "Meeting the count threshold cannot override insufficient temporal density")
    }

    private fun discardedFlickerCannotBridgeGroups() {
        val result = GuardianTemporalSpeechCore.evaluate(sequence(15, -60, 14, -60, 85))
        verify(!result.accepted && result.positiveFrames == 114 && result.corePositiveFrames == 85,
            "An excluded 14-frame island cannot bridge two retained islands separated by 134 frames")
    }

    private fun qualifyingCoreWinsOverLargerSparseGroup() {
        val sparse = mutableListOf<Int>()
        repeat(8) { if (it > 0) sparse += -120; sparse += 15 }
        sparse += -121
        sparse += 100
        val result = GuardianTemporalSpeechCore.evaluate(sequence(*sparse.toIntArray()))
        verify(result.accepted && result.positiveFrames == 220 && result.corePositiveFrames == 100 &&
            result.coreSpanFrames == 100 && result.coreDensity == 1.0,
            "A valid coherent group must win over a larger sparse rejected group")
    }

    private fun endingIslandAndSampleCoordinatesArePreserved() {
        val original = sequence(-23, 50, -10, 50, -37)
        val copy = original.copyOf()
        val result = GuardianTemporalSpeechCore.evaluate(original)
        verify(result.accepted && result.coreStartFrame == 23 && result.coreEndFrameExclusive == 133 &&
            result.coreSpanFrames == 110 && result.corePositiveFrames == 100,
            "Core coordinates must use the exact input posterior frame clock, excluding surrounding silence")
        verify(original.contentEquals(copy), "Temporal confirmation must not mutate its posterior")
        val atEnd = GuardianTemporalSpeechCore.evaluate(sequence(-23, 100))
        verify(atEnd.accepted && atEnd.coreStartFrame == 23 && atEnd.coreEndFrameExclusive == 123,
            "A qualifying island ending at the final frame must not be lost")
        val earlier = GuardianTemporalSpeechCore.evaluate(sequence(100, -121, 99))
        verify(earlier.accepted && earlier.coreStartFrame == 0 && earlier.coreEndFrameExclusive == 100,
            "A later subthreshold group must not overwrite a prior qualifying core")
    }

    private fun posteriorBoundaryMatchesFsmnFloatArithmetic() {
        val atBoundary = GuardianTemporalSpeechCore.evaluate(FloatArray(100) { .2f })
        verify(atBoundary.accepted && atBoundary.positiveFrames == 100,
            "The Float32 .2 posterior boundary must match the existing FSMN rule")
        val above = GuardianTemporalSpeechCore.evaluate(FloatArray(100) { java.lang.Math.nextUp(.2f) })
        verify(!above.accepted && above.positiveFrames == 0,
            "One Float32 step above .2 must not become speech through threshold rounding")
    }

    private fun invalidPosteriorFailsClosed() {
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -.01f, 1.01f)) {
            val input = FloatArray(100) { 0f }.also { it[50] = invalid }
            verify(runCatching { GuardianTemporalSpeechCore.evaluate(input) }.exceptionOrNull() is IllegalArgumentException,
                "A malformed posterior must not rescue a window: $invalid")
        }
    }

    private fun everyRescuePrerequisiteIsMandatory() {
        for (mask in 0 until 128) {
            fun bit(n: Int) = mask and (1 shl n) != 0
            val eligible = GuardianTemporalRescuePolicy.eligible(bit(0), bit(1), bit(2), bit(3), bit(4), bit(5), bit(6))
            verify(eligible == (mask == 60),
                "Rescue eligibility must require a clean/coherent LONG, positive FireRed and completed FSMN refusal; mask=$mask")
        }
        for (mask in 0 until 16) {
            fun bit(n: Int) = mask and (1 shl n) != 0
            verify(GuardianTemporalRescuePolicy.admit(bit(0), bit(1), bit(2), bit(3)) == (mask == 15),
                "No GTCRN model decision or temporal core alone may admit the rescue; mask=$mask")
        }
    }
}
