package expo.modules.guardiannativeprotection

import android.os.SystemClock
import android.util.Log
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ordonnanceur de décodage Qwen3 offline progressif.
 *
 * Une seule instance GuardianQwenRescueRecognizer, un seul worker natif.
 * Les PARTIAL sont remplaçables : si Qwen est en retard, il ne faut pas
 * traiter en rafale des fenêtres devenues périmées.
 * Les FINAL, en revanche, sont conservés dans une petite file bornée.
 *
 * Ne capture PAS le microphone : la segmentation et le PCM appartiennent
 * à GuardianAudioEngine/au service. Le service devra lui remettre des
 * fenêtres complètes commençant au début du segment technique courant.
 *
 * Ce composant ne prétend PAS fournir le streaming natif de Qwen : il
 * simule des hypothèses progressives par décodages offline successifs.
 */
class GuardianQwenProgressiveDecoder(
    private val qwen: GuardianQwenRescueRecognizer,
    private val listener: Listener,
    private val configuration: Configuration = Configuration(),
    private val diagnostics: GuardianVoiceDiagnostics? = null,
) {
    data class Configuration(
        val sampleRate: Int = 16_000,
        val minPartialWindowMs: Long = 700L,
        val minFinalWindowMs: Long = 150L,
        val maxWindowMs: Long = 8_000L,
        val maxPendingFinals: Int = 3,
        val maxPartialWindowMs: Long = minOf(3_000L, maxWindowMs),
    ) {
        init {
            require(sampleRate == 16_000)
            require(minPartialWindowMs in 250L..2_000L)
            require(minFinalWindowMs in 100L..minPartialWindowMs)
            require(maxWindowMs in 2_000L..15_000L)
            require(maxPendingFinals in 1..8)
            require(maxPartialWindowMs in minPartialWindowMs..maxWindowMs)
        }
    }

    enum class AcousticEvidence {
        CLEAR_CONFIRMED, FAR_CONFIRMED, CANDIDATE_ONLY, NEURAL_CONFIRMED
    }

    internal enum class DiagnosticOfferStatus {
        QUEUED, DUPLICATE_PCM, QUEUE_FULL, CLOSED, EMPTY_PCM, INVALID_LENGTH, INVALID_PCM, INVALID_SEGMENT, INVALID_LANE
    }

    /** Physical-channel fairness, independent of candidate IDs or unsuccessful offers. */
    internal class DiagnosticChannelOrder {
        private var lastAcceptedChannel: Int? = null

        fun ordered(channels: List<Int>): List<Int> {
            require(channels.size <= 30 && channels.all { it in 0..29 })
            val sorted = channels.distinct().sorted()
            val last = lastAcceptedChannel ?: return sorted
            val start = sorted.indexOfFirst { it > last }.takeIf { it >= 0 } ?: 0
            return sorted.drop(start) + sorted.take(start)
        }

        fun accepted(channel: Int) {
            require(channel in 0..29)
            lastAcceptedChannel = channel
        }
    }

    data class Hypothesis(
        val segmentId: Long,
        val revision: Long,
        val text: String,
        val language: String,
        val isFinal: Boolean,
        val boundaryReason: String?,
        val observedAtElapsedRealtimeMs: Long,
        val audioDurationMs: Long,
        val decodeElapsedMs: Long,
        val acousticEvidence: AcousticEvidence,
    )

    interface Listener {
        fun onPartial(hypothesis: Hypothesis)
        fun onFinal(hypothesis: Hypothesis)
        fun onDecoderUnavailable(segmentId: Long, isFinal: Boolean)
        fun onBackpressure(segmentId: Long, pendingFinalCount: Int)
    }

    private data class Job(
        val segmentId: Long,
        val revision: Long,
        val samples: FloatArray,
        val isFinal: Boolean,
        val boundaryReason: String? = null,
        val acousticEvidence: AcousticEvidence,
        val generation: Long,
        val queuedAtMs: Long = SystemClock.elapsedRealtime(),
        val diagnosticLane: String? = null,
        val diagnosticFingerprint: String? = null,
    )

    private val lock = Any()
    // Bounded, generation-local identities. Text equality is never audio equality.
    private val diagnosticInputs = LinkedHashMap<String, Pair<Long, String>>()
    private val closed = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor { job ->
        Thread(job, "Guardian-Qwen-Primary").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
    }
    private val pendingFinals = ArrayDeque<Job>()
    private var pendingPartial: Job? = null
    private val pendingDiagnostics = ArrayDeque<Job>()
    private var generation = 0L
    private var pumping = false
    private var currentRevision = 0L
    private var activeSegmentId: Long? = null
    private var lastAcceptedFinalSegmentId = 0L
    private var lastPartialText: String? = null
    private var lastPartialSegmentId: Long? = null

    /**
     * Une fenêtre issue du MEME début acoustique de segment. Aucune copie de
     * travail n'est créée sur le thread de capture : l'appelant transfère la
     * propriété du FloatArray et ne doit plus le modifier.
     *
     * Retourne false si la fenêtre est rejetée (p.ex. moteur arrêté).
     */
    fun offerPartial(
        segmentId: Long, ownedPcm: FloatArray, acousticEvidence: AcousticEvidence,
    ): Boolean {
        if (closed.get() || segmentId <= 0L || !validLength(ownedPcm, false)) return false
        synchronized(lock) {
            if (closed.get() || segmentId <= lastAcceptedFinalSegmentId) return false
            // Long offline partials repeat most of the final's work and cannot be
            // interrupted when the final arrives. Keep early feedback, then reserve
            // the worker for complete segments. Never truncate the final PCM.
            if (ownedPcm.size.toLong() * 1000 / configuration.sampleRate > configuration.maxPartialWindowMs) {
                if (pendingPartial?.segmentId == segmentId) pendingPartial = null
                diagnostics?.event("QWEN_PARTIAL_SKIPPED", "segment" to segmentId,
                    "reason" to "LONG_WINDOW", "maxPartialMs" to configuration.maxPartialWindowMs)
                return false
            }
            if (activeSegmentId != null && segmentId < activeSegmentId!!) return false
            if (activeSegmentId != segmentId) {
                activeSegmentId = segmentId
                lastPartialText = null
                lastPartialSegmentId = null
            }
            currentRevision++
            pendingPartial = Job(
                segmentId, currentRevision, ownedPcm, false,
                acousticEvidence = acousticEvidence,
                generation = generation,
            )
            scheduleLocked()
        }
        return true
    }

    /**
     * Les FINAL sont FIFO : contrairement aux PARTIAL, ils ne sont jamais
     * silencieusement écrasés. Si la file est saturée, le retour false et
     * onBackpressure rendent la dégradation visible au service.
     */
    fun offerFinal(
        segmentId: Long,
        ownedPcm: FloatArray,
        boundaryReason: String,
        acousticEvidence: AcousticEvidence,
    ): Boolean {
        if (closed.get() || segmentId <= 0L || !validLength(ownedPcm, true)) return false
        var backlog: Int? = null
        synchronized(lock) {
            if (closed.get() || segmentId <= lastAcceptedFinalSegmentId) return false
            if (pendingFinals.size >= configuration.maxPendingFinals) {
                backlog = pendingFinals.size
            } else {
                lastAcceptedFinalSegmentId = segmentId
                currentRevision++
                pendingFinals.addLast(
                    Job(segmentId, currentRevision, ownedPcm, true, boundaryReason, acousticEvidence, generation)
                )
                if (pendingPartial?.segmentId == segmentId) pendingPartial = null
                scheduleLocked()
            }
        }
        if (backlog != null) {
            diagnostics?.event("QWEN_BACKPRESSURE", "segment" to segmentId, "pendingFinals" to backlog)
            listener.onBackpressure(segmentId, backlog!!)
            return false
        }
        return true
    }

    /** Diagnostic only, lower priority, two global FIFO slots, shared native model.
     * Never invokes product listeners; caller must have neural confirmation. */
    internal fun offerDiagnostic(segmentId: Long, pcm: FloatArray, lane: String): Boolean =
        offerDiagnosticStatus(segmentId, pcm, lane) == DiagnosticOfferStatus.QUEUED

    internal fun pendingDiagnosticCount(): Int = synchronized(lock) { pendingDiagnostics.size }

    internal fun offerDiagnosticStatus(segmentId: Long, pcm: FloatArray, lane: String): DiagnosticOfferStatus =
        synchronized(lock) {
            val fingerprint = if (pcm.isNotEmpty() && validLength(pcm, true) && pcm.all { it.isFinite() }) {
                GuardianPcmIdentity.sha256(pcm)
            } else null
            val duplicate = fingerprint?.let { diagnosticInputs[it] }
            val status = when {
                closed.get() -> DiagnosticOfferStatus.CLOSED
                segmentId <= 0L -> DiagnosticOfferStatus.INVALID_SEGMENT
                lane.isBlank() -> DiagnosticOfferStatus.INVALID_LANE
                pcm.isEmpty() -> DiagnosticOfferStatus.EMPTY_PCM
                !validLength(pcm, true) -> DiagnosticOfferStatus.INVALID_LENGTH
                pcm.any { !it.isFinite() } -> DiagnosticOfferStatus.INVALID_PCM
                duplicate != null -> DiagnosticOfferStatus.DUPLICATE_PCM
                pendingDiagnostics.size >= MAX_PENDING_DIAGNOSTICS -> DiagnosticOfferStatus.QUEUE_FULL
                else -> DiagnosticOfferStatus.QUEUED
            }
            if (status == DiagnosticOfferStatus.QUEUED) {
                diagnosticInputs[checkNotNull(fingerprint)] = segmentId to lane
                while (diagnosticInputs.size > 32) diagnosticInputs.remove(diagnosticInputs.keys.first())
                pendingDiagnostics.addLast(Job(segmentId, 0, pcm.copyOf(), true, null,
                    AcousticEvidence.NEURAL_CONFIRMED, generation, diagnosticLane = lane, diagnosticFingerprint = fingerprint))
                scheduleLocked()
            }
            diagnostics?.event("STUDY_QWEN_OFFER", "segment" to segmentId, "lane" to lane,
                "accepted" to (status == DiagnosticOfferStatus.QUEUED), "status" to status.name,
                "duplicateOfSegment" to if (status == DiagnosticOfferStatus.DUPLICATE_PCM) duplicate?.first else null,
                "duplicateOfLane" to if (status == DiagnosticOfferStatus.DUPLICATE_PCM) duplicate?.second else null,
                "pendingDiagnostics" to pendingDiagnostics.size,
                "maxPendingDiagnostics" to MAX_PENDING_DIAGNOSTICS, "role" to "SHADOW_NO_ALERT")
            status
        }

    /** Accepted jobs get one terminal diagnostic record even when never decoded. */
    private fun dropPendingDiagnosticsLocked(reason: String) {
        while (pendingDiagnostics.isNotEmpty()) {
            val job = pendingDiagnostics.removeFirst()
            diagnostics?.event("STUDY_QWEN_RESULT", "segment" to job.segmentId,
                "lane" to job.diagnosticLane, "eligible" to false, "success" to false,
                "status" to "DROPPED", "reason" to reason, "text" to null,
                "started" to false, "queueWaitMs" to (SystemClock.elapsedRealtime() - job.queuedAtMs).coerceAtLeast(0),
                "decodeWallMs" to 0, "audioMs" to job.samples.size * 1000L / configuration.sampleRate,
                "role" to "SHADOW_NO_ALERT")
        }
    }

    private fun validLength(pcm: FloatArray, final: Boolean): Boolean {
        val durationMs = pcm.size.toLong() * 1_000L / configuration.sampleRate
        return durationMs >= (if (final) configuration.minFinalWindowMs else configuration.minPartialWindowMs) &&
            durationMs <= configuration.maxWindowMs
    }

    // Un seul drain dans l'executor, quel que soit le nombre de fenêtres.
    private fun scheduleLocked() {
        if (pumping || closed.get()) return
        pumping = true
        try {
            worker.execute { drain() }
        } catch (_: RejectedExecutionException) {
            pumping = false
        }
    }

    private fun drain() {
        while (true) {
            val job = synchronized(lock) {
                if (closed.get()) {
                    pendingFinals.clear()
                    pendingPartial = null
                    dropPendingDiagnosticsLocked("DECODER_CLOSED")
                    pumping = false
                    return
                }
                when {
                    pendingFinals.isNotEmpty() -> pendingFinals.removeFirst()
                    pendingPartial != null -> pendingPartial.also { pendingPartial = null }
                    pendingDiagnostics.isNotEmpty() -> pendingDiagnostics.removeFirst()
                    else -> {
                        pumping = false
                        return
                    }
                }
            } ?: continue

            if (job.diagnosticLane != null) {
                val start = SystemClock.elapsedRealtime()
                val result = runCatching { qwen.decode(job.samples) }.getOrNull()
                synchronized(lock) {
                    val eligible = !closed.get() && job.generation == generation
                    val status = when {
                        !eligible -> "DROPPED"
                        result == null -> "FAILED"
                        result.text.isBlank() -> "EMPTY"
                        else -> "DECODED"
                    }
                    val reason = when {
                        closed.get() -> "DECODER_CLOSED"
                        job.generation != generation -> "RESET"
                        result == null -> "DECODE_FAILED"
                        result.text.isBlank() -> "EMPTY_TEXT"
                        else -> null
                    }
                    if (result == null && job.generation == generation) job.diagnosticFingerprint?.let { diagnosticInputs.remove(it) }
                    diagnostics?.event("STUDY_QWEN_RESULT",
                        "eligible" to eligible, "status" to status, "reason" to reason, "started" to true,
                        "segment" to job.segmentId, "lane" to job.diagnosticLane,
                        "success" to (eligible && result != null), "text" to if (eligible) result?.text else null,
                        "queueWaitMs" to start-job.queuedAtMs,
                        "decodeWallMs" to SystemClock.elapsedRealtime()-start,
                        "audioMs" to job.samples.size*1000L/configuration.sampleRate,
                        "role" to "SHADOW_NO_ALERT")
                }
                continue
            }
            // Après un FINAL, aucun ancien PARTIAL du même segment ne doit sortir.
            val decodeStartedAtMs = SystemClock.elapsedRealtime()
            diagnostics?.event("QWEN_START", "segment" to job.segmentId, "revision" to job.revision,
                "final" to job.isFinal, "generation" to job.generation,
                "audioMs" to job.samples.size.toLong() * 1000 / configuration.sampleRate,
                "queueWaitMs" to (decodeStartedAtMs - job.queuedAtMs).coerceAtLeast(0))
            val decoded = try {
                qwen.decode(job.samples)
            } catch (error: Throwable) {
                Log.e(TAG, "Erreur Qwen progressif segment=${job.segmentId}", error)
                null
            }

            // A newer pending window must NOT suppress an otherwise useful
            // decoded partial: Qwen offline decode can take longer than the
            // 1-second capture cadence. Publishing the last completed view,
            // then revising it, preserves incremental word delivery.
            // Reject partials from segments already finalized or superseded.
            val stillRelevant = synchronized(lock) {
                !closed.get() && job.generation == generation && (
                    job.isFinal ||
                    (job.segmentId == activeSegmentId &&
                        job.segmentId > lastAcceptedFinalSegmentId &&
                        pendingFinals.none { it.segmentId == job.segmentId })
                )
            }
            val decodedAtMs = SystemClock.elapsedRealtime()
            val pendingCount = synchronized(lock) { pendingFinals.size }
            Log.i(TAG, "QWEN_PROGRESS_METRIC : " +
                "segment=${job.segmentId}, final=${job.isFinal}, " +
                "acoustic=${job.acousticEvidence}, " +
                "queueWaitMs=${(decodeStartedAtMs - job.queuedAtMs).coerceAtLeast(0L)}, " +
                "decodeWallMs=${(decodedAtMs - decodeStartedAtMs).coerceAtLeast(0L)}, " +
                "decodeMs=${decoded?.elapsedMs ?: -1L}, " +
                "audioMs=${job.samples.size.toLong() * 1_000L / configuration.sampleRate}, " +
                "pendingFinals=$pendingCount, relevant=$stillRelevant, " +
                "resultChars=${decoded?.text?.length ?: -1}")
            diagnostics?.event("QWEN_RESULT", "segment" to job.segmentId, "revision" to job.revision,
                "final" to job.isFinal, "generation" to job.generation,
                "eligible" to stillRelevant, "success" to (decoded != null),
                "decodeWallMs" to (decodedAtMs - decodeStartedAtMs).coerceAtLeast(0),
                "audioMs" to job.samples.size.toLong() * 1000 / configuration.sampleRate,
                "pendingFinals" to pendingCount,
                "text" to if (stillRelevant) decoded?.text else null)
            if (!stillRelevant) continue

            if (decoded == null) {
                synchronized(lock) {
                    if (!closed.get() && job.generation == generation) {
                        listener.onDecoderUnavailable(job.segmentId, job.isFinal)
                    }
                }
                continue
            }

            val text = decoded.text.trim()
            val hypothesis = Hypothesis(
                segmentId = job.segmentId,
                revision = job.revision,
                text = text,
                language = decoded.language,
                isFinal = job.isFinal,
                boundaryReason = job.boundaryReason,
                observedAtElapsedRealtimeMs = SystemClock.elapsedRealtime(),
                audioDurationMs = decoded.audioDurationMs,
                decodeElapsedMs = decoded.elapsedMs,
                acousticEvidence = job.acousticEvidence,
            )

            if (job.isFinal) {
                synchronized(lock) {
                    if (lastPartialSegmentId == job.segmentId) {
                        lastPartialText = null
                        lastPartialSegmentId = null
                    }
                }
                synchronized(lock) {
                    if (!closed.get() && job.generation == generation) {
                        listener.onFinal(hypothesis)
                        diagnostics?.event("QWEN_PUBLISH", "segment" to job.segmentId,
                            "revision" to job.revision, "final" to true, "text" to hypothesis.text)
                    }
                }
            } else if (text.isNotBlank()) {
                val changed = synchronized(lock) {
                    if (lastPartialSegmentId == job.segmentId &&
                        lastPartialText == text) {
                        false
                    } else {
                        lastPartialSegmentId = job.segmentId
                        lastPartialText = text
                        true
                    }
                }
                synchronized(lock) {
                    if (changed && !closed.get() && job.generation == generation &&
                        job.segmentId == activeSegmentId &&
                        job.segmentId > lastAcceptedFinalSegmentId) {
                        listener.onPartial(hypothesis)
                        diagnostics?.event("QWEN_PUBLISH", "segment" to job.segmentId,
                            "revision" to job.revision, "final" to false, "text" to hypothesis.text)
                    }
                }
            }
        }
    }

    /** Invalidate queued AND in-flight results after a capture/VAD discontinuity. */
    fun reset() {
        synchronized(lock) {
            generation++
            diagnosticInputs.clear()
            diagnostics?.event("QWEN_RESET", "generation" to generation)
            pendingFinals.clear()
            pendingPartial = null
            dropPendingDiagnosticsLocked("RESET")
            activeSegmentId = null
            lastPartialText = null
            lastPartialSegmentId = null
        }
    }

    /**
     * Arrêt non bloquant : le worker déjà en décodage peut terminer, mais ses
     * callbacks sont invalidés. La libération Qwen intervient SUR le même
     * executor après le decode pour éviter use-after-free natif.
     */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(lock) {
            diagnosticInputs.clear()
            pendingFinals.clear()
            pendingPartial = null
            dropPendingDiagnosticsLocked("DECODER_CLOSED")
        }
        try {
            worker.execute { qwen.release() }
        } catch (_: RejectedExecutionException) {
            // Si l'executor est déjà arrêté, la couche propriétaire doit
            // s'assurer que Qwen est libéré après la fin des décodages.
        }
        worker.shutdown()
    }

    companion object {
        private const val TAG = "GuardianQwenProgressive"
        private const val MAX_PENDING_DIAGNOSTICS = 2
    }
}
