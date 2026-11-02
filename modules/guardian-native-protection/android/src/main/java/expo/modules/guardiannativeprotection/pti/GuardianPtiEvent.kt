package expo.modules.guardiannativeprotection

/**
 * Origine d’un événement PTI.
 */
enum class GuardianPtiCause {
  FALL,
  IMMOBILITY,
  DURESS,
  MANUAL
}

/**
 * État de traitement d’un événement PTI.
 */
enum class GuardianPtiState {
  PREALERT,
  CONFIRMED,
  CANCELLED
}

/**
 * Niveau de confiance calculé par le moteur PTI.
 */
enum class GuardianPtiConfidence {
  LOW,
  MEDIUM,
  HIGH,
  VERY_HIGH
}

/**
 * Événement natif produit par le moteur PTI.
 */
data class GuardianPtiEvent(

  override val eventId: String,
  override val priority: GuardianEventPriority,
  override val createdAt: Long,
  override val recordedAt: Long,
  override val schemaVersion: Int = 1,

  val childId: String,
  val familyId: String,
  val familyCode: String,
  val deviceId: String,

  val cause: GuardianPtiCause,
  val state: GuardianPtiState,

  /**
   * Score PTI compris entre 0 et 100.
   */
  val score: Int,

  /**
   * Niveau de confiance calculé à partir du score final.
   */
  val confidence: GuardianPtiConfidence =
    GuardianPtiConfidence.MEDIUM,

  /**
   * Indique si une reprise de mouvement crédible
   * a été détectée après l’impact.
   */
  val recoveryDetected: Boolean = false,

  /**
   * Trust Score GPS calculé par Guardian.
   */
  val gpsTrustScore: Int? = null,

  /**
   * Santé GNSS calculée par Guardian.
   */
  val gnssHealth: Int? = null,

  /**
   * Indique si la position jointe a été acceptée
   * par les contrôles Guardian.
   */
  val guardianPositionAccepted: Boolean? = null,

  /**
   * Force maximale mesurée pendant l’impact.
   */
  val peakImpactG: Float? = null,

  /**
   * Force mesurée après l’impact.
   */
  val postImpactForceG: Float? = null,

  /**
   * Indique si le téléphone a été détecté en position
   * horizontale après l’impact.
   */
  val horizontalAfterImpact: Boolean? = null,

  /**
   * Durée d’immobilité observée.
   */
  val immobilityDurationSeconds: Int? = null,

  /**
   * Dernière position connue au moment de l’événement.
   */
  val latitude: Double? = null,
  val longitude: Double? = null,
  val accuracy: Float? = null

) : GuardianNativeEvent {

  override val eventType: GuardianEventType =
    when (state) {
      GuardianPtiState.PREALERT,
      GuardianPtiState.CANCELLED ->
        GuardianEventType.PTI_PREALERT

      GuardianPtiState.CONFIRMED ->
        GuardianEventType.PTI_ALERT
    }

  init {
    require(eventId.isNotBlank()) {
      "eventId PTI vide"
    }

    require(childId.isNotBlank()) {
      "childId PTI vide"
    }

    require(familyId.isNotBlank()) {
      "familyId PTI vide"
    }

    require(familyCode.isNotBlank()) {
      "familyCode PTI vide"
    }

    require(deviceId.isNotBlank()) {
      "deviceId PTI vide"
    }

    require(score in 0..100) {
      "Score PTI invalide : $score"
    }

    require(
      gpsTrustScore == null ||
        gpsTrustScore in 0..100
    ) {
      "gpsTrustScore PTI invalide"
    }

    require(
      gnssHealth == null ||
        gnssHealth in 0..100
    ) {
      "gnssHealth PTI invalide"
    }

    require(
      peakImpactG == null ||
        (
          peakImpactG.isFinite() &&
            peakImpactG >= 0f
          )
    ) {
      "Impact PTI invalide"
    }

    require(
      postImpactForceG == null ||
        (
          postImpactForceG.isFinite() &&
            postImpactForceG >= 0f
          )
    ) {
      "Force post-impact PTI invalide"
    }

    require(
      immobilityDurationSeconds == null ||
        immobilityDurationSeconds >= 0
    ) {
      "Durée d’immobilité PTI invalide"
    }

    require(
      (latitude == null) ==
        (longitude == null)
    ) {
      "Position PTI incomplète"
    }

    require(
      latitude == null ||
        (
          latitude.isFinite() &&
            latitude in -90.0..90.0
          )
    ) {
      "Latitude PTI invalide"
    }

    require(
      longitude == null ||
        (
          longitude.isFinite() &&
            longitude in -180.0..180.0
          )
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
      "Précision GPS PTI invalide"
    }

    require(createdAt > 0L) {
      "createdAt PTI invalide"
    }

    require(recordedAt > 0L) {
      "recordedAt PTI invalide"
    }

    require(schemaVersion > 0) {
      "schemaVersion PTI invalide"
    }

    require(
      recordedAt >=
        createdAt -
          ALLOWED_CLOCK_DRIFT_MS
    ) {
      "Chronologie PTI incohérente"
    }
  }

  companion object {
    private const val ALLOWED_CLOCK_DRIFT_MS =
      5 * 60 * 1000L
  }
}