package expo.modules.guardiannativeprotection

data class GuardianLocationRecord(
  val id: String,
  val latitude: Double,
  val longitude: Double,
  val accuracy: Float?,
  val speedMps: Float?,
  val altitude: Double?,
  val locationTimestamp: Long,
  val recordedAt: Long,
  val uploaded: Boolean = false
)