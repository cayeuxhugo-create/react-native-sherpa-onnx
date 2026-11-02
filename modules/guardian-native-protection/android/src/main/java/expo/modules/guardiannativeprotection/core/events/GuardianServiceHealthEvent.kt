package expo.modules.guardiannativeprotection

data class GuardianServiceHealthEvent(

  override val eventId: String,

  override val priority:
    GuardianEventPriority,

  override val createdAt: Long,

  override val recordedAt: Long,

  override val schemaVersion: Int,

  val childId: String,

  val familyId: String,

  val familyCode: String,

  val deviceId: String,

  val serviceRunning: Boolean,

  val locationActive: Boolean,

  val voiceActive: Boolean,

  val ptiActive: Boolean,

  val lastLocationAt: Long?,

  val lastAudioAt: Long?,

  val lastSensorAt: Long?,

  val lastError: String?

) : GuardianNativeEvent {

  override val eventType =
    GuardianEventType.SERVICE_HEALTH
}