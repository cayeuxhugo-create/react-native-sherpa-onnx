package expo.modules.guardiannativeprotection

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Moteur PTI natif Guardian Voice.
 *
 * Responsabilités :
 * - écouter l’accéléromètre Android ;
 * - détecter un impact potentiellement dangereux ;
 * - analyser l’immobilité après l’impact ;
 * - analyser l’orientation du téléphone ;
 * - calculer un score PTI ;
 * - produire un GuardianPtiEvent.
 *
 * Cette classe :
 * - ne communique pas avec Firestore ;
 * - ne réalise aucun upload réseau ;
 * - ne modifie aucune interface React Native ;
 * - ne décide pas seule de confirmer définitivement
 *   une alerte.
 *
 * Elle produit une pré-alerte PTI. La confirmation ou
 * l’annulation sera gérée ensuite par le service natif
 * et l’écran de confirmation.
 */
class GuardianPtiEngine(
  context: Context,
  private val config: GuardianNativeConfig,
  private val listener: Listener
) : SensorEventListener {

  interface Listener {

    /**
     * Appelé lorsqu’une situation PTI suffisamment
     * crédible est détectée.
     */
    fun onPtiEvent(
      event: GuardianPtiEvent
    )

    /**
     * Remonte l’état du moteur pour les diagnostics.
     */
    fun onPtiStatusChanged(
      status: Status
    ) = Unit

    /**
     * Permet au moteur de joindre la dernière position
     * GPS connue à l’événement PTI.
     *
     * Retourner null si aucune position n’est disponible.
     */
    fun getLastKnownLocation():
      LastKnownLocation? = null
  }

  data class LastKnownLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float?
  ) {
    init {
      require(
        latitude.isFinite() &&
          latitude in -90.0..90.0
      ) {
        "Latitude PTI invalide"
      }

      require(
        longitude.isFinite() &&
          longitude in -180.0..180.0
      ) {
        "Longitude PTI invalide"
      }

      require(
        accuracy == null ||
          (
            accuracy.isFinite() &&
              accuracy >= 0f
            )
      ) {
        "Précision PTI invalide"
      }
    }
  }

  data class Status(
    val running: Boolean,
    val sensorAvailable: Boolean,
    val phase: Phase,
    val currentForceG: Float,
    val peakImpactG: Float?,
    val horizontal: Boolean,
    val stillDurationSeconds: Int,
    val lastSensorAt: Long?,
    val lastScore: Int?
)

  enum class Phase {
    STOPPED,
    MONITORING,
    POST_IMPACT_ANALYSIS,
    COOLDOWN
  }

  companion object {
    private const val TAG =
      "GuardianPtiEngine"

    /*
     * L’accélération terrestre normale est proche
     * de 9,81 m/s².
     */
    private const val EARTH_GRAVITY =
      SensorManager.GRAVITY_EARTH

    /*
     * Intervalle d’analyse pendant la période
     * qui suit un impact.
     */
    private const val ANALYSIS_INTERVAL_MS =
      250L

    /*
     * Durée minimale pendant laquelle le téléphone
     * doit rester horizontal pour renforcer le score.
     */
    private const val HORIZONTAL_MIN_DURATION_MS =
      2_000L

    /*
     * Empêche plusieurs pré-alertes pour une même chute.
     */
    private const val EVENT_COOLDOWN_MS =
      60_000L

    /*
     * Une force proche de 1 g correspond à un téléphone
     * immobile soumis uniquement à la gravité.
     */
    private const val DEFAULT_REST_FORCE_G =
      1.0f

    /*
     * Tolérance utilisée pour considérer que le téléphone
     * reste immobile.
     */
    private const val STILL_FORCE_TOLERANCE_G =
      0.20f

    /*
     * La composante Z domine lorsque le téléphone est
     * globalement posé à plat.
     */
    private const val HORIZONTAL_Z_RATIO =
      0.75f

    /*
     * Évite qu’une mesure extrêmement anormale ou
     * corrompue produise un score incohérent.
     */
    private const val MAX_REASONABLE_FORCE_G =
      20f

    /*
     * Une reprise de mouvement crédible après l'impact
     * réduit le risque de faux positif.
     */
    private const val RECOVERY_MIN_DELAY_MS =
      1_500L

    private const val RECOVERY_FORCE_DEVIATION_G =
      0.45f

    private const val RECOVERY_REQUIRED_SAMPLES =
      10

    private const val RECOVERY_SCORE_PENALTY =
      35
  }

  private val appContext =
    context.applicationContext

  private val sensorManager =
    appContext.getSystemService(
      Context.SENSOR_SERVICE
    ) as SensorManager

  private val accelerometer =
    sensorManager.getDefaultSensor(
      Sensor.TYPE_ACCELEROMETER
    )

  private val handlerThread =
    HandlerThread(
      "GuardianPtiSensorThread"
    )

  private var handler:
    Handler? = null

  @Volatile
  private var running =
    false

  @Volatile
  private var phase =
    Phase.STOPPED

  private var analysisRunnable:
    Runnable? = null

  private var cooldownRunnable:
    Runnable? = null

  private var impactDetectedAtElapsed =
    0L

  private var stillStartedAtElapsed:
    Long? = null

  private var horizontalStartedAtElapsed:
    Long? = null

  private var peakImpactG:
    Float? = null

  private var currentForceG =
    DEFAULT_REST_FORCE_G

  private var postImpactForceG =
    DEFAULT_REST_FORCE_G

  private var horizontalAfterImpact =
    false

  private var lastScore:
    Int? = null

  private var lastSensorAt: Long? = null

  private var recoverySampleCount =
    0

  private var recoveryDetected =
    false

  private var lastEventCreatedAtElapsed =
    0L

  private var latestX =
    0f

  private var latestY =
    0f

  private var latestZ =
    EARTH_GRAVITY

  /**
   * Démarre l’écoute native de l’accéléromètre.
   *
   * Cette méthode peut être appelée plusieurs fois :
   * un moteur déjà démarré ne sera pas redémarré.
   */
  @Synchronized
  fun start() {
    if (running) {
      Log.d(
        TAG,
        "Moteur PTI déjà actif"
      )

      return
    }

    if (accelerometer == null) {
      Log.e(
        TAG,
        "Accéléromètre Android indisponible"
      )

      phase =
        Phase.STOPPED

      publishStatus(
        sensorAvailable = false
      )

      return
    }

    if (!handlerThread.isAlive) {
      handlerThread.start()
    }

    handler =
      Handler(
        handlerThread.looper
      )

    resetDetectionState()

    val registered =
      sensorManager.registerListener(
        this,
        accelerometer,
        SensorManager.SENSOR_DELAY_GAME,
        handler
      )

    if (!registered) {
      Log.e(
        TAG,
        "Impossible d’enregistrer le listener PTI"
      )

      phase =
        Phase.STOPPED

      publishStatus(
        sensorAvailable = false
      )

      return
    }

    running =
      true

    phase =
      Phase.MONITORING

    Log.d(
      TAG,
      "Moteur PTI natif démarré"
    )

    publishStatus()
  }

  /**
   * Arrête complètement le moteur PTI.
   */
  @Synchronized
  fun stop() {
    if (!running && phase == Phase.STOPPED) {
      return
    }

    running =
      false

    sensorManager.unregisterListener(
      this
    )

    analysisRunnable?.let {
      handler?.removeCallbacks(it)
    }

    cooldownRunnable?.let {
      handler?.removeCallbacks(it)
    }

    analysisRunnable =
      null

    cooldownRunnable =
      null

    phase =
      Phase.STOPPED

    resetDetectionState()

    Log.d(
      TAG,
      "Moteur PTI natif arrêté"
    )

    publishStatus()
  }

  /**
   * Libère également le thread interne.
   *
   * À appeler lorsque GuardianProtectionService est
   * définitivement détruit.
   */
  @Synchronized
  fun destroy() {
    stop()

    if (handlerThread.isAlive) {
      handlerThread.quitSafely()
    }

    handler =
      null
  }

  fun isRunning(): Boolean =
    running

  fun getStatus(): Status =
    buildStatus(
      sensorAvailable =
        accelerometer != null
    )

  override fun onSensorChanged(
    sensorEvent: SensorEvent?
  ) {
    if (
      !running ||
      sensorEvent == null ||
      sensorEvent.sensor.type !=
      Sensor.TYPE_ACCELEROMETER
    ) {
      return
    }

    if (sensorEvent.values.size < 3) {
      return
    }

    val x =
      sensorEvent.values[0]

    val y =
      sensorEvent.values[1]

    val z =
      sensorEvent.values[2]

    if (
      !x.isFinite() ||
      !y.isFinite() ||
      !z.isFinite()
    ) {
      return
    }

    latestX =
      x

    latestY =
      y

    latestZ =
      z

    val magnitude =
  sqrt(
    (
      x * x +
        y * y +
        z * z
    ).toDouble()
  ).toFloat()

    val forceG =
      magnitude /
        EARTH_GRAVITY

    if (
  !forceG.isFinite() ||
  forceG < 0f ||
  forceG > MAX_REASONABLE_FORCE_G
) {
  return
}

lastSensorAt =
    System.currentTimeMillis()

currentForceG =
    forceG



    when (phase) {
      Phase.MONITORING -> {
        detectPotentialImpact(
          forceG
        )
      }

      Phase.POST_IMPACT_ANALYSIS -> {
        analyzePostImpactSample(
          forceG = forceG,
          x = x,
          y = y,
          z = z
        )
      }

      Phase.COOLDOWN,
      Phase.STOPPED -> {
        // Aucune nouvelle détection pendant ces phases.
      }
    }
  }

  override fun onAccuracyChanged(
    sensor: Sensor?,
    accuracy: Int
  ) {
    Log.d(
      TAG,
      "Précision accéléromètre modifiée : $accuracy"
    )
  }

  private fun detectPotentialImpact(
    forceG: Float
  ) {
    if (
      forceG <
      config.fallImpactThreshold
    ) {
      return
    }

    val nowElapsed =
      SystemClock.elapsedRealtime()

    if (
  lastEventCreatedAtElapsed > 0L &&
  nowElapsed -
    lastEventCreatedAtElapsed <
  EVENT_COOLDOWN_MS
) {
  return
}

    impactDetectedAtElapsed =
      nowElapsed

    peakImpactG =
      forceG

    postImpactForceG =
      forceG

    stillStartedAtElapsed =
      null

    horizontalStartedAtElapsed =
      null

    horizontalAfterImpact =
      false

    lastScore =
      null

    phase =
      Phase.POST_IMPACT_ANALYSIS

    Log.w(
      TAG,
      "Impact PTI détecté : " +
        "force=${formatForce(forceG)} g"
    )

    schedulePostImpactAnalysis()

    publishStatus()
  }

  private fun analyzePostImpactSample(
    forceG: Float,
    x: Float,
    y: Float,
    z: Float
  ) {
    val currentPeak =
      peakImpactG ?: forceG

    if (forceG > currentPeak) {
      peakImpactG =
        forceG
    }

    postImpactForceG =
      forceG

    val nowElapsed =
      SystemClock.elapsedRealtime()

    updateStillnessState(
      forceG = forceG,
      nowElapsed = nowElapsed
    )

    updateHorizontalState(
      x = x,
      y = y,
      z = z,
      nowElapsed = nowElapsed
    )

    updateRecoveryState(
      forceG = forceG,
      nowElapsed = nowElapsed
    )
  }

  private fun updateStillnessState(
    forceG: Float,
    nowElapsed: Long
  ) {
    val forceDifference =
      abs(
        forceG -
          DEFAULT_REST_FORCE_G
      )

    val stillByConfiguredThreshold =
      forceG <=
        config.fallStillThreshold

    val stillByGravityStability =
      forceDifference <=
        STILL_FORCE_TOLERANCE_G

    val currentlyStill =
  stillByConfiguredThreshold &&
    stillByGravityStability

    if (currentlyStill) {
      if (stillStartedAtElapsed == null) {
        stillStartedAtElapsed =
          nowElapsed
      }
    } else {
      stillStartedAtElapsed =
        null
    }
  }

  private fun updateHorizontalState(
    x: Float,
    y: Float,
    z: Float,
    nowElapsed: Long
  ) {
    val magnitude =
  sqrt(
    (
      x * x +
        y * y +
        z * z
    ).toDouble()
  ).toFloat()

    if (magnitude <= 0f) {
      horizontalStartedAtElapsed =
        null

      horizontalAfterImpact =
        false

      return
    }

    val zRatio =
      abs(z) /
        magnitude

    val currentlyHorizontal =
      zRatio >=
        HORIZONTAL_Z_RATIO

    if (currentlyHorizontal) {
      if (horizontalStartedAtElapsed == null) {
        horizontalStartedAtElapsed =
          nowElapsed
      }

      val horizontalDuration =
        nowElapsed -
          (
            horizontalStartedAtElapsed
              ?: nowElapsed
            )

      if (
        horizontalDuration >=
        HORIZONTAL_MIN_DURATION_MS
      ) {
        horizontalAfterImpact =
          true
      }
    } else {
      horizontalStartedAtElapsed =
        null

      horizontalAfterImpact =
        false
    }
  }


  /**
   * Recherche une reprise de mouvement répétée après
   * l'impact. Une seule secousse ne suffit pas : plusieurs
   * mesures consécutives sont nécessaires.
   */
  private fun updateRecoveryState(
    forceG: Float,
    nowElapsed: Long
  ) {
    if (
      recoveryDetected ||
      impactDetectedAtElapsed <= 0L
    ) {
      return
    }

    val elapsedSinceImpact =
      nowElapsed -
        impactDetectedAtElapsed

    if (
      elapsedSinceImpact <
      RECOVERY_MIN_DELAY_MS
    ) {
      return
    }

    val forceDeviation =
      abs(
        forceG -
          DEFAULT_REST_FORCE_G
      )

    val credibleMovement =
      forceDeviation >=
        RECOVERY_FORCE_DEVIATION_G

    if (credibleMovement) {
      recoverySampleCount +=
        1

      if (
        recoverySampleCount >=
        RECOVERY_REQUIRED_SAMPLES
      ) {
        recoveryDetected =
          true

        Log.i(
          TAG,
          "Reprise de mouvement détectée après impact"
        )
      }
    } else {
      recoverySampleCount =
        0
    }
  }


  private fun schedulePostImpactAnalysis() {
    analysisRunnable?.let {
      handler?.removeCallbacks(it)
    }

    val runnable =
      object : Runnable {
        override fun run() {
          if (
            !running ||
            phase !=
            Phase.POST_IMPACT_ANALYSIS
          ) {
            return
          }

          evaluatePostImpactState()

          if (
            running &&
            phase ==
            Phase.POST_IMPACT_ANALYSIS
          ) {
            handler?.postDelayed(
              this,
              ANALYSIS_INTERVAL_MS
            )
          }
        }
      }

    analysisRunnable =
      runnable

    handler?.postDelayed(
      runnable,
      ANALYSIS_INTERVAL_MS
    )
  }

  private fun evaluatePostImpactState() {
    val nowElapsed =
      SystemClock.elapsedRealtime()

    val elapsedSinceImpact =
      nowElapsed -
        impactDetectedAtElapsed

    val requiredAnalysisDuration =
      config.fallStillDelaySeconds
        .coerceAtLeast(1)
        .toLong() *
        1_000L

    val stillDurationMs =
      stillStartedAtElapsed
        ?.let {
          nowElapsed - it
        }
        ?: 0L

    val stillDurationSeconds =
      (
        stillDurationMs /
          1_000L
        ).toInt()

    val score =
      calculateFallScore(
        peakImpact =
          peakImpactG
            ?: currentForceG,

        postImpactForce =
          postImpactForceG,

        horizontal =
          horizontalAfterImpact,

        stillDurationMs =
          stillDurationMs,

        requiredStillDurationMs =
          requiredAnalysisDuration,

        recoveryDetected =
          recoveryDetected
      )

    lastScore =
      score

    publishStatus()

    if (
      elapsedSinceImpact <
      requiredAnalysisDuration
    ) {
      return
    }

    if (
      score >=
      config.fallScoreTrigger
    ) {
      emitFallPrealert(
        score =
          score,

        stillDurationSeconds =
          stillDurationSeconds
      )

      enterCooldown()

      return
    }

    Log.d(
      TAG,
      "Impact PTI écarté : " +
        "score=$score/" +
        config.fallScoreTrigger
    )

    returnToMonitoring()
  }

  /**
   * Calcul du score compatible avec les mesures
   * réalisées pendant les tests Guardian Voice :
   *
   * - impact important : jusqu’à 40 points ;
   * - téléphone horizontal : 20 points ;
   * - immobilité après impact : jusqu’à 40 points.
   */
  private fun calculateFallScore(
    peakImpact: Float,
    postImpactForce: Float,
    horizontal: Boolean,
    stillDurationMs: Long,
    requiredStillDurationMs: Long,
    recoveryDetected: Boolean
  ): Int {
    var score =
      0

    val impactThreshold =
    config.fallImpactThreshold
        .coerceAtLeast(0.1)

    val impactRatio =
  peakImpact.toDouble() /
    impactThreshold

    score +=
  when {
    impactRatio >= 1.50 ->
      40

    impactRatio >= 1.25 ->
      35

    impactRatio >= 1.10 ->
      30

    impactRatio >= 1.00 ->
      25

    else ->
      0
  }

    if (horizontal) {
      score +=
        20
    }

    val stableAfterImpact =
      abs(
        postImpactForce -
          DEFAULT_REST_FORCE_G
      ) <=
        STILL_FORCE_TOLERANCE_G ||
        postImpactForce <=
        config.fallStillThreshold

    if (stableAfterImpact) {
      val requiredDuration =
        requiredStillDurationMs
          .coerceAtLeast(1L)

      val stillRatio =
        stillDurationMs
          .toDouble() /
          requiredDuration.toDouble()

      score +=
        when {
          stillRatio >= 1.0 ->
            40

          stillRatio >= 0.75 ->
            30

          stillRatio >= 0.50 ->
            20

          stillRatio >= 0.25 ->
            10

          else ->
            0
        }
    }

    if (recoveryDetected) {
      score -=
        RECOVERY_SCORE_PENALTY
    }

    return score.coerceIn(
      0,
      100
    )
  }

  private fun emitFallPrealert(
    score: Int,
    stillDurationSeconds: Int
  ) {
    val now =
      System.currentTimeMillis()

    val location =
      runCatching {
        listener.getLastKnownLocation()
      }.getOrElse { error ->
        Log.e(
          TAG,
          "Lecture de la dernière position PTI impossible",
          error
        )

        null
      }

    val event =
      GuardianPtiEvent(
        eventId =
          UUID.randomUUID()
            .toString(),

        priority =
          GuardianEventPriority.HIGH,

        createdAt =
          now,

        recordedAt =
          now,

        childId =
          config.childId,

        familyId =
          config.familyId,

        familyCode =
          config.familyCode,

        deviceId =
          config.deviceId,

        cause =
          GuardianPtiCause.FALL,

        state =
          GuardianPtiState.PREALERT,

        score =
          score,

        peakImpactG =
          peakImpactG,

        postImpactForceG =
          postImpactForceG,

        horizontalAfterImpact =
          horizontalAfterImpact,

        immobilityDurationSeconds =
          stillDurationSeconds,

        latitude =
          location?.latitude,

        longitude =
          location?.longitude,

        accuracy =
          location?.accuracy
      )

    lastEventCreatedAtElapsed =
      SystemClock.elapsedRealtime()

    Log.w(
      TAG,
      "Pré-alerte PTI générée : " +
        "eventId=${event.eventId}, " +
        "score=${event.score}, " +
        "impact=${event.peakImpactG}, " +
        "immobilité=" +
        "${event.immobilityDurationSeconds}s, " +
        "recovery=$recoveryDetected, " +
        "confidence=${confidenceForScore(score)}"
    )

    runCatching {
      listener.onPtiEvent(
        event
      )
    }.onFailure { error ->
      /*
       * Le moteur reste vivant même si le consommateur
       * de l’événement rencontre momentanément une erreur.
       */
      Log.e(
        TAG,
        "Échec de transmission locale de l’événement PTI",
        error
      )
    }
  }

  private fun enterCooldown() {
    analysisRunnable?.let {
      handler?.removeCallbacks(it)
    }

    analysisRunnable =
      null

    phase =
      Phase.COOLDOWN

    publishStatus()

    cooldownRunnable?.let {
      handler?.removeCallbacks(it)
    }

    val runnable =
      Runnable {
        if (!running) {
          return@Runnable
        }

        returnToMonitoring()
      }

    cooldownRunnable =
      runnable

    handler?.postDelayed(
      runnable,
      EVENT_COOLDOWN_MS
    )
  }

  private fun returnToMonitoring() {
    analysisRunnable?.let {
      handler?.removeCallbacks(it)
    }

    cooldownRunnable?.let {
      handler?.removeCallbacks(it)
    }

    analysisRunnable =
      null

    cooldownRunnable =
      null

    resetDetectionState()

    phase =
      if (running) {
        Phase.MONITORING
      } else {
        Phase.STOPPED
      }

    publishStatus()
  }

  private fun resetDetectionState() {
    impactDetectedAtElapsed =
      0L

    stillStartedAtElapsed =
      null

    horizontalStartedAtElapsed =
      null

    peakImpactG =
      null

    currentForceG =
      DEFAULT_REST_FORCE_G

    postImpactForceG =
      DEFAULT_REST_FORCE_G

    horizontalAfterImpact =
      false

    lastScore =
      null

    recoverySampleCount =
      0

    recoveryDetected =
      false

    latestX =
      0f

    latestY =
      0f

    latestZ =
      EARTH_GRAVITY
  }

  private fun getStillDurationSeconds(): Int {
    val startedAt =
      stillStartedAtElapsed
        ?: return 0

    return (
      (
        SystemClock.elapsedRealtime() -
          startedAt
        ) /
        1_000L
      ).toInt()
  }

  private fun publishStatus(
    sensorAvailable: Boolean =
      accelerometer != null
  ) {
    runCatching {
      listener.onPtiStatusChanged(
        buildStatus(
          sensorAvailable =
            sensorAvailable
        )
      )
    }.onFailure { error ->
      Log.e(
        TAG,
        "Échec de publication du statut PTI",
        error
      )
    }
  }

  private fun buildStatus(
    sensorAvailable: Boolean
  ): Status {
    return Status(
  running =
    running,

  sensorAvailable =
    sensorAvailable,

  phase =
    phase,

  currentForceG =
    currentForceG,

  peakImpactG =
    peakImpactG,

  horizontal =
    horizontalAfterImpact,

  stillDurationSeconds =
    getStillDurationSeconds(),

  lastSensorAt =
    lastSensorAt,

  lastScore =
    lastScore
)
  }

  private fun confidenceForScore(
    score: Int
  ): String =
    when {
      score >= 90 ->
        "VERY_HIGH"

      score >= 75 ->
        "HIGH"

      score >= 55 ->
        "MEDIUM"

      else ->
        "LOW"
    }

  private fun formatForce(
    forceG: Float
  ): String {
    return "%.2f".format(
      forceG
    )
  }
}