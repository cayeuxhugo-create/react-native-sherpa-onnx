package expo.modules.guardiannativeprotection

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

object GuardianEventBatchEncoder {

  const val NATIVE_CORE_VERSION =
    "1.0.0"

  const val SCHEMA_VERSION =
    1

  private const val MAX_BATCH_SIZE =
    50

  data class EncodedBatch(
    val batchId: String,
    val eventIds: List<String>,
    val json: String
  )

  /**
   * Transforme un lot de positions SQLite en requête JSON.
   */
  fun encodeLocationEvents(
    context: Context,
    config: GuardianNativeConfig,
    events: List<GuardianLocationEvent>
  ): EncodedBatch {
    validateConfig(
      config
    )

    validateBatchSize(
      events.size
    )

    validateUniqueEventIds(
      eventIds =
        events.map {
          it.eventId
        }
    )

    val appContext =
      context.applicationContext

    val batchId =
      UUID.randomUUID().toString()

    val sentAt =
      System.currentTimeMillis()

    val appVersion =
      resolveAppVersion(
        appContext
      )

    val eventsArray =
      JSONArray()

    val encodedEventIds =
      ArrayList<String>(
        events.size
      )

    events.forEach { event ->
      validateLocationEvent(
        config = config,
        event = event,
        sentAt = sentAt
      )

      val deviceJson =
        createDeviceJson(
          config = config,
          childId = event.childId,
          familyId = event.familyId,
          familyCode = event.familyCode,
          deviceId = event.deviceId,
          appVersion = appVersion
        )

      val payloadJson =
        JSONObject()
          .put(
            "latitude",
            event.latitude
          )
          .put(
            "longitude",
            event.longitude
          )
          .put(
            "accuracy",
            event.accuracy
              ?: JSONObject.NULL
          )
          .put(
            "speedMps",
            event.speedMps
              ?: JSONObject.NULL
          )
          .put(
            "altitude",
            event.altitude
              ?: JSONObject.NULL
          )
          .put(
            "locationTimestamp",
            event.createdAt
          )

      val eventJson =
        createEventJson(
          eventId = event.eventId,
          eventType = event.eventType,
          priority = event.priority,
          createdAt = event.createdAt,
          recordedAt = event.recordedAt,
          schemaVersion = event.schemaVersion,
          deviceJson = deviceJson,
          payloadJson = payloadJson
        )

      eventsArray.put(
        eventJson
      )

      encodedEventIds.add(
        event.eventId
      )
    }

    return createEncodedBatch(
      batchId = batchId,
      sentAt = sentAt,
      eventIds = encodedEventIds,
      eventsArray = eventsArray
    )
  }

  /**
   * Transforme un lot d'événements PTI SQLite en requête JSON.
   *
   * Le format d'enveloppe reste strictement identique à
   * celui des positions GPS. Seul le contenu de payload
   * varie selon le type d'événement.
   */
  fun encodePtiEvents(
    context: Context,
    config: GuardianNativeConfig,
    events: List<GuardianPtiEvent>
  ): EncodedBatch {
    validateConfig(
      config
    )

    validateBatchSize(
      events.size
    )

    validateUniqueEventIds(
      eventIds =
        events.map {
          it.eventId
        }
    )

    val appContext =
      context.applicationContext

    val batchId =
      UUID.randomUUID().toString()

    val sentAt =
      System.currentTimeMillis()

    val appVersion =
      resolveAppVersion(
        appContext
      )

    val eventsArray =
      JSONArray()

    val encodedEventIds =
      ArrayList<String>(
        events.size
      )

    events.forEach { event ->
      validatePtiEvent(
        config = config,
        event = event,
        sentAt = sentAt
      )

      val deviceJson =
        createDeviceJson(
          config = config,
          childId = event.childId,
          familyId = event.familyId,
          familyCode = event.familyCode,
          deviceId = event.deviceId,
          appVersion = appVersion
        )

      val payloadJson =
        JSONObject()
          .put(
            "cause",
            event.cause.name
          )
          .put(
            "state",
            event.state.name
          )
          .put(
    "score",
    event.score
)
.put(
    "confidence",
    event.confidence.name
)
.put(
    "recoveryDetected",
    event.recoveryDetected
)
.put(
    "gpsTrustScore",
    event.gpsTrustScore
        ?: JSONObject.NULL
)
.put(
    "gnssHealth",
    event.gnssHealth
        ?: JSONObject.NULL
)
.put(
    "guardianPositionAccepted",
    event.guardianPositionAccepted
        ?: JSONObject.NULL
)
          .put(
            "peakImpactG",
            event.peakImpactG
              ?: JSONObject.NULL
          )
          .put(
            "postImpactForceG",
            event.postImpactForceG
              ?: JSONObject.NULL
          )
          .put(
            "horizontalAfterImpact",
            event.horizontalAfterImpact
              ?: JSONObject.NULL
          )
          .put(
            "immobilityDurationSeconds",
            event.immobilityDurationSeconds
              ?: JSONObject.NULL
          )
          .put(
            "latitude",
            event.latitude
              ?: JSONObject.NULL
          )
          .put(
            "longitude",
            event.longitude
              ?: JSONObject.NULL
          )
          .put(
            "accuracy",
            event.accuracy
              ?: JSONObject.NULL
          )
          .put(
            "ptiTimestamp",
            event.createdAt
          )

      val eventJson =
        createEventJson(
          eventId = event.eventId,
          eventType = event.eventType,
          priority = event.priority,
          createdAt = event.createdAt,
          recordedAt = event.recordedAt,
          schemaVersion = event.schemaVersion,
          deviceJson = deviceJson,
          payloadJson = payloadJson
        )

      eventsArray.put(
        eventJson
      )

      encodedEventIds.add(
        event.eventId
      )
    }

    return createEncodedBatch(
      batchId = batchId,
      sentAt = sentAt,
      eventIds = encodedEventIds,
      eventsArray = eventsArray
    )
  }


  /**
   * Transforme un lot d'événements Voice SQLite en requête JSON.
   */
  fun encodeVoiceEvents(
    context: Context,
    config: GuardianNativeConfig,
    events: List<GuardianVoiceEvent>
  ): EncodedBatch {
    validateConfig(config)
    validateBatchSize(events.size)
    validateUniqueEventIds(events.map { it.eventId })

    val appContext=context.applicationContext
    val batchId=UUID.randomUUID().toString()
    val sentAt=System.currentTimeMillis()
    val appVersion=resolveAppVersion(appContext)
    val eventsArray=JSONArray()
    val encodedEventIds=ArrayList<String>(events.size)

    events.forEach { event ->
      validateVoiceEvent(config,event,sentAt)

      val deviceJson=createDeviceJson(
        config,event.childId,event.familyId,event.familyCode,event.deviceId,appVersion
      )

      val payloadJson=JSONObject()
        .put("cause",event.cause.name)
        .put("state",event.state.name)
        .put("confidenceScore",event.confidenceScore)
        .put("recognizedText",event.recognizedText ?: JSONObject.NULL)
        .put("wakePhrase",event.wakePhrase ?: JSONObject.NULL)
        .put("emergencyPhrase",event.emergencyPhrase ?: JSONObject.NULL)
        .put("latitude",event.latitude ?: JSONObject.NULL)
        .put("longitude",event.longitude ?: JSONObject.NULL)
        .put("accuracy",event.accuracy ?: JSONObject.NULL)
        .put("voiceTimestamp",event.createdAt)

      eventsArray.put(
        createEventJson(
          event.eventId,event.eventType,event.priority,
          event.createdAt,event.recordedAt,event.schemaVersion,
          deviceJson,payloadJson
        )
      )
      encodedEventIds.add(event.eventId)
    }

    return createEncodedBatch(
      batchId,sentAt,encodedEventIds,eventsArray
    )
  }


  /**
   * Transforme un lot d'événements SOS SQLite en requête JSON.
   *
   * Un événement SOS représente une demande d'assistance
   * manuelle déjà confirmée. Il est toujours critique et doit
   * pouvoir être encodé même lorsque la position ou la batterie
   * sont temporairement indisponibles.
   */
  fun encodeSosEvents(
    context: Context,
    config: GuardianNativeConfig,
    events: List<GuardianSosEvent>
  ): EncodedBatch {
    validateConfig(
      config
    )

    validateBatchSize(
      events.size
    )

    validateUniqueEventIds(
      eventIds =
        events.map {
          it.eventId
        }
    )

    val appContext =
      context.applicationContext

    val batchId =
      UUID.randomUUID().toString()

    val sentAt =
      System.currentTimeMillis()

    val appVersion =
      resolveAppVersion(
        appContext
      )

    val eventsArray =
      JSONArray()

    val encodedEventIds =
      ArrayList<String>(
        events.size
      )

    events.forEach { event ->
      validateSosEvent(
        config = config,
        event = event,
        sentAt = sentAt
      )

      val deviceJson =
        createDeviceJson(
          config = config,
          childId = event.childId,
          familyId = event.familyId,
          familyCode = event.familyCode,
          deviceId = event.deviceId,
          appVersion = appVersion
        )

      val payloadJson =
        JSONObject()
          .put(
            "latitude",
            event.latitude
              ?: JSONObject.NULL
          )
          .put(
            "longitude",
            event.longitude
              ?: JSONObject.NULL
          )
          .put(
            "accuracy",
            event.accuracy
              ?: JSONObject.NULL
          )
          .put(
            "batteryPercent",
            event.batteryPercent
              ?: JSONObject.NULL
          )
          .put(
            "sosTimestamp",
            event.createdAt
          )

      val eventJson =
        createEventJson(
          eventId = event.eventId,
          eventType = event.eventType,
          priority = event.priority,
          createdAt = event.createdAt,
          recordedAt = event.recordedAt,
          schemaVersion = event.schemaVersion,
          deviceJson = deviceJson,
          payloadJson = payloadJson
        )

      eventsArray.put(
        eventJson
      )

      encodedEventIds.add(
        event.eventId
      )
    }

    return createEncodedBatch(
      batchId = batchId,
      sentAt = sentAt,
      eventIds = encodedEventIds,
      eventsArray = eventsArray
    )
  }

  /**
 * Transforme un lot d'événements SERVICE SQLite
 * en requête JSON.
 */
fun encodeServiceEvents(
  context: Context,
  config: GuardianNativeConfig,
  events: List<GuardianServiceEvent>
): EncodedBatch {
  validateConfig(
    config
  )

  validateBatchSize(
    events.size
  )

  validateUniqueEventIds(
    eventIds =
      events.map {
        it.eventId
      }
  )

  val appContext =
    context.applicationContext

  val batchId =
    UUID.randomUUID().toString()

  val sentAt =
    System.currentTimeMillis()

  val appVersion =
    resolveAppVersion(
      appContext
    )

  val eventsArray =
    JSONArray()

  val encodedEventIds =
    ArrayList<String>(
      events.size
    )

  events.forEach { event ->
    validateServiceEvent(
      config = config,
      event = event,
      sentAt = sentAt
    )

    val deviceJson =
      createDeviceJson(
        config = config,
        childId = event.childId,
        familyId = event.familyId,
        familyCode = event.familyCode,
        deviceId = event.deviceId,
        appVersion = appVersion
      )

    val payloadJson =
      JSONObject()
        .put(
          "reason",
          event.reason
        )
        .put(
          "serviceTimestamp",
          event.createdAt
        )

    val eventJson =
      createEventJson(
        eventId = event.eventId,
        eventType = event.eventType,
        priority = event.priority,
        createdAt = event.createdAt,
        recordedAt = event.recordedAt,
        schemaVersion = event.schemaVersion,
        deviceJson = deviceJson,
        payloadJson = payloadJson
      )

    eventsArray.put(
      eventJson
    )

    encodedEventIds.add(
      event.eventId
    )
  }

  return createEncodedBatch(
    batchId = batchId,
    sentAt = sentAt,
    eventIds = encodedEventIds,
    eventsArray = eventsArray
  )
}

  fun encodeServiceHealthEvents(
  context: Context,
  config: GuardianNativeConfig,
  events: List<GuardianServiceHealthEvent>
): EncodedBatch {
  validateConfig(config)

  validateBatchSize(events.size)

  validateUniqueEventIds(
    events.map { it.eventId }
  )

  val appContext =
    context.applicationContext

  val batchId =
    UUID.randomUUID().toString()

  val sentAt =
    System.currentTimeMillis()

  val appVersion =
    resolveAppVersion(appContext)

  val eventsArray =
    JSONArray()

  val encodedEventIds =
    ArrayList<String>(events.size)

  events.forEach { event ->

    validateServiceHealthEvent(
      config = config,
      event = event,
      sentAt = sentAt
    )

    val deviceJson =
      createDeviceJson(
        config = config,
        childId = event.childId,
        familyId = event.familyId,
        familyCode = event.familyCode,
        deviceId = event.deviceId,
        appVersion = appVersion
      )

    val payloadJson =
  JSONObject()
    .put(
      "serviceRunning",
      event.serviceRunning
    )
    .put(
      "locationActive",
      event.locationActive
    )
    .put(
      "voiceActive",
      event.voiceActive
    )
    .put(
      "ptiActive",
      event.ptiActive
    )
    .put(
      "lastLocationAt",
      event.lastLocationAt
        ?: JSONObject.NULL
    )
    .put(
      "lastAudioAt",
      event.lastAudioAt
        ?: JSONObject.NULL
    )
    .put(
      "lastSensorAt",
      event.lastSensorAt
        ?: JSONObject.NULL
    )
    .put(
      "lastError",
      event.lastError
        ?: JSONObject.NULL
    )
    .put(
  "healthTimestamp",
  event.recordedAt
)

    val eventJson =
      createEventJson(
        eventId = event.eventId,
        eventType = event.eventType,
        priority = event.priority,
        createdAt = event.createdAt,
        recordedAt = event.recordedAt,
        schemaVersion = event.schemaVersion,
        deviceJson = deviceJson,
        payloadJson = payloadJson
      )

    eventsArray.put(eventJson)

    encodedEventIds.add(
      event.eventId
    )
  }

  return createEncodedBatch(
    batchId = batchId,
    sentAt = sentAt,
    eventIds = encodedEventIds,
    eventsArray = eventsArray
  )
}

  private fun createDeviceJson(
    config: GuardianNativeConfig,
    childId: String,
    familyId: String,
    familyCode: String,
    deviceId: String,
    appVersion: String?
  ): JSONObject {
    return JSONObject()
      .put(
        "childId",
        childId
      )
      .put(
        "childName",
        config.childName
      )
      .put(
        "familyId",
        familyId
      )
      .put(
        "familyCode",
        familyCode
      )
      .put(
        "deviceId",
        deviceId
      )
      .put(
        "appVersion",
        appVersion
          ?: JSONObject.NULL
      )
      .put(
        "nativeCoreVersion",
        NATIVE_CORE_VERSION
      )
  }

  private fun createEventJson(
    eventId: String,
    eventType: GuardianEventType,
    priority: GuardianEventPriority,
    createdAt: Long,
    recordedAt: Long,
    schemaVersion: Int,
    deviceJson: JSONObject,
    payloadJson: JSONObject
  ): JSONObject {
    return JSONObject()
      .put(
        "eventId",
        eventId
      )
      .put(
        "eventType",
        eventType.name
      )
      .put(
        "priority",
        priority.name
          .lowercase(
            Locale.ROOT
          )
      )
      .put(
        "createdAt",
        createdAt
      )
      .put(
        "recordedAt",
        recordedAt
      )
      .put(
        "device",
        deviceJson
      )
      .put(
        "payload",
        payloadJson
      )
      .put(
        "schemaVersion",
        schemaVersion
      )
  }

  private fun createEncodedBatch(
    batchId: String,
    sentAt: Long,
    eventIds: List<String>,
    eventsArray: JSONArray
  ): EncodedBatch {
    val requestJson =
      JSONObject()
        .put(
          "batchId",
          batchId
        )
        .put(
          "sentAt",
          sentAt
        )
        .put(
          "events",
          eventsArray
        )

    return EncodedBatch(
      batchId =
        batchId,

      eventIds =
        eventIds.toList(),

      json =
        requestJson.toString()
    )
  }

  private fun validateConfig(
    config: GuardianNativeConfig
  ) {
    require(
      config.childId.isNotBlank()
    ) {
      "Configuration Guardian : childId absent"
    }

    require(
      config.familyId.isNotBlank()
    ) {
      "Configuration Guardian : familyId absent"
    }

    require(
      config.familyCode.isNotBlank()
    ) {
      "Configuration Guardian : familyCode absent"
    }

    require(
      config.deviceId.isNotBlank()
    ) {
      "Configuration Guardian : deviceId absent"
    }

    require(
      config.childName.isNotBlank()
    ) {
      "Configuration Guardian : childName absent"
    }
  }

  private fun validateBatchSize(
    eventCount: Int
  ) {
    require(
      eventCount > 0
    ) {
      "Impossible d'encoder un lot vide"
    }

    require(
      eventCount <= MAX_BATCH_SIZE
    ) {
      "Lot Guardian trop volumineux : " +
        "$eventCount/$MAX_BATCH_SIZE"
    }
  }

  private fun validateUniqueEventIds(
    eventIds: List<String>
  ) {
    val uniqueEventIds =
      HashSet<String>(
        eventIds.size
      )

    eventIds.forEach { eventId ->
      require(
        eventId.isNotBlank()
      ) {
        "Un événement Guardian possède un eventId vide"
      }

      require(
        uniqueEventIds.add(
          eventId
        )
      ) {
        "eventId dupliqué dans le lot : $eventId"
      }
    }
  }

  private fun validateLocationEvent(
    config: GuardianNativeConfig,
    event: GuardianLocationEvent,
    sentAt: Long
  ) {
    validateEventIdentity(
      config = config,
      eventId = event.eventId,
      childId = event.childId,
      familyId = event.familyId,
      familyCode = event.familyCode,
      deviceId = event.deviceId
    )

    require(
      event.latitude.isFinite() &&
        event.latitude in -90.0..90.0
    ) {
      "Latitude invalide pour l'événement " +
        event.eventId
    }

    require(
      event.longitude.isFinite() &&
        event.longitude in -180.0..180.0
    ) {
      "Longitude invalide pour l'événement " +
        event.eventId
    }

    require(
      event.accuracy == null ||
        (
          event.accuracy.isFinite() &&
            event.accuracy >= 0f
          )
    ) {
      "Précision invalide pour l'événement " +
        event.eventId
    }

    require(
      event.speedMps == null ||
        (
          event.speedMps.isFinite() &&
            event.speedMps >= 0f
          )
    ) {
      "Vitesse invalide pour l'événement " +
        event.eventId
    }

    require(
      event.altitude == null ||
        event.altitude.isFinite()
    ) {
      "Altitude invalide pour l'événement " +
        event.eventId
    }

    validateEventTimes(
      eventId = event.eventId,
      createdAt = event.createdAt,
      recordedAt = event.recordedAt,
      sentAt = sentAt,
      schemaVersion = event.schemaVersion
    )
  }

  private fun validatePtiEvent(
    config: GuardianNativeConfig,
    event: GuardianPtiEvent,
    sentAt: Long
  ) {
    validateEventIdentity(
      config = config,
      eventId = event.eventId,
      childId = event.childId,
      familyId = event.familyId,
      familyCode = event.familyCode,
      deviceId = event.deviceId
    )

    require(
      event.score in 0..100
    ) {
      "Score PTI invalide pour l'événement " +
        event.eventId
    }
    
    require(
    event.gpsTrustScore == null ||
        event.gpsTrustScore in 0..100
) {
    "gpsTrustScore PTI invalide pour l'événement " +
        event.eventId
}

require(
    event.gnssHealth == null ||
        event.gnssHealth in 0..100
) {
    "gnssHealth PTI invalide pour l'événement " +
        event.eventId
}

    require(
      event.peakImpactG == null ||
        (
          event.peakImpactG.isFinite() &&
            event.peakImpactG >= 0f
          )
    ) {
      "Impact PTI invalide pour l'événement " +
        event.eventId
    }

    require(
      event.postImpactForceG == null ||
        (
          event.postImpactForceG.isFinite() &&
            event.postImpactForceG >= 0f
          )
    ) {
      "Force post-impact PTI invalide pour l'événement " +
        event.eventId
    }

    require(
      event.immobilityDurationSeconds == null ||
        event.immobilityDurationSeconds >= 0
    ) {
      "Durée d'immobilité PTI invalide pour l'événement " +
        event.eventId
    }

    require(
      (event.latitude == null) ==
        (event.longitude == null)
    ) {
      "Position PTI incomplète pour l'événement " +
        event.eventId
    }

    require(
      event.latitude == null ||
        (
          event.latitude.isFinite() &&
            event.latitude in -90.0..90.0
          )
    ) {
      "Latitude PTI invalide pour l'événement " +
        event.eventId
    }

    require(
      event.longitude == null ||
        (
          event.longitude.isFinite() &&
            event.longitude in -180.0..180.0
          )
    ) {
      "Longitude PTI invalide pour l'événement " +
        event.eventId
    }

    require(
      event.accuracy == null ||
        (
          event.accuracy.isFinite() &&
            event.accuracy >= 0f
          )
    ) {
      "Précision PTI invalide pour l'événement " +
        event.eventId
    }

    validateEventTimes(
      eventId = event.eventId,
      createdAt = event.createdAt,
      recordedAt = event.recordedAt,
      sentAt = sentAt,
      schemaVersion = event.schemaVersion
    )
  }


  private fun validateVoiceEvent(
    config: GuardianNativeConfig,
    event: GuardianVoiceEvent,
    sentAt: Long
  ) {
    validateEventIdentity(
      config,
      event.eventId,
      event.childId,
      event.familyId,
      event.familyCode,
      event.deviceId
    )

    require(event.confidenceScore in 0..100){
      "Score Voice invalide pour ${event.eventId}"
    }

    validateEventTimes(
      event.eventId,
      event.createdAt,
      event.recordedAt,
      sentAt,
      event.schemaVersion
    )
  }


  private fun validateSosEvent(
    config: GuardianNativeConfig,
    event: GuardianSosEvent,
    sentAt: Long
  ) {
    validateEventIdentity(
      config = config,
      eventId = event.eventId,
      childId = event.childId,
      familyId = event.familyId,
      familyCode = event.familyCode,
      deviceId = event.deviceId
    )

    require(
      event.eventType ==
        GuardianEventType.SOS
    ) {
      "Type invalide pour l'événement SOS " +
        event.eventId
    }

    require(
      event.priority ==
        GuardianEventPriority.CRITICAL
    ) {
      "Priorité non critique pour l'événement SOS " +
        event.eventId
    }

    require(
      (event.latitude == null) ==
        (event.longitude == null)
    ) {
      "Position SOS incomplète pour l'événement " +
        event.eventId
    }

    require(
      event.latitude == null ||
        (
          event.latitude.isFinite() &&
            event.latitude in -90.0..90.0
          )
    ) {
      "Latitude SOS invalide pour l'événement " +
        event.eventId
    }

    require(
      event.longitude == null ||
        (
          event.longitude.isFinite() &&
            event.longitude in -180.0..180.0
          )
    ) {
      "Longitude SOS invalide pour l'événement " +
        event.eventId
    }

    require(
      event.accuracy == null ||
        (
          event.accuracy.isFinite() &&
            event.accuracy >= 0f
          )
    ) {
      "Précision SOS invalide pour l'événement " +
        event.eventId
    }

    require(
      event.accuracy == null ||
        event.latitude != null
    ) {
      "Précision SOS sans position pour l'événement " +
        event.eventId
    }

    require(
      event.batteryPercent == null ||
        event.batteryPercent in 0..100
    ) {
      "Batterie SOS invalide pour l'événement " +
        event.eventId
    }

    validateEventTimes(
      eventId = event.eventId,
      createdAt = event.createdAt,
      recordedAt = event.recordedAt,
      sentAt = sentAt,
      schemaVersion = event.schemaVersion
    )
  }

  private fun validateServiceEvent(
  config: GuardianNativeConfig,
  event: GuardianServiceEvent,
  sentAt: Long
) {
  validateEventIdentity(
    config = config,
    eventId = event.eventId,
    childId = event.childId,
    familyId = event.familyId,
    familyCode = event.familyCode,
    deviceId = event.deviceId
  )

  require(
    event.eventType ==
      GuardianEventType.TRACKING_STOPPED
  ) {
    "Type invalide pour l'événement SERVICE " +
      event.eventId
  }

  require(
    event.priority ==
      GuardianEventPriority.HIGH
  ) {
    "Priorité invalide pour l'événement SERVICE " +
      event.eventId
  }

  require(
    event.reason.isNotBlank()
  ) {
    "Raison vide pour l'événement SERVICE " +
      event.eventId
  }

  validateEventTimes(
    eventId = event.eventId,
    createdAt = event.createdAt,
    recordedAt = event.recordedAt,
    sentAt = sentAt,
    schemaVersion = event.schemaVersion
  )
}

  private fun validateServiceHealthEvent(
  config: GuardianNativeConfig,
  event: GuardianServiceHealthEvent,
  sentAt: Long
) {
  validateEventIdentity(
    config = config,
    eventId = event.eventId,
    childId = event.childId,
    familyId = event.familyId,
    familyCode = event.familyCode,
    deviceId = event.deviceId
  )

  require(
    event.eventType ==
      GuardianEventType.SERVICE_HEALTH
  ) {
    "Type invalide pour l'événement SERVICE_HEALTH ${event.eventId}"
  }

  validateEventTimes(
    eventId = event.eventId,
    createdAt = event.createdAt,
    recordedAt = event.recordedAt,
    sentAt = sentAt,
    schemaVersion = event.schemaVersion
  )
}

  private fun validateEventTimes(
    eventId: String,
    createdAt: Long,
    recordedAt: Long,
    sentAt: Long,
    schemaVersion: Int
  ) {
    require(
      createdAt > 0L
    ) {
      "createdAt invalide pour l'événement $eventId"
    }

    require(
      recordedAt > 0L
    ) {
      "recordedAt invalide pour l'événement $eventId"
    }

    require(
      createdAt <=
        sentAt + TimeConstants.ALLOWED_CLOCK_DRIFT_MS
    ) {
      "createdAt trop éloigné dans le futur pour $eventId"
    }

    require(
      recordedAt <=
        sentAt + TimeConstants.ALLOWED_CLOCK_DRIFT_MS
    ) {
      "recordedAt trop éloigné dans le futur pour $eventId"
    }

    require(
      recordedAt >=
        createdAt -
          TimeConstants.ALLOWED_CLOCK_DRIFT_MS
    ) {
      "Chronologie incohérente pour l'événement $eventId"
    }

    require(
      schemaVersion ==
        SCHEMA_VERSION
    ) {
      "Version de schéma non supportée : $schemaVersion"
    }
  }

  private fun validateEventIdentity(
    config: GuardianNativeConfig,
    eventId: String,
    childId: String,
    familyId: String,
    familyCode: String,
    deviceId: String
  ) {
    require(
      childId.isNotBlank() &&
        familyId.isNotBlank() &&
        familyCode.isNotBlank() &&
        deviceId.isNotBlank()
    ) {
      "Identité incomplète pour l'événement $eventId"
    }

    val identityMatches =
      childId ==
        config.childId &&
        familyId ==
        config.familyId &&
        familyCode ==
        config.familyCode &&
        deviceId ==
        config.deviceId

    require(
      identityMatches
    ) {
      "Identité incohérente pour l'événement $eventId"
    }
  }

  @Suppress("DEPRECATION")
  private fun resolveAppVersion(
    context: Context
  ): String? {
    return try {
      val packageInfo =
        context.packageManager
          .getPackageInfo(
            context.packageName,
            0
          )

      packageInfo.versionName
        ?.trim()
        ?.takeIf {
          it.isNotBlank()
        }
    } catch (_: Exception) {
      null
    }
  }

  private object TimeConstants {
    const val ALLOWED_CLOCK_DRIFT_MS =
      5 * 60 * 1000L
  }
}