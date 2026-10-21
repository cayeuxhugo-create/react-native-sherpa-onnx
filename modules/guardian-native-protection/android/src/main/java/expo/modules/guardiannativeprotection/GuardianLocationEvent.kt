package expo.modules.guardiannativeprotection

data class GuardianLocationEvent(

  override val eventId: String,

  override val priority: GuardianEventPriority,

  override val createdAt: Long,

  override val recordedAt: Long,

  override val schemaVersion: Int =
    GuardianEventBatchEncoder.SCHEMA_VERSION,

  val childId: String,

  val familyId: String,

  val familyCode: String,

  val deviceId: String,

  val latitude: Double,

  val longitude: Double,

  val accuracy: Float? = null,

  val speedMps: Float? = null,

  val altitude: Double? = null

) : GuardianNativeEvent {

  override val eventType:
    GuardianEventType =
    GuardianEventType.LOCATION
}