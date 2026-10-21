package expo.modules.guardiannativeprotection

/**
 * Origine d’un événement vocal Guardian Voice.
 */
enum class GuardianVoiceCause {
  WAKE_PHRASE,
  EMERGENCY_PHRASE,
  DISTRESS_COMMAND,
  MANUAL
}

/**
 * État de traitement d’un événement vocal.
 *
 * DETECTED :
 * une phrase pertinente a été reconnue et doit encore
 * être analysée ou confirmée.
 *
 * CONFIRMED :
 * la demande d’assistance est confirmée et doit être
 * transmise comme une alerte critique.
 *
 * CANCELLED :
 * la détection a été rejetée ou annulée.
 */
enum class GuardianVoiceState {
  DETECTED,
  CONFIRMED,
  CANCELLED
}

/**
 * Événement natif produit par le moteur vocal.
 *
 * Aucun enregistrement audio brut n’est conservé.
 * Seules les informations utiles à la décision et
 * à la traçabilité de sécurité sont enregistrées.
 */
data class GuardianVoiceEvent(

  override val eventId: String,

  override val priority: GuardianEventPriority,

  override val createdAt: Long,

  override val recordedAt: Long,

  override val schemaVersion: Int = 1,

  val childId: String,

  val familyId: String,

  val familyCode: String,

  val deviceId: String,

  val cause: GuardianVoiceCause,

  val state: GuardianVoiceState,

  /**
   * Score de confiance compris entre 0 et 100.
   */
  val confidenceScore: Int,

  /**
   * Texte reconnu puis normalisé.
   *
   * Il peut rester null lorsqu’un moteur local ne fournit
   * qu’un identifiant de mot-clé détecté.
   */
  val recognizedText: String? = null,

  /**
   * Expression de réveil reconnue.
   */
  val wakePhrase: String? = null,

  /**
   * Expression d’urgence reconnue.
   */
  val emergencyPhrase: String? = null,

  /**
   * Dernière position connue au moment de l’événement.
   *
   * L’événement doit pouvoir être enregistré même lorsque
   * le GPS est temporairement indisponible.
   */
  val latitude: Double? = null,

  val longitude: Double? = null,

  val accuracy: Float? = null

) : GuardianNativeEvent {

  /**
   * Une détection ou son annulation reste un événement
   * VOICE_TRIGGER.
   *
   * Une demande confirmée devient VOICE_ALERT.
   */
  override val eventType: GuardianEventType =
    when (state) {
      GuardianVoiceState.DETECTED,
      GuardianVoiceState.CANCELLED ->
        GuardianEventType.VOICE_TRIGGER

      GuardianVoiceState.CONFIRMED ->
        GuardianEventType.VOICE_SOS
    }

  init {
    require(
      eventId.isNotBlank()
    ) {
      "eventId Voice vide"
    }

    require(
      childId.isNotBlank()
    ) {
      "childId Voice vide"
    }

    require(
      familyId.isNotBlank()
    ) {
      "familyId Voice vide"
    }

    require(
      familyCode.isNotBlank()
    ) {
      "familyCode Voice vide"
    }

    require(
      deviceId.isNotBlank()
    ) {
      "deviceId Voice vide"
    }

    require(
      confidenceScore in 0..100
    ) {
      "Score Voice invalide : $confidenceScore"
    }

    require(
      recognizedText == null ||
        recognizedText.length <= MAX_RECOGNIZED_TEXT_LENGTH
    ) {
      "Texte Voice trop long"
    }

    require(
      wakePhrase == null ||
        wakePhrase.length <= MAX_PHRASE_LENGTH
    ) {
      "Phrase de réveil Voice trop longue"
    }

    require(
      emergencyPhrase == null ||
        emergencyPhrase.length <= MAX_PHRASE_LENGTH
    ) {
      "Phrase d’urgence Voice trop longue"
    }

    require(
      recognizedText == null ||
        recognizedText.isNotBlank()
    ) {
      "Texte Voice vide"
    }

    require(
      wakePhrase == null ||
        wakePhrase.isNotBlank()
    ) {
      "Phrase de réveil Voice vide"
    }

    require(
      emergencyPhrase == null ||
        emergencyPhrase.isNotBlank()
    ) {
      "Phrase d’urgence Voice vide"
    }

    require(
      (latitude == null) ==
        (longitude == null)
    ) {
      "Position Voice incomplète"
    }

    require(
      latitude == null ||
        (
          latitude.isFinite() &&
            latitude in -90.0..90.0
          )
    ) {
      "Latitude Voice invalide"
    }

    require(
      longitude == null ||
        (
          longitude.isFinite() &&
            longitude in -180.0..180.0
          )
    ) {
      "Longitude Voice invalide"
    }

    require(
      accuracy == null ||
        (
          accuracy.isFinite() &&
            accuracy >= 0f
          )
    ) {
      "Précision GPS Voice invalide"
    }

    require(
      accuracy == null ||
        latitude != null
    ) {
      "Précision Voice sans position"
    }

    require(
      createdAt > 0L
    ) {
      "createdAt Voice invalide"
    }

    require(
      recordedAt > 0L
    ) {
      "recordedAt Voice invalide"
    }

    require(
      schemaVersion > 0
    ) {
      "schemaVersion Voice invalide"
    }

    require(
      recordedAt >= createdAt - ALLOWED_CLOCK_DRIFT_MS
    ) {
      "Chronologie Voice incohérente"
    }

    require(
      state != GuardianVoiceState.CONFIRMED ||
        cause != GuardianVoiceCause.WAKE_PHRASE
    ) {
      "Une simple phrase de réveil ne peut pas confirmer une alerte Voice"
    }

    require(
      cause != GuardianVoiceCause.WAKE_PHRASE ||
        wakePhrase != null
    ) {
      "Phrase de réveil Voice absente"
    }

    require(
      cause != GuardianVoiceCause.EMERGENCY_PHRASE ||
        emergencyPhrase != null
    ) {
      "Phrase d’urgence Voice absente"
    }
  }

  companion object {
    private const val ALLOWED_CLOCK_DRIFT_MS =
      5 * 60 * 1000L

    private const val MAX_RECOGNIZED_TEXT_LENGTH =
      500

    private const val MAX_PHRASE_LENGTH =
      160
  }
}