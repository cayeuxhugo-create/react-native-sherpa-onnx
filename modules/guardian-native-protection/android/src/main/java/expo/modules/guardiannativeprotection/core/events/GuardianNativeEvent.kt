package expo.modules.guardiannativeprotection

/**
 * Types d'événements pouvant être produits par
 * le Guardian Native Core.
 *
 * Ces valeurs sont envoyées à l'API Guardian.
 * Elles ne doivent jamais être renommées sans
 * migration correspondante côté serveur.
 */
enum class GuardianEventType {

  LOCATION,

  SERVICE_HEALTH,

  PTI_PREALERT,

  PTI_ALERT,

  VOICE_TRIGGER,

  VOICE_SOS,

  SOS,

  TRACKING_STOPPED
}

/**
 * Priorité métier d'un événement.
 *
 * Elle permet au Worker et au serveur
 * d'adapter le traitement des événements.
 */
enum class GuardianEventPriority {

  LOW,

  NORMAL,

  HIGH,

  CRITICAL
}

/**
 * Contrat commun à tous les événements
 * produits par le Guardian Native Core.
 */
interface GuardianNativeEvent {

  /**
   * Identifiant unique et immuable de l'événement.
   */
  val eventId: String

  /**
   * Type métier de l'événement.
   */
  val eventType: GuardianEventType

  /**
   * Niveau de priorité.
   */
  val priority: GuardianEventPriority

  /**
   * Instant représenté par l'événement.
   */
  val createdAt: Long

  /**
   * Instant où le cœur natif a enregistré
   * l'événement dans SQLite.
   */
  val recordedAt: Long

  /**
   * Version du schéma de sérialisation.
   */
  val schemaVersion: Int

  companion object {
    const val CURRENT_SCHEMA_VERSION = 1
  }
}