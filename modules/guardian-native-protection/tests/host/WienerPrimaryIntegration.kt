package expo.modules.guardiannativeprotection

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt
import kotlin.system.exitProcess

/** Opt-in host replay with production segmentation, real FireRed JNI, and fake ASR.
 * The manifest and generated audio belong outside the source checkout. No input
 * paths, identities, audio, or recognition text are printed by this runner.
 *
 * Manifest (one case per line, no header):
 * input.f32<TAB>1=accepted.f32,2=accepted.f32<TAB>3=rejected.f32
 * Segment IDs are explicit; the rejected column may be empty.
 */
object WienerPrimaryIntegration {
    private data class Replay(val input: File, val accepted: Map<Long, FloatArray>, val rejected: Map<Long, FloatArray>)
    private data class Event(val raw: String) {
        fun value(key: String): String? = Regex("\"${Regex.escape(key)}\":(\"([^\"]*)\"|[^,}]+)")
            .find(raw)?.let { if (it.groupValues[2].isNotEmpty()) it.groupValues[2] else it.groupValues[1].trim() }
        val name get() = value("event")
        fun long(key: String) = checkNotNull(value(key)).toLong()
        fun flag(key: String) = value(key) == "true"
    }

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2) { "WienerPrimaryIntegration manifest.tsv artifact-directory" }
        val artifacts = File(args[1])
        require(artifacts.isDirectory || artifacts.mkdirs()) { "Cannot create artifact directory" }
        try {
            val cases = File(args[0]).readLines().filter { it.isNotBlank() && !it.startsWith('#') }.map { line ->
                val fields = line.split('\t')
                require(fields.size in 2..3) { "Manifest needs two or three tab-separated columns" }
                fun expected(column: String): Map<Long, FloatArray> = if (column.isBlank()) emptyMap() else
                    column.split(',').associate { item ->
                        val parts = item.split('=', limit = 2)
                        require(parts.size == 2) { "Expected segmentId=path in manifest" }
                        parts[0].toLong() to readFloats(File(parts[1]))
                    }
                Replay(File(fields[0]), expected(fields[1]), expected(fields.getOrElse(2) { "" })).also {
                    require(it.accepted.isNotEmpty() && (it.accepted.keys intersect it.rejected.keys).isEmpty())
                }
            }
            require(cases.isNotEmpty()) { "Empty manifest" }
            cases.forEachIndexed { index, replay ->
                val input = readFloats(replay.input)
                require(input.size in 1..(16000 * 180)) { "Replay must fit the production study limit" }
                runReplay(replay, input, File(artifacts, "case-${index + 1}-continuous"), invalidate = false)
                runReplay(replay, input, File(artifacts, "case-${index + 1}-discontinuity"), invalidate = true)
            }
            println("PASS Wiener production primary integration: ${cases.size} replay(s), real FireRed JNI, exact primary PCM, shadow-only decoding, aligned WAV pairs, discontinuity isolation")
        } catch (error: Throwable) {
            File(artifacts, "failure-private.txt").writeText(error.stackTraceToString())
            System.err.println("FAIL Wiener primary integration (${error.javaClass.simpleName}); details are in the private artifact directory")
            exitProcess(1)
        }
    }

    private fun runReplay(replay: Replay, input: FloatArray, directory: File, invalidate: Boolean) {
        require(!directory.exists()) { "Use a fresh artifact directory" }
        check(directory.mkdirs())
        val records = Collections.synchronizedList(mutableListOf<Event>())
        fun events(name: String): List<Event> = synchronized(records) { records.filter { it.name == name } }
        val diagnostics = GuardianVoiceDiagnostics(true, "HOST", { records.add(Event(it)) }, "host-integration")
        val lastDecode = AtomicReference<FloatArray>()
        val decoded = Collections.synchronizedList(mutableListOf<FloatArray>())
        val qwen = GuardianQwenRescueRecognizer { pcm ->
            pcm.copyOf().also { lastDecode.set(it); decoded.add(it) }
            GuardianQwenRescueRecognizer.Result("integration fixture", audioDurationMs = pcm.size * 1000L / 16000)
        }
        val finals = Collections.synchronizedList(mutableListOf<Pair<Long, FloatArray>>())
        val partials = AtomicInteger()
        val unavailable = AtomicInteger()
        val backpressure = AtomicInteger()
        val warnings = AtomicInteger()
        val decoder = GuardianQwenProgressiveDecoder(qwen, object : GuardianQwenProgressiveDecoder.Listener {
            override fun onPartial(hypothesis: GuardianQwenProgressiveDecoder.Hypothesis) { partials.incrementAndGet() }
            override fun onFinal(hypothesis: GuardianQwenProgressiveDecoder.Hypothesis) {
                finals.add(hypothesis.segmentId to checkNotNull(lastDecode.get()).copyOf())
            }
            override fun onDecoderUnavailable(segmentId: Long, isFinal: Boolean) { unavailable.incrementAndGet() }
            override fun onBackpressure(segmentId: Long, pendingFinalCount: Int) { backpressure.incrementAndGet() }
        }, diagnostics = diagnostics)
        val primary = GuardianQwenPrimaryAudioProcessor(Context(), decoder,
            object : GuardianQwenPrimaryAudioProcessor.Listener {
                override fun onSpeechStarted() {}
                override fun onSpeechEnded() {}
                override fun onWarning(message: String) { warnings.incrementAndGet() }
            }, diagnostics = diagnostics, diagnosticWavDirectory = directory,
            speechOnlyGain = true, confirmedGainMaximum = 1f,
            continuousStudy = true, studyModel = "WIENER_DD_ADAPTIVE")
        val worker = field(primary, "worker") as ExecutorService
        val study = checkNotNull(field(primary, "study"))
        val queue = field(study, "queue") as java.util.concurrent.BlockingQueue<*>
        val nativeWorkerClosed = field(primary, "worker") as ExecutorService
        var sequence = 0L
        val capturedBase = System.nanoTime()
        fun send(pcm: FloatArray, skipSequence: Boolean = false) {
            sequence += if (skipSequence) 2 else 1
            val original = pcm.copyOf()
            val selected = pcm.copyOf()
            // A deliberately different post-gain lane proves speechOnlyGain
            // selects the original detector lane, including for native VAD.
            val postGain = FloatArray(pcm.size) { .125f }
            val raw = pcm.copyOf()
            val timestamp = capturedBase + sequence * 50_000_000L
            primary.onAudioFrame(GuardianAudioEngine.AudioFrame(
                samples = postGain, sampleCount = pcm.size, sampleRateHz = 16000,
                capturedAtElapsedRealtimeNanos = timestamp, sequenceNumber = sequence,
                enhancedSamples = FloatArray(0), enhancedSampleCount = 0,
                enhancedCapturedAtElapsedRealtimeNanos = timestamp, enhancedSequenceNumber = sequence,
                enhancementApplied = false, enhancementSource = "NONE",
                rawSamples = raw, rawSampleCount = raw.size,
                detectorSamples = selected, detectorSampleCount = selected.size,
                detectorCapturedAtElapsedRealtimeNanos = timestamp, detectorSequenceNumber = sequence,
                acousticRms = GuardianVoiceDiagnostics.rms(pcm), acousticNoiseFloorRms = .00001f,
                snrDb = 0f, speechLevel = "UNKNOWN", farVoiceEventId = 0,
                farVoiceSource = GuardianAudioEngine.FarVoiceSource.NONE,
                farVoicePhase = GuardianAudioEngine.FarVoicePhase.NONE,
                adaptiveGain = 4f, enhancementExpected = true, captureChannelRevision = 0))
            check(pcm.contentEquals(original) && selected.contentEquals(original)) { "Capture PCM was modified" }
            // AudioRecord reuses buffers immediately. Production must have copied
            // every array before returning to this simulated capture callback.
            selected.fill(Float.NaN); raw.fill(Float.NaN); postGain.fill(Float.NaN)
            worker.submit {}.get(30, TimeUnit.SECONDS)
            await("Study worker backlog") { queue.size <= 8 }
        }
        try {
            await("Wiener study readiness") { events("CONTINUOUS_STUDY_READY").isNotEmpty() }
            val ready = events("CONTINUOUS_STUDY_READY").single()
            check(ready.value("model") == "WIENER_DD_ADAPTIVE" && ready.long("calibrationSamples") == 48000L)
            check(ready.value("alignment") == "WIENER_FINALIZED_SAMPLE_CLOCK")
            check(events("FIRERED_READY").single().value("model") == GuardianFireRedConfirmation.MODEL_ID)
            if (invalidate) {
                repeat(4) { send(FloatArray(800)) }
                send(input.copyOfRange(0, minOf(800, input.size)), skipSequence = true)
                await("Discontinuity invalidates the shadow worker") { events("CONTINUOUS_STUDY_END").isNotEmpty() }
                check(events("CONTINUOUS_STUDY_INVALID").single().value("reason") == "PRIMARY_DISCONTINUITY")
                // The frame above is the first frame of a fresh primary sample
                // clock. Continue the SAME input, proving primary ASR survives.
                for (position in 800 until input.size step 800) send(input.copyOfRange(position, minOf(position + 800, input.size)))
            } else {
                for (position in input.indices step 800) send(input.copyOfRange(position, minOf(position + 800, input.size)))
                await("All native windows receive aligned study pairs") {
                    events("CONTINUOUS_STUDY_WINDOW").size == replay.accepted.size + replay.rejected.size
                }
                // The window record precedes its diagnostic offer. Wait for
                // that handoff before placing a decoder executor barrier.
                await("Every eligible shadow window reaches the decoder handoff") {
                    events("CONTINUOUS_STUDY_ASR_OFFER").size == events("CONTINUOUS_STUDY_WINDOW")
                        .count { it.flag("enhancedAccepted") && !it.flag("startupBypass") }
                }
            }
            (field(decoder, "worker") as ExecutorService).submit {}.get(30, TimeUnit.SECONDS)
            val observedFinals = synchronized(finals) { finals.toList() }
            check(observedFinals.map { it.first }.toSet() == replay.accepted.keys && observedFinals.size == replay.accepted.size) {
                "Primary callback IDs or count changed"
            }
            for ((id, pcm) in observedFinals) check(pcm.contentEquals(replay.accepted.getValue(id))) { "Primary ASR PCM changed" }
            check(partials.get() == 0 && unavailable.get() == 0 && backpressure.get() == 0) { "Unexpected public decoder callback" }
            val gates = events("FIRERED_RESULT").associateBy { it.long("segment") }
            check(gates.keys == replay.accepted.keys + replay.rejected.keys) { "Native window set changed" }
            for (id in replay.accepted.keys) check(gates.getValue(id).flag("accepted")) { "Expected primary admission changed" }
            for (id in replay.rejected.keys) check(!gates.getValue(id).flag("accepted")) { "Rejected native window entered primary ASR" }
            check(events("AUDIO_FRAME").any { it.long("streamVadFrames") > 0 }) { "No native streaming inference observed" }
            check(events("SEGMENT_GAIN").all { it.value("gain") == "1.0" }) { "Unit gain contract changed" }
            check(events("QWEN_START").size == replay.accepted.size && events("QWEN_START").all { it.flag("final") })
            if (invalidate) {
                check(events("PIPELINE_RESET").size == 1)
                check(events("CONTINUOUS_STUDY_WINDOW").isEmpty() && events("STUDY_QWEN_RESULT").isEmpty()) {
                    "Invalid study published a window or diagnostic decode"
                }
                check(directory.walkTopDown().none { it.isDirectory && it.name.startsWith("segment-") })
            } else {
                check(warnings.get() == 0 && events("CONTINUOUS_STUDY_INVALID").isEmpty()) { "Replay was discontinuous or overloaded" }
                val offers = events("CONTINUOUS_STUDY_ASR_OFFER").count { it.flag("accepted") }
                check(offers > 0 && events("STUDY_QWEN_RESULT").size == offers) { "Shadow ASR was not exercised" }
                check(events("STUDY_QWEN_RESULT").all { it.flag("eligible") && it.flag("success") && it.value("lane") == "WIENER_DD_ADAPTIVE" })
                check(decoded.size == observedFinals.size + offers) { "Shadow decoding reached product callbacks" }
            }
        } finally {
            primary.close()
            check(qwen.released.await(30, TimeUnit.SECONDS)) { "Qwen was not released" }
            check(nativeWorkerClosed.awaitTermination(30, TimeUnit.SECONDS)) { "Native primary worker did not release" }
            check(field(checkNotNull(field(primary, "streamingVad")), "handle") == 0L &&
                field(checkNotNull(field(primary, "fireRed")), "handle") == 0L) { "Native primary handles remain open" }
            await("Study release") { events("CONTINUOUS_STUDY_END").isNotEmpty() }
            synchronized(records) { File(directory, "events-private.jsonl").writeText(records.joinToString("\n") { it.raw } + "\n") }
        }
        val end = events("CONTINUOUS_STUDY_END").single()
        check(end.flag("valid") != invalidate && end.long("incompleteWindows") == 0L)
        check(events("SESSION_END").single().long("droppedInputTasks") == 0L)
        if (!invalidate) {
            check(end.long("inputSamples") == input.size.toLong() && end.long("pairedWindows") == (replay.accepted.size + replay.rejected.size).toLong())
            check(end.long("outputSamples") <= input.size && input.size - end.long("outputSamples") < 800)
            verifyPairs(directory, input, replay.accepted + replay.rejected, events("CONTINUOUS_STUDY_WINDOW"))
            check(readFloats(stageFile(directory, "truth", "f32")).contentEquals(input)) { "Primary truth export changed" }
            check(readFloats(stageFile(directory, "asr", "f32")).contentEquals(input)) { "Primary ASR export changed" }
        } else check(end.long("pairedWindows") == 0L)
        println("PASS ${if (invalidate) "discontinuity" else "continuous"}: ${replay.accepted.size} exact primary finals, ${replay.rejected.size} native rejections, ${end.long("pairedWindows")} study pairs")
    }

    private fun verifyPairs(directory: File, input: FloatArray, expected: Map<Long, FloatArray>, windows: List<Event>) {
        val session = File(directory, "continuous-study").listFiles()!!.single { it.isDirectory }
        val continuous = readFloats(stageFile(session, "asr", "f32"))
        check(readFloats(stageFile(session, "truth", "f32")).contentEquals(input)) { "Study input differs from primary input" }
        var filtered = 0
        for (window in windows) {
            val id = window.long("segment")
            val start = window.long("startSample").toInt()
            val end = window.long("endSample").toInt()
            check(start >= 0 && end <= input.size && end - start == window.long("samples").toInt())
            val pair = File(session, "segment-$id")
            val truth = readFloats(stageFile(pair, "truth", "f32"))
            val clean = readFloats(stageFile(pair, "asr", "f32"))
            check(truth.contentEquals(input.copyOfRange(start, end)) && truth.contentEquals(expected.getValue(id))) {
                "Paired original is not the exact primary interval"
            }
            check(clean.size == truth.size && clean.all { it.isFinite() })
            if (window.flag("startupBypass")) check(clean.contentEquals(truth)) { "Startup fallback is not entire original PCM" }
            else {
                filtered++
                check(window.flag("calibrationReady") && window.flag("calibrationValid") && window.value("bypassReason") == "FILTERED")
                check(window.value("enhancedPeakProbability") != "null") { "Filtered window skipped native confirmation" }
                check(clean.contentEquals(continuous.copyOfRange(start, end))) { "Filtered pair used a different sample interval" }
                check(!clean.contentEquals(truth)) { "Calibrated window silently bypassed Wiener" }
            }
            verifyWav(stageFile(pair, "truth", "wav"), truth)
            verifyWav(stageFile(pair, "asr", "wav"), clean)
        }
        check(filtered > 0) { "No calibrated pair was verified" }
    }

    private fun verifyWav(file: File, pcm: FloatArray) {
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        check(bytes.size == 44 + pcm.size * 2 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE")
        check(buffer.getInt(4) == bytes.size - 8 && buffer.getShort(20).toInt() == 1 && buffer.getShort(22).toInt() == 1)
        check(buffer.getInt(24) == 16000 && buffer.getShort(34).toInt() == 16 && buffer.getInt(40) == pcm.size * 2)
        for (index in pcm.indices) check(buffer.getShort(44 + index * 2).toInt() ==
            (pcm[index].coerceIn(-1f, 1f) * 32768f).roundToInt().coerceIn(-32768, 32767)) { "WAV differs from same-interval float sidecar" }
    }

    private fun stageFile(directory: File, stage: String, extension: String): File =
        directory.listFiles()!!.single { it.isFile && it.name.endsWith("-$stage.$extension") }
    private fun readFloats(file: File): FloatArray {
        val bytes = file.readBytes()
        require(bytes.size % 4 == 0) { "Invalid float input size" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(buffer.remaining()).also { buffer.get(it); require(it.all { value -> value.isFinite() }) }
    }
    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).let { it.isAccessible = true; it.get(owner) }
    private fun await(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Timed out: $description" }
            Thread.sleep(2)
        }
    }
}
