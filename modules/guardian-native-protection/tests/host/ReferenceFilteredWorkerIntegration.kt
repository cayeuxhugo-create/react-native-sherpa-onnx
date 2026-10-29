package expo.modules.guardiannativeprotection

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.concurrent.BlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real native VAD replay, opt-in and offline. No primary window() is submitted.
 * Private TSV: case-id, primary float32 path, reference float32 path,
 * optional minimum independent filtered candidates (default 1),
 * optional EXPECT_CLOSE_FALLBACK flag for a capture ending before real context.
 * Corpus paths, audio and event logs remain in the private artifact directory.
 */
object ReferenceFilteredWorkerIntegration {
    private const val FILTERED_SOURCE = "FILTERED_STREAM_LONG"
    private const val STUDY_ID_BASE = 1_000_000_000L
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 2)
        val output = File(args[1]); check(output.isDirectory || output.mkdirs())
        var cases = 0
        for (line in File(args[0]).readLines().filter { it.isNotBlank() && !it.startsWith('#') }) {
            val columns = line.split('\t'); require(columns.size in 3..5)
            val primary = read(File(columns[1])); val reference = read(File(columns[2]))
            require(primary.size == reference.size && primary.size > 16 * 16000)
            val minimum = columns.getOrNull(3)?.toInt() ?: 1
            require(minimum > 0)
            val expectCloseFallback = columns.getOrNull(4)?.let {
                require(it == "EXPECT_CLOSE_FALLBACK"); true
            } ?: false
            replay(primary, reference, File(output, "case-$cases"), minimum, expectCloseFallback)
            cases++
        }
        require(cases > 0)
        println("PASS independent LONG worker: $cases native-VAD replays, no primary windows or product callbacks")
    }

    private fun replay(primary: FloatArray, reference: FloatArray, output: File, minimum: Int, expectCloseFallback: Boolean) {
        check(!output.exists() && output.mkdirs())
        val events = Collections.synchronizedList(mutableListOf<String>())
        val callbacks = AtomicInteger(); val decodes = AtomicInteger()
        val diagnostics = GuardianVoiceDiagnostics(true, "HOST", { events.add(it) }, "independent-filtered-worker")
        val recognizer = GuardianQwenRescueRecognizer { pcm ->
            check(pcm.isNotEmpty() && pcm.size <= 128000 && pcm.all { it.isFinite() })
            decodes.incrementAndGet()
            GuardianQwenRescueRecognizer.Result("synthetic host decoder result", audioDurationMs = pcm.size / 16L)
        }
        val decoder = GuardianQwenProgressiveDecoder(recognizer, object : GuardianQwenProgressiveDecoder.Listener {
            override fun onPartial(h: GuardianQwenProgressiveDecoder.Hypothesis) { callbacks.incrementAndGet() }
            override fun onFinal(h: GuardianQwenProgressiveDecoder.Hypothesis) { callbacks.incrementAndGet() }
            override fun onDecoderUnavailable(segmentId: Long, isFinal: Boolean) { callbacks.incrementAndGet() }
            override fun onBackpressure(segmentId: Long, pendingFinalCount: Int) { callbacks.incrementAndGet() }
        }, diagnostics = diagnostics)
        val study = GuardianReferenceNoiseStudy(Context(), output, diagnostics, decoder)
        val worker = field(study, "thread") as Thread
        val queue = field(study, "queue") as BlockingQueue<*>
        try {
            var start = 0; var sequence = 1L
            while (start < primary.size) {
                val end = minOf(primary.size, start + 800)
                val p = primary.copyOfRange(start, end); val r = reference.copyOfRange(start, end)
                val capture = GuardianCaptureChannelFrame(1, 2, 0, listOf(
                    GuardianCaptureChannelFrame.Channel(0, p.copyOf(), true),
                    GuardianCaptureChannelFrame.Channel(1, r.copyOf(), true)))
                // Deliberately supply no primary VAD speech or window. The
                // filtered worker must discover its own candidates from LONG.
                study.frame(p, sequence, sequence * 50_000_000, false, 1, capture)
                p.fill(Float.NaN); r.fill(Float.NaN)
                capture.channels.forEach { it.samples.fill(Float.NaN) }
                await { queue.size < 4 }
                start = end; sequence++
            }
        } finally {
            study.close(); worker.join(120_000); check(!worker.isAlive) { "Filtered study worker did not finish" }
            (field(decoder, "worker") as ExecutorService).submit {}.get(60, TimeUnit.SECONDS)
            decoder.close(); check(recognizer.released.await(60, TimeUnit.SECONDS))
        }
        val snapshot = synchronized(events) { events.toList() }
        File(output, "events-private.jsonl").writeText(snapshot.joinToString("\n") + "\n")
        check(callbacks.get() == 0 && snapshot.none { value(it, "event") == "QWEN_PUBLISH" }) {
            "A shadow-only replay reached product callbacks"
        }
        val ready = snapshot.single { value(it, "event") == "REFERENCE_STUDY_READY" }
        check(value(ready, "windowSources") == "PRIMARY_ORIGINAL_AND_FILTERED_STREAM_LONG")
        check(value(ready, "fixedVadBlockSamples") == "800")
        val end = snapshot.single { value(it, "event") == "REFERENCE_STUDY_END" }
        check(value(end, "valid") == "true" && value(end, "pairEverAvailable") == "true")
        val windows = snapshot.filter { value(it, "event") == "REFERENCE_STUDY_WINDOW" }
        check(windows.isNotEmpty() && windows.all { value(it, "windowSource") == FILTERED_SOURCE }) {
            "Independent windows are missing or were attributed to a primary candidate"
        }
        check(windows.all { value(it, "role") == "SHADOW_NO_ALERT" && value(it, "productionEligible") == "false" &&
                value(it, "diagnosticForced") == "true" && value(it, "primaryOriginalAccepted") == "null" })
        val candidates = windows.groupBy { checkNotNull(value(it, "segment")).toLong() }
        check(candidates.size >= minimum && candidates.keys.all { it >= STUDY_ID_BASE }) {
            "Expected at least $minimum independent candidates; found ${candidates.size}"
        }
        var queued = 0
        val queuedByCandidate = mutableMapOf<Long, Int>()
        for ((_, variants) in candidates) {
            check(variants.count { value(it, "variant") == "EXACT" } == 1)
            check(variants.count { value(it, "variant") == "END_PLUS_2S" } == 1)
            val exact = variants.single { value(it, "variant") == "EXACT" }
            val context = variants.single { value(it, "variant") == "END_PLUS_2S" }
            check(value(exact, "startSample") == value(context, "startSample"))
            val exactStart=checkNotNull(value(exact,"startSample")).toLong()
            val exactEnd=checkNotNull(value(exact,"endSample")).toLong()
            check(checkNotNull(value(context,"endSample")).toLong()==
                (GuardianAsrWindowPolicy.contextEnd(exactStart,exactEnd) ?: (exactEnd+32000L)))
            if (value(context, "available") == "true" && value(exact, "status") == "FILTERED" &&
                value(exact,"filteredFireRedAccepted")=="true") {
                check(value(exact, "diagnosticOfferStatus") == "CONTEXT_PREFERRED") {
                    "The exact window was decoded despite its real context being available"
                }
            }
            for (window in variants) {
                if (value(window, "available") == "true") {
                    check(checkNotNull(value(window, "samples")).toInt() in 1..128000)
                    if (value(window, "status") == "FILTERED") {
                        check(value(window, "filteredFireRedAccepted") in listOf("true", "false"))
                        check(value(window, "filteredFsmnAccepted") in listOf("true", "false"))
                    }
                }
                if (value(window, "diagnosticOfferStatus") == "QUEUED") {
                    queued++
                    val id = checkNotNull(value(window, "segment")).toLong()
                    queuedByCandidate[id] = (queuedByCandidate[id] ?: 0) + 1
                    check(value(window, "filteredFireRedAccepted") == "true")
                    // FSMN is measured here; a diagnostic may be queued when it
                    // is false. This branch must never grant product eligibility.
                    check(value(window, "filteredFsmnAccepted") in listOf("true", "false"))
                    if (value(window, "variant") == "EXACT") {
                        check(value(context, "status") == "CONTEXT_TOO_LONG_OR_INVALID")
                    }
                }
            }
        }
        val fallbacks = snapshot.filter { value(it, "event") == "REFERENCE_FILTERED_FALLBACK" }
        var closeFallbacks = 0; var fallbackQueued = 0
        for (fallback in fallbacks) {
            val id = checkNotNull(value(fallback, "segment")).toLong()
            val variants = checkNotNull(candidates[id])
            val exact = variants.single { value(it, "variant") == "EXACT" }
            val context = variants.single { value(it, "variant") == "END_PLUS_2S" }
            check(value(fallback, "windowSource") == FILTERED_SOURCE && value(fallback, "variant") == "EXACT")
            check(value(fallback, "role") == "SHADOW_NO_ALERT" && value(fallback, "productionEligible") == "false" &&
                    value(fallback, "diagnosticForced") == "true")
            check(value(fallback, "diagnosticLane") == "REFERENCE_NOISE_LONG_EXACT")
            check(value(fallback, "startSample") == value(exact, "startSample") &&
                    value(fallback, "endSample") == value(exact, "endSample"))
            if (value(fallback, "diagnosticOfferStatus") == "QUEUED") {
                queued++; fallbackQueued++
                queuedByCandidate[id] = (queuedByCandidate[id] ?: 0) + 1
                check(value(fallback, "available") == "true" && value(fallback, "filteredFireRedAccepted") == "true")
                check(value(fallback, "filteredFsmnAccepted") in listOf("true", "false"))
                check(value(exact, "available") == "true" && value(exact, "diagnosticOfferStatus") == "CONTEXT_PREFERRED")
                if (value(context, "available") == "false" && value(context, "status") == "END_CONTEXT_UNAVAILABLE") {
                    check(value(fallback, "reason") == "END_CONTEXT_UNAVAILABLE")
                    check(checkNotNull(value(context, "endSample")).toLong() > primary.size)
                    closeFallbacks++
                }
            }
        }
        if (expectCloseFallback) check(closeFallbacks > 0) {
            "Closing before the real context was complete did not queue its deferred exact window"
        }
        check(queuedByCandidate.values.all { it == 1 }) { "A filtered candidate queued both exact and context" }
        check(queued <= 16) { "Independent diagnostic ASR budget exceeded" }
        val registeredGroups = assertExports(File(output, "reference-study/${diagnostics.sessionId}"))
        File(output, "summary.txt").writeText("independentCandidates=${candidates.size};registeredGroups=$registeredGroups;queued=$queued;fallbackQueued=$fallbackQueued;closeFallbacks=$closeFallbacks;decodes=${decodes.get()};productCallbacks=0;sourcePcmByteExact=true\n")
        println("PASS independent filtered candidates=${candidates.size}, registeredGroups=$registeredGroups, queued=$queued, fallbackQueued=$fallbackQueued, closeFallbacks=$closeFallbacks, productCallbacks=0")
    }

    private fun assertExports(directory: File): Int {
        val rows = csv(File(directory, "reference-files.csv"))
        check(rows.map { it["path"] }.toSet().size == rows.size) { "A diagnostic fallback duplicated a CSV export" }
        val full = rows.filter { it["kind"] == "full" && checkNotNull(it["path"]).endsWith(".f32") }
            .associateBy { it["epoch"] + ":" + it["lane"] }
        val sourceBytes = full.mapValues { File(directory, checkNotNull(it.value["path"])).readBytes() }
        check(full.isNotEmpty() && full.keys.map { it.substringAfter(':') }.toSet() == setOf("primary", "reference", "short", "long"))
        for (row in rows) check(File(directory, checkNotNull(row["path"])).isFile)
        val pairs = rows.filter { it["kind"] == "pair" && checkNotNull(it["path"]).endsWith(".f32") }
        for (row in pairs) {
            val key = row["epoch"] + ":" + row["lane"]
            val source = checkNotNull(full[key]); val bytes = checkNotNull(sourceBytes[key])
            val start = (checkNotNull(row["globalStart"]).toLong() - checkNotNull(source["globalStart"]).toLong()) * 4
            val count = checkNotNull(row["samples"]).toInt(); check(count in 1..128000)
            val pair = File(directory, checkNotNull(row["path"])).readBytes()
            check(start >= 0 && start + pair.size <= bytes.size && pair.size == count * 4)
            check(pair.contentEquals(bytes.copyOfRange(start.toInt(), start.toInt() + pair.size))) {
                "A paired window differs from its real continuous source interval"
            }
        }
        val groups = pairs.groupBy { it["epoch"] + ":" + it["segment"] + ":" + it["variant"] }
        for (group in groups.values) {
            check(group.size == 4 && group.map { it["lane"] }.toSet().size == 4)
            check(group.map { it["globalStart"] + ":" + it["samples"] }.toSet().size == 1)
        }
        check(groups.isNotEmpty())
        return groups.size
    }
    private fun csv(file: File): List<Map<String, String>> {
        val lines = file.readLines(); val header = lines.first().split(',')
        return lines.drop(1).filter { it.isNotBlank() }.map { header.zip(it.split(',')).toMap() }
    }
    private fun read(file: File): FloatArray {
        val bytes = file.readBytes(); require(bytes.size % 4 == 0)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(buffer.remaining()).also { buffer.get(it); require(it.all { sample -> sample.isFinite() }) }
    }
    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).let { it.isAccessible = true; it.get(owner) }
    private fun value(raw: String, key: String): String? = Regex("\"${Regex.escape(key)}\":(\"([^\"]*)\"|[^,}]+)").find(raw)?.let {
        if (it.groupValues[2].isNotEmpty()) it.groupValues[2] else it.groupValues[1].trim()
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
        while (!condition()) { check(System.nanoTime() < deadline) { "Filtered worker queue timeout" }; Thread.sleep(2) }
    }
}
