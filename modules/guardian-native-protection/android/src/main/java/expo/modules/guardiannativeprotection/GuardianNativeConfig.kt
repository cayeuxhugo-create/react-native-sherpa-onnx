package expo.modules.guardiannativeprotection

data class GuardianNativeConfig(
  val childId: String,
  val childName: String,
  val familyId: String,
  val familyCode: String,
  val deviceId: String,

  /* Moteurs natifs */
  val gpsEnabled: Boolean = true,
  val voiceEnabled: Boolean = false,
  val voiceProfile: String = "DISTANT",
  val voiceDiagnosticsEnabled: Boolean = false,
  val ptiEnabled: Boolean = false,

  /* Upload */
  val uploadIntervalSeconds: Int =
    DEFAULT_UPLOAD_INTERVAL_SECONDS,

  /* PTI */
  val ptiDelayMinutes: Int =
    DEFAULT_PTI_DELAY_MINUTES,

  val ptiMovementThresholdMeters: Double =
    DEFAULT_PTI_MOVEMENT_THRESHOLD_METERS,

  val ptiConfirmationSeconds: Int =
    DEFAULT_PTI_CONFIRMATION_SECONDS,

  /* Détection de chute */
  val fallImpactThreshold: Double =
    DEFAULT_FALL_IMPACT_THRESHOLD,

  val fallStillThreshold: Double =
    DEFAULT_FALL_STILL_THRESHOLD,

  val fallStillDelaySeconds: Int =
    DEFAULT_FALL_STILL_DELAY_SECONDS,

  val fallScoreTrigger: Int =
    DEFAULT_FALL_SCORE_TRIGGER,

  /* Métadonnées */
  val version: Int = CURRENT_VERSION,
  val lastUpdated: Long =
    System.currentTimeMillis()
) {

  companion object {

    const val CURRENT_VERSION = 2

    /* Upload */

    const val DEFAULT_UPLOAD_INTERVAL_SECONDS =
      30

    const val MIN_UPLOAD_INTERVAL_SECONDS =
      5

    const val MAX_UPLOAD_INTERVAL_SECONDS =
      3600

    /* PTI */

    const val DEFAULT_PTI_DELAY_MINUTES =
      30

    const val DEFAULT_PTI_MOVEMENT_THRESHOLD_METERS =
      10.0

    const val DEFAULT_PTI_CONFIRMATION_SECONDS =
      30

    /* Chute */

    const val DEFAULT_FALL_IMPACT_THRESHOLD =
      3.0

    const val DEFAULT_FALL_STILL_THRESHOLD =
      1.20

    const val DEFAULT_FALL_STILL_DELAY_SECONDS =
      15

    const val DEFAULT_FALL_SCORE_TRIGGER =
      70
  }
}