package expo.modules.guardiannativeprotection

import expo.modules.kotlin.records.Field
import expo.modules.kotlin.records.Record

class GuardianNativeConfigRecord : Record {

  @Field
  val childId: String = ""

  @Field
  val childName: String = ""

  @Field
  val familyId: String = ""

  @Field
  val familyCode: String = ""

  @Field
  val deviceId: String = ""

  @Field
  val gpsEnabled: Boolean = true

  @Field
  val voiceEnabled: Boolean = false

  @Field
  val voiceProfile: String = "DISTANT"

  @Field
  val voiceDiagnosticsEnabled: Boolean = false

  @Field
  val ptiEnabled: Boolean = false

  @Field
  val uploadIntervalSeconds: Int = 30
}