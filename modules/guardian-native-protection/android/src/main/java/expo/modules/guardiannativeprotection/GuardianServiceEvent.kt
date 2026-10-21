package expo.modules.guardiannativeprotection

data class GuardianServiceEvent(

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

  val reason: String

) : GuardianNativeEvent {

  override val eventType:
    GuardianEventType =
      GuardianEventType.TRACKING_STOPPED
}