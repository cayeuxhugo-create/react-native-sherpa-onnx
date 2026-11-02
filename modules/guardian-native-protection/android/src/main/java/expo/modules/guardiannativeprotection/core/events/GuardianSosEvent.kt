package expo.modules.guardiannativeprotection

/**
 * Evénement produit lorsqu'un SOS manuel est
 * déclenché par l'utilisateur.
 *
 * Cet événement représente une alerte critique
 * confirmée. Il n'existe donc pas de phase de
 * préalerte.
 */
data class GuardianSosEvent(

    override val eventId: String,

    override val createdAt: Long,

    override val recordedAt: Long,

    override val schemaVersion: Int =
        GuardianNativeEvent.CURRENT_SCHEMA_VERSION,

    val childId: String,

    val familyId: String,

    val familyCode: String,

    val deviceId: String,

    val latitude: Double? = null,

    val longitude: Double? = null,

    val accuracy: Float? = null,

    val batteryPercent: Int? = null

) : GuardianNativeEvent {

    override val eventType =
        GuardianEventType.SOS

    override val priority =
        GuardianEventPriority.CRITICAL

    init {

        require(eventId.isNotBlank()) {
            "eventId SOS vide"
        }

        require(childId.isNotBlank()) {
            "childId SOS vide"
        }

        require(familyId.isNotBlank()) {
            "familyId SOS vide"
        }

        require(familyCode.isNotBlank()) {
            "familyCode SOS vide"
        }

        require(deviceId.isNotBlank()) {
            "deviceId SOS vide"
        }

        require(createdAt > 0L) {
            "createdAt SOS invalide"
        }

        require(recordedAt > 0L) {
            "recordedAt SOS invalide"
        }

        require(schemaVersion > 0) {
            "schemaVersion SOS invalide"
        }

        require(
            (latitude == null) == (longitude == null)
        ) {
            "Position SOS incomplète"
        }

        require(
            latitude == null ||
                (latitude.isFinite() &&
                 latitude in -90.0..90.0)
        ) {
            "Latitude SOS invalide"
        }

        require(
            longitude == null ||
                (longitude.isFinite() &&
                 longitude in -180.0..180.0)
        ) {
            "Longitude SOS invalide"
        }

        require(
            accuracy == null ||
                (accuracy.isFinite() && accuracy >= 0f)
        ) {
            "Précision GPS SOS invalide"
        }

        require(
            batteryPercent == null ||
                batteryPercent in 0..100
        ) {
            "Batterie SOS invalide"
        }
    }
}