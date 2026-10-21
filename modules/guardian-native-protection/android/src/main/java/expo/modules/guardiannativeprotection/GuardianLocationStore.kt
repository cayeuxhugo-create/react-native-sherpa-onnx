package expo.modules.guardiannativeprotection

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

class GuardianLocationStore(
  context: Context
) : SQLiteOpenHelper(
  context.applicationContext,
  DATABASE_NAME,
  null,
  DATABASE_VERSION
) {

  companion object {
    private const val TAG =
      "GuardianLocationStore"

    private const val DATABASE_NAME =
      "guardian_native_core.db"

    private const val DATABASE_VERSION =
  7

    private const val TABLE_LOCATIONS =
  "guardian_locations"

private const val TABLE_PTI_EVENTS =
  "guardian_pti_events"

private const val TABLE_VOICE_EVENTS =
  "guardian_voice_events"

private const val TABLE_SOS_EVENTS =
  "guardian_sos_events"

private const val TABLE_SERVICE_EVENTS =
  "guardian_service_events"

private const val TABLE_SERVICE_HEALTH_EVENTS =
  "guardian_service_health_events"



    /*
     * Limite de sécurité par table.
     *
     * Les événements non envoyés ne sont jamais supprimés.
     * Le nettoyage concerne uniquement les événements
     * déjà confirmés par le serveur.
     */
    private const val MAX_STORED_EVENTS =
      100_000

    /*
     * Marge conservée après nettoyage.
     */
    private const val TARGET_STORED_EVENTS =
      90_000

    /*
     * SQLite accepte un nombre limité de paramètres
     * dans une requête.
     */
    private const val SQL_BATCH_SIZE =
      500
  }

  override fun onCreate(
    database: SQLiteDatabase
  ) {
    createLocationTable(
      database
    )

    createPtiTable(
      database
    )

    createVoiceTable(
      database
    )

    createSosTable(
      database
    )

    createServiceTable(
  database
   )

    createServiceHealthTable(
  database
   )

    createLocationIndexes(
      database
    )

    createPtiIndexes(
      database
    )

    createVoiceIndexes(
      database
    )

    createSosIndexes(
      database
    )

    createServiceIndexes(
  database
  )

createServiceHealthIndexes(
  database
)
  }

  override fun onUpgrade(
    database: SQLiteDatabase,
    oldVersion: Int,
    newVersion: Int
  ) {
    if (oldVersion < 2) {
      migrateLocationsToVersion2(
        database
      )
    }

    if (oldVersion < 3) {
      database.beginTransaction()

      try {
        createPtiTable(
          database
        )

        createPtiIndexes(
          database
        )

        database.setTransactionSuccessful()
      } finally {
        database.endTransaction()
      }
    }

    if (oldVersion < 4) {
      database.beginTransaction()

      try {
        createVoiceTable(
          database
        )

        createVoiceIndexes(
          database
        )

        database.setTransactionSuccessful()
      } finally {
        database.endTransaction()
      }
    }

    if (oldVersion < 5) {
      database.beginTransaction()

      try {
        createSosTable(
          database
        )

        createSosIndexes(
          database
        )

        database.setTransactionSuccessful()
      } finally {
        database.endTransaction()
      }
    }

    if (oldVersion < 6) {
  database.beginTransaction()

  try {
    createServiceTable(
      database
    )

    createServiceIndexes(
      database
    )

    database.setTransactionSuccessful()
  } finally {
    database.endTransaction()
  }
 }
 

if (oldVersion < 7) {
  database.beginTransaction()

  try {
    createServiceHealthTable(
      database
    )

    createServiceHealthIndexes(
      database
    )

    database.setTransactionSuccessful()
  } finally {
    database.endTransaction()
  }
}
}

  override fun onConfigure(
  database: SQLiteDatabase
) {
  super.onConfigure(
    database
  )

  database.rawQuery(
    "PRAGMA busy_timeout = 5000",
    null
  ).use { cursor ->
    /*
     * Certains PRAGMA Android sont considérés
     * comme des requêtes et ne peuvent donc pas
     * être exécutés avec execSQL().
     *
     * Le déplacement du curseur force SQLite
     * à appliquer et lire la valeur configurée.
     */
    cursor.moveToFirst()
  }
}

  private fun createLocationTable(
    database: SQLiteDatabase
  ) {
    database.execSQL(
      """
      CREATE TABLE IF NOT EXISTS $TABLE_LOCATIONS (
        id TEXT PRIMARY KEY,

        event_type TEXT NOT NULL,
        priority TEXT NOT NULL,
        schema_version INTEGER NOT NULL,

        child_id TEXT NOT NULL,
        family_id TEXT NOT NULL,
        family_code TEXT NOT NULL,
        device_id TEXT NOT NULL,

        latitude REAL NOT NULL,
        longitude REAL NOT NULL,
        accuracy REAL,
        speed_mps REAL,
        altitude REAL,

        created_at INTEGER NOT NULL,
        recorded_at INTEGER NOT NULL,

        uploaded INTEGER NOT NULL DEFAULT 0
      )
      """.trimIndent()
    )
  }

  private fun createPtiTable(
    database: SQLiteDatabase
  ) {
    database.execSQL(
      """
      CREATE TABLE IF NOT EXISTS $TABLE_PTI_EVENTS (
        id TEXT PRIMARY KEY,

        event_type TEXT NOT NULL,
        priority TEXT NOT NULL,
        schema_version INTEGER NOT NULL,

        child_id TEXT NOT NULL,
        family_id TEXT NOT NULL,
        family_code TEXT NOT NULL,
        device_id TEXT NOT NULL,

        cause TEXT NOT NULL,
        state TEXT NOT NULL,
        score INTEGER NOT NULL,

        peak_impact_g REAL,
        post_impact_force_g REAL,
        horizontal_after_impact INTEGER,
        immobility_duration_seconds INTEGER,

        latitude REAL,
        longitude REAL,
        accuracy REAL,

        created_at INTEGER NOT NULL,
        recorded_at INTEGER NOT NULL,

        uploaded INTEGER NOT NULL DEFAULT 0
      )
      """.trimIndent()
    )
  }


  private fun createVoiceTable(
    database: SQLiteDatabase
  ) {
    database.execSQL(
      """
      CREATE TABLE IF NOT EXISTS $TABLE_VOICE_EVENTS (
        id TEXT PRIMARY KEY,

        event_type TEXT NOT NULL,
        priority TEXT NOT NULL,
        schema_version INTEGER NOT NULL,

        child_id TEXT NOT NULL,
        family_id TEXT NOT NULL,
        family_code TEXT NOT NULL,
        device_id TEXT NOT NULL,

        cause TEXT NOT NULL,
        state TEXT NOT NULL,
        confidence_score INTEGER NOT NULL,

        recognized_text TEXT,
        wake_phrase TEXT,
        emergency_phrase TEXT,

        latitude REAL,
        longitude REAL,
        accuracy REAL,

        created_at INTEGER NOT NULL,
        recorded_at INTEGER NOT NULL,

        uploaded INTEGER NOT NULL DEFAULT 0
      )
      """.trimIndent()
    )
  }

  private fun createSosTable(
    database: SQLiteDatabase
  ) {
    database.execSQL(
      """
      CREATE TABLE IF NOT EXISTS $TABLE_SOS_EVENTS (
        id TEXT PRIMARY KEY,

        event_type TEXT NOT NULL,
        priority TEXT NOT NULL,
        schema_version INTEGER NOT NULL,

        child_id TEXT NOT NULL,
        family_id TEXT NOT NULL,
        family_code TEXT NOT NULL,
        device_id TEXT NOT NULL,

        latitude REAL,
        longitude REAL,
        accuracy REAL,
        battery_percent INTEGER,

        created_at INTEGER NOT NULL,
        recorded_at INTEGER NOT NULL,

        uploaded INTEGER NOT NULL DEFAULT 0
      )
      """.trimIndent()
    )
  }

  private fun createServiceTable(
  database: SQLiteDatabase
) {
  database.execSQL(
    """
    CREATE TABLE IF NOT EXISTS $TABLE_SERVICE_EVENTS (
      id TEXT PRIMARY KEY,

      event_type TEXT NOT NULL,
      priority TEXT NOT NULL,
      schema_version INTEGER NOT NULL,

      child_id TEXT NOT NULL,
      family_id TEXT NOT NULL,
      family_code TEXT NOT NULL,
      device_id TEXT NOT NULL,

      reason TEXT NOT NULL,

      created_at INTEGER NOT NULL,
      recorded_at INTEGER NOT NULL,

      uploaded INTEGER NOT NULL DEFAULT 0
    )
    """.trimIndent()
  )
}

private fun createServiceHealthTable(
  database: SQLiteDatabase
) {
  database.execSQL(
    """
    CREATE TABLE IF NOT EXISTS $TABLE_SERVICE_HEALTH_EVENTS (
      id TEXT PRIMARY KEY,

      event_type TEXT NOT NULL,
      priority TEXT NOT NULL,
      schema_version INTEGER NOT NULL,

      child_id TEXT NOT NULL,
      family_id TEXT NOT NULL,
      family_code TEXT NOT NULL,
      device_id TEXT NOT NULL,

      service_running INTEGER NOT NULL,
      location_active INTEGER NOT NULL,
      voice_active INTEGER NOT NULL,
      pti_active INTEGER NOT NULL,

      last_location_at INTEGER,
      last_audio_at INTEGER,
      last_sensor_at INTEGER,

      last_error TEXT,

      created_at INTEGER NOT NULL,
      recorded_at INTEGER NOT NULL,

      uploaded INTEGER NOT NULL DEFAULT 0
    )
    """.trimIndent()
  )
}

  private fun migrateLocationsToVersion2(
    database: SQLiteDatabase
  ) {
    database.beginTransaction()

    try {
      database.execSQL(
        """
        ALTER TABLE $TABLE_LOCATIONS
        ADD COLUMN event_type TEXT
        NOT NULL DEFAULT 'LOCATION'
        """.trimIndent()
      )

      database.execSQL(
        """
        ALTER TABLE $TABLE_LOCATIONS
        ADD COLUMN priority TEXT
        NOT NULL DEFAULT 'NORMAL'
        """.trimIndent()
      )

      database.execSQL(
        """
        ALTER TABLE $TABLE_LOCATIONS
        ADD COLUMN schema_version INTEGER
        NOT NULL DEFAULT 1
        """.trimIndent()
      )

      database.execSQL(
        """
        ALTER TABLE $TABLE_LOCATIONS
        ADD COLUMN child_id TEXT
        NOT NULL DEFAULT ''
        """.trimIndent()
      )

      database.execSQL(
        """
        ALTER TABLE $TABLE_LOCATIONS
        ADD COLUMN family_id TEXT
        NOT NULL DEFAULT ''
        """.trimIndent()
      )

      database.execSQL(
        """
        ALTER TABLE $TABLE_LOCATIONS
        ADD COLUMN family_code TEXT
        NOT NULL DEFAULT ''
        """.trimIndent()
      )

      database.execSQL(
        """
        ALTER TABLE $TABLE_LOCATIONS
        ADD COLUMN device_id TEXT
        NOT NULL DEFAULT ''
        """.trimIndent()
      )

      database.execSQL(
        """
        ALTER TABLE $TABLE_LOCATIONS
        ADD COLUMN created_at INTEGER
        NOT NULL DEFAULT 0
        """.trimIndent()
      )

      database.execSQL(
        """
        UPDATE $TABLE_LOCATIONS
        SET
          created_at = location_timestamp,
          uploaded = 1
        WHERE child_id = ''
           OR family_id = ''
           OR device_id = ''
        """.trimIndent()
      )

      createLocationIndexes(
        database
      )

      database.setTransactionSuccessful()
    } finally {
      database.endTransaction()
    }
  }

  private fun createLocationIndexes(
    database: SQLiteDatabase
  ) {
    database.execSQL(
      """
      CREATE INDEX IF NOT EXISTS
      index_guardian_locations_upload
      ON $TABLE_LOCATIONS(
        uploaded,
        recorded_at
      )
      """.trimIndent()
    )

    database.execSQL(
      """
      CREATE INDEX IF NOT EXISTS
      index_guardian_locations_device
      ON $TABLE_LOCATIONS(
        device_id,
        created_at
      )
      """.trimIndent()
    )
  }

  private fun createPtiIndexes(
    database: SQLiteDatabase
  ) {
    database.execSQL(
      """
      CREATE INDEX IF NOT EXISTS
      index_guardian_pti_upload
      ON $TABLE_PTI_EVENTS(
        uploaded,
        recorded_at
      )
      """.trimIndent()
    )

    database.execSQL(
      """
      CREATE INDEX IF NOT EXISTS
      index_guardian_pti_device
      ON $TABLE_PTI_EVENTS(
        device_id,
        created_at
      )
      """.trimIndent()
    )

    database.execSQL(
      """
      CREATE INDEX IF NOT EXISTS
      index_guardian_pti_state
      ON $TABLE_PTI_EVENTS(
        state,
        created_at
      )
      """.trimIndent()
    )
  }


  private fun createVoiceIndexes(
    database: SQLiteDatabase
  ) {
    database.execSQL(
      """
      CREATE INDEX IF NOT EXISTS
      index_guardian_voice_upload
      ON $TABLE_VOICE_EVENTS(
        uploaded,
        recorded_at
      )
      """.trimIndent()
    )

    database.execSQL(
      """
      CREATE INDEX IF NOT EXISTS
      index_guardian_voice_device
      ON $TABLE_VOICE_EVENTS(
        device_id,
        created_at
      )
      """.trimIndent()
    )

    database.execSQL(
      """
      CREATE INDEX IF NOT EXISTS
      index_guardian_voice_state
      ON $TABLE_VOICE_EVENTS(
        state,
        created_at
      )
      """.trimIndent()
    )
  }

  private fun createSosIndexes(
    database: SQLiteDatabase
  ) {
    database.execSQL(
      """
      CREATE INDEX IF NOT EXISTS
      index_guardian_sos_upload
      ON $TABLE_SOS_EVENTS(
        uploaded,
        recorded_at
      )
      """.trimIndent()
    )

    database.execSQL(
      """
      CREATE INDEX IF NOT EXISTS
      index_guardian_sos_device
      ON $TABLE_SOS_EVENTS(
        device_id,
        created_at
      )
      """.trimIndent()
    )
  }

  private fun createServiceIndexes(
  database: SQLiteDatabase
) {
  database.execSQL(
    """
    CREATE INDEX IF NOT EXISTS
    index_guardian_service_upload
    ON $TABLE_SERVICE_EVENTS(
      uploaded,
      recorded_at
    )
    """.trimIndent()
  )

  database.execSQL(
    """
    CREATE INDEX IF NOT EXISTS
    index_guardian_service_device
    ON $TABLE_SERVICE_EVENTS(
      device_id,
      created_at
    )
    """.trimIndent()
  )
}

  private fun createServiceHealthIndexes(
  database: SQLiteDatabase
) {
  database.execSQL(
    """
    CREATE INDEX IF NOT EXISTS
    index_guardian_service_health_upload
    ON $TABLE_SERVICE_HEALTH_EVENTS(
      uploaded,
      recorded_at
    )
    """.trimIndent()
  )

  database.execSQL(
    """
    CREATE INDEX IF NOT EXISTS
    index_guardian_service_health_device
    ON $TABLE_SERVICE_HEALTH_EVENTS(
      device_id,
      created_at
    )
    """.trimIndent()
  )
}

  @Synchronized
  fun saveLocationEvent(
    event: GuardianLocationEvent
  ) {
    validateLocationEvent(
      event
    )

    val values =
      ContentValues().apply {
        put("id", event.eventId)
        put("event_type", event.eventType.name)
        put("priority", event.priority.name)
        put("schema_version", event.schemaVersion)

        put("child_id", event.childId)
        put("family_id", event.familyId)
        put("family_code", event.familyCode)
        put("device_id", event.deviceId)

        put("latitude", event.latitude)
        put("longitude", event.longitude)

        if (event.accuracy != null) {
          put("accuracy", event.accuracy)
        } else {
          putNull("accuracy")
        }

        if (event.speedMps != null) {
          put("speed_mps", event.speedMps)
        } else {
          putNull("speed_mps")
        }

        if (event.altitude != null) {
          put("altitude", event.altitude)
        } else {
          putNull("altitude")
        }

        put("created_at", event.createdAt)
        put("recorded_at", event.recordedAt)
        put("uploaded", 0)
      }

    insertEvent(
      tableName = TABLE_LOCATIONS,
      eventId = event.eventId,
      values = values,
      eventLabel = "GPS"
    )
  }

  @Synchronized
  fun savePtiEvent(
    event: GuardianPtiEvent
  ) {
    validatePtiEvent(
      event
    )

    val values =
      ContentValues().apply {
        put("id", event.eventId)
        put("event_type", event.eventType.name)
        put("priority", event.priority.name)
        put("schema_version", event.schemaVersion)

        put("child_id", event.childId)
        put("family_id", event.familyId)
        put("family_code", event.familyCode)
        put("device_id", event.deviceId)

        put("cause", event.cause.name)
        put("state", event.state.name)
        put("score", event.score)

        if (event.peakImpactG != null) {
          put("peak_impact_g", event.peakImpactG)
        } else {
          putNull("peak_impact_g")
        }

        if (event.postImpactForceG != null) {
          put(
            "post_impact_force_g",
            event.postImpactForceG
          )
        } else {
          putNull(
            "post_impact_force_g"
          )
        }

        if (event.horizontalAfterImpact != null) {
          put(
            "horizontal_after_impact",
            if (event.horizontalAfterImpact) {
              1
            } else {
              0
            }
          )
        } else {
          putNull(
            "horizontal_after_impact"
          )
        }

        if (
          event.immobilityDurationSeconds != null
        ) {
          put(
            "immobility_duration_seconds",
            event.immobilityDurationSeconds
          )
        } else {
          putNull(
            "immobility_duration_seconds"
          )
        }

        if (event.latitude != null) {
          put("latitude", event.latitude)
        } else {
          putNull("latitude")
        }

        if (event.longitude != null) {
          put("longitude", event.longitude)
        } else {
          putNull("longitude")
        }

        if (event.accuracy != null) {
          put("accuracy", event.accuracy)
        } else {
          putNull("accuracy")
        }

        put("created_at", event.createdAt)
        put("recorded_at", event.recordedAt)
        put("uploaded", 0)
      }

    insertEvent(
      tableName = TABLE_PTI_EVENTS,
      eventId = event.eventId,
      values = values,
      eventLabel = "PTI"
    )
  }


  @Synchronized
  fun saveVoiceEvent(
    event: GuardianVoiceEvent
  ) {
    validateVoiceEvent(
      event
    )

    val values =
      ContentValues().apply {
        put("id", event.eventId)
        put("event_type", event.eventType.name)
        put("priority", event.priority.name)
        put("schema_version", event.schemaVersion)

        put("child_id", event.childId)
        put("family_id", event.familyId)
        put("family_code", event.familyCode)
        put("device_id", event.deviceId)

        put("cause", event.cause.name)
        put("state", event.state.name)
        put(
          "confidence_score",
          event.confidenceScore
        )

        if (event.recognizedText != null) {
          put(
            "recognized_text",
            event.recognizedText
          )
        } else {
          putNull(
            "recognized_text"
          )
        }

        if (event.wakePhrase != null) {
          put(
            "wake_phrase",
            event.wakePhrase
          )
        } else {
          putNull(
            "wake_phrase"
          )
        }

        if (event.emergencyPhrase != null) {
          put(
            "emergency_phrase",
            event.emergencyPhrase
          )
        } else {
          putNull(
            "emergency_phrase"
          )
        }

        if (event.latitude != null) {
          put("latitude", event.latitude)
        } else {
          putNull("latitude")
        }

        if (event.longitude != null) {
          put("longitude", event.longitude)
        } else {
          putNull("longitude")
        }

        if (event.accuracy != null) {
          put("accuracy", event.accuracy)
        } else {
          putNull("accuracy")
        }

        put("created_at", event.createdAt)
        put("recorded_at", event.recordedAt)
        put("uploaded", 0)
      }

    insertEvent(
      tableName = TABLE_VOICE_EVENTS,
      eventId = event.eventId,
      values = values,
      eventLabel = "VOICE"
    )
  }

  @Synchronized
  fun saveSosEvent(
    event: GuardianSosEvent
  ) {
    validateSosEvent(
      event
    )

    val values =
      ContentValues().apply {
        put("id", event.eventId)
        put("event_type", event.eventType.name)
        put("priority", event.priority.name)
        put("schema_version", event.schemaVersion)

        put("child_id", event.childId)
        put("family_id", event.familyId)
        put("family_code", event.familyCode)
        put("device_id", event.deviceId)

        if (event.latitude != null) {
          put("latitude", event.latitude)
        } else {
          putNull("latitude")
        }

        if (event.longitude != null) {
          put("longitude", event.longitude)
        } else {
          putNull("longitude")
        }

        if (event.accuracy != null) {
          put("accuracy", event.accuracy)
        } else {
          putNull("accuracy")
        }

        if (event.batteryPercent != null) {
          put(
            "battery_percent",
            event.batteryPercent
          )
        } else {
          putNull(
            "battery_percent"
          )
        }

        put("created_at", event.createdAt)
        put("recorded_at", event.recordedAt)
        put("uploaded", 0)
      }

    insertEvent(
      tableName = TABLE_SOS_EVENTS,
      eventId = event.eventId,
      values = values,
      eventLabel = "SOS"
    )

  }

  @Synchronized
fun saveServiceEvent(
  event: GuardianServiceEvent
) {
  validateServiceEvent(
    event
  )

  val values =
    ContentValues().apply {
      put("id", event.eventId)
      put("event_type", event.eventType.name)
      put("priority", event.priority.name)
      put("schema_version", event.schemaVersion)

      put("child_id", event.childId)
      put("family_id", event.familyId)
      put("family_code", event.familyCode)
      put("device_id", event.deviceId)

      put("reason", event.reason)

      put("created_at", event.createdAt)
      put("recorded_at", event.recordedAt)
      put("uploaded", 0)
    }

  insertEvent(
    tableName = TABLE_SERVICE_EVENTS,
    eventId = event.eventId,
    values = values,
    eventLabel = "SERVICE"
  )
}

  @Synchronized
fun saveServiceHealthEvent(
  event: GuardianServiceHealthEvent
) {
  validateServiceHealthEvent(
  event
   )

  val values =
    ContentValues().apply {
      put("id", event.eventId)
      put("event_type", event.eventType.name)
      put("priority", event.priority.name)
      put("schema_version", event.schemaVersion)

      put("child_id", event.childId)
      put("family_id", event.familyId)
      put("family_code", event.familyCode)
      put("device_id", event.deviceId)

      put(
        "service_running",
        if (event.serviceRunning) 1 else 0
      )

      put(
        "location_active",
        if (event.locationActive) 1 else 0
      )

      put(
        "voice_active",
        if (event.voiceActive) 1 else 0
      )

      put(
        "pti_active",
        if (event.ptiActive) 1 else 0
      )

      if (event.lastLocationAt != null) {
        put(
          "last_location_at",
          event.lastLocationAt
        )
      } else {
        putNull(
          "last_location_at"
        )
      }

      if (event.lastAudioAt != null) {
        put(
          "last_audio_at",
          event.lastAudioAt
        )
      } else {
        putNull(
          "last_audio_at"
        )
      }

      if (event.lastSensorAt != null) {
        put(
          "last_sensor_at",
          event.lastSensorAt
        )
      } else {
        putNull(
          "last_sensor_at"
        )
      }

      if (event.lastError != null) {
        put(
          "last_error",
          event.lastError
        )
      } else {
        putNull(
          "last_error"
        )
      }

      put("created_at", event.createdAt)
      put("recorded_at", event.recordedAt)
      put("uploaded", 0)
    }

  insertEvent(
    tableName = TABLE_SERVICE_HEALTH_EVENTS,
    eventId = event.eventId,
    values = values,
    eventLabel = "SERVICE_HEALTH"
  )
}

  private fun insertEvent(
    tableName: String,
    eventId: String,
    values: ContentValues,
    eventLabel: String
  ) {
    val result =
      writableDatabase.insertWithOnConflict(
        tableName,
        null,
        values,
        SQLiteDatabase.CONFLICT_IGNORE
      )

    if (result != -1L) {
      return
    }

    if (
      eventExists(
        tableName = tableName,
        eventId = eventId
      )
    ) {
      Log.w(
        TAG,
        "Événement $eventLabel déjà enregistré : $eventId"
      )

      return
    }

    throw IllegalStateException(
      "Échec de l'enregistrement SQLite " +
        "$eventLabel : $eventId"
    )
  }

  fun getPendingCount(): Int =
    getPendingCount(
      TABLE_LOCATIONS
    )

  fun getPendingPtiCount(): Int =
    getPendingCount(
      TABLE_PTI_EVENTS
    )

  fun getPendingVoiceCount(): Int =
    getPendingCount(
      TABLE_VOICE_EVENTS
    )

  fun getPendingSosCount(): Int =
    getPendingCount(
      TABLE_SOS_EVENTS
    )

  fun getPendingServiceCount(): Int =
  getPendingCount(
    TABLE_SERVICE_EVENTS
  )

  fun getPendingServiceHealthCount(): Int =
  getPendingCount(
    TABLE_SERVICE_HEALTH_EVENTS
  )


  private fun getPendingCount(
    tableName: String
  ): Int {
    readableDatabase.rawQuery(
      """
      SELECT COUNT(*)
      FROM $tableName
      WHERE uploaded = 0
      """.trimIndent(),
      null
    ).use { cursor ->
      return if (cursor.moveToFirst()) {
        cursor.getInt(0)
      } else {
        0
      }
    }
  }

  fun getPendingLocationEvents(
    limit: Int = 100
  ): List<GuardianLocationEvent> {
    val safeLimit =
      limit.coerceIn(
        1,
        500
      )

    val events =
      mutableListOf<GuardianLocationEvent>()

    readableDatabase.query(
      TABLE_LOCATIONS,
      null,
      "uploaded = ?",
      arrayOf("0"),
      null,
      null,
      "recorded_at ASC",
      safeLimit.toString()
    ).use { cursor ->
      val idIndex =
        cursor.getColumnIndexOrThrow("id")

      val priorityIndex =
        cursor.getColumnIndexOrThrow("priority")

      val schemaVersionIndex =
        cursor.getColumnIndexOrThrow(
          "schema_version"
        )

      val childIdIndex =
        cursor.getColumnIndexOrThrow("child_id")

      val familyIdIndex =
        cursor.getColumnIndexOrThrow("family_id")

      val familyCodeIndex =
        cursor.getColumnIndexOrThrow(
          "family_code"
        )

      val deviceIdIndex =
        cursor.getColumnIndexOrThrow("device_id")

      val latitudeIndex =
        cursor.getColumnIndexOrThrow("latitude")

      val longitudeIndex =
        cursor.getColumnIndexOrThrow("longitude")

      val accuracyIndex =
        cursor.getColumnIndexOrThrow("accuracy")

      val speedIndex =
        cursor.getColumnIndexOrThrow("speed_mps")

      val altitudeIndex =
        cursor.getColumnIndexOrThrow("altitude")

      val createdAtIndex =
        cursor.getColumnIndexOrThrow("created_at")

      val recordedAtIndex =
        cursor.getColumnIndexOrThrow("recorded_at")

      while (cursor.moveToNext()) {
        val priority =
          parsePriority(
            cursor.getString(
              priorityIndex
            )
          )

        events.add(
          GuardianLocationEvent(
            eventId =
              cursor.getString(idIndex),

            priority =
              priority,

            createdAt =
              cursor.getLong(createdAtIndex),

            recordedAt =
              cursor.getLong(recordedAtIndex),

            schemaVersion =
              cursor.getInt(
                schemaVersionIndex
              ),

            childId =
              cursor.getString(childIdIndex),

            familyId =
              cursor.getString(familyIdIndex),

            familyCode =
              cursor.getString(
                familyCodeIndex
              ),

            deviceId =
              cursor.getString(deviceIdIndex),

            latitude =
              cursor.getDouble(latitudeIndex),

            longitude =
              cursor.getDouble(longitudeIndex),

            accuracy =
              cursor.getNullableFloat(
                accuracyIndex
              ),

            speedMps =
              cursor.getNullableFloat(
                speedIndex
              ),

            altitude =
              cursor.getNullableDouble(
                altitudeIndex
              )
          )
        )
      }
    }

    return events
  }

  fun getPendingPtiEvents(
    limit: Int = 100
  ): List<GuardianPtiEvent> {
    val safeLimit =
      limit.coerceIn(
        1,
        500
      )

    val events =
      mutableListOf<GuardianPtiEvent>()

    readableDatabase.query(
      TABLE_PTI_EVENTS,
      null,
      "uploaded = ?",
      arrayOf("0"),
      null,
      null,
      "recorded_at ASC",
      safeLimit.toString()
    ).use { cursor ->
      val idIndex =
        cursor.getColumnIndexOrThrow("id")

      val priorityIndex =
        cursor.getColumnIndexOrThrow("priority")

      val schemaVersionIndex =
        cursor.getColumnIndexOrThrow(
          "schema_version"
        )

      val childIdIndex =
        cursor.getColumnIndexOrThrow("child_id")

      val familyIdIndex =
        cursor.getColumnIndexOrThrow("family_id")

      val familyCodeIndex =
        cursor.getColumnIndexOrThrow(
          "family_code"
        )

      val deviceIdIndex =
        cursor.getColumnIndexOrThrow("device_id")

      val causeIndex =
        cursor.getColumnIndexOrThrow("cause")

      val stateIndex =
        cursor.getColumnIndexOrThrow("state")

      val scoreIndex =
        cursor.getColumnIndexOrThrow("score")

      val peakImpactIndex =
        cursor.getColumnIndexOrThrow(
          "peak_impact_g"
        )

      val postImpactForceIndex =
        cursor.getColumnIndexOrThrow(
          "post_impact_force_g"
        )

      val horizontalIndex =
        cursor.getColumnIndexOrThrow(
          "horizontal_after_impact"
        )

      val immobilityIndex =
        cursor.getColumnIndexOrThrow(
          "immobility_duration_seconds"
        )

      val latitudeIndex =
        cursor.getColumnIndexOrThrow("latitude")

      val longitudeIndex =
        cursor.getColumnIndexOrThrow("longitude")

      val accuracyIndex =
        cursor.getColumnIndexOrThrow("accuracy")

      val createdAtIndex =
        cursor.getColumnIndexOrThrow("created_at")

      val recordedAtIndex =
        cursor.getColumnIndexOrThrow("recorded_at")

      while (cursor.moveToNext()) {
        val cause =
          GuardianPtiCause.valueOf(
            cursor.getString(
              causeIndex
            )
          )

        val state =
          GuardianPtiState.valueOf(
            cursor.getString(
              stateIndex
            )
          )

        events.add(
          GuardianPtiEvent(
            eventId =
              cursor.getString(idIndex),

            priority =
              parsePriority(
                cursor.getString(
                  priorityIndex
                )
              ),

            createdAt =
              cursor.getLong(createdAtIndex),

            recordedAt =
              cursor.getLong(recordedAtIndex),

            schemaVersion =
              cursor.getInt(
                schemaVersionIndex
              ),

            childId =
              cursor.getString(childIdIndex),

            familyId =
              cursor.getString(familyIdIndex),

            familyCode =
              cursor.getString(
                familyCodeIndex
              ),

            deviceId =
              cursor.getString(deviceIdIndex),

            cause =
              cause,

            state =
              state,

            score =
              cursor.getInt(scoreIndex),

            peakImpactG =
              cursor.getNullableFloat(
                peakImpactIndex
              ),

            postImpactForceG =
              cursor.getNullableFloat(
                postImpactForceIndex
              ),

            horizontalAfterImpact =
              cursor.getNullableBoolean(
                horizontalIndex
              ),

            immobilityDurationSeconds =
              cursor.getNullableInt(
                immobilityIndex
              ),

            latitude =
              cursor.getNullableDouble(
                latitudeIndex
              ),

            longitude =
              cursor.getNullableDouble(
                longitudeIndex
              ),

            accuracy =
              cursor.getNullableFloat(
                accuracyIndex
              )
          )
        )
      }
    }

    return events
  }


  fun getPendingVoiceEvents(
    limit: Int = 100
  ): List<GuardianVoiceEvent> {
    val safeLimit =
      limit.coerceIn(
        1,
        500
      )

    val events =
      mutableListOf<GuardianVoiceEvent>()

    readableDatabase.query(
      TABLE_VOICE_EVENTS,
      null,
      "uploaded = ?",
      arrayOf("0"),
      null,
      null,
      "recorded_at ASC",
      safeLimit.toString()
    ).use { cursor ->
      val idIndex =
        cursor.getColumnIndexOrThrow("id")

      val priorityIndex =
        cursor.getColumnIndexOrThrow("priority")

      val schemaVersionIndex =
        cursor.getColumnIndexOrThrow(
          "schema_version"
        )

      val childIdIndex =
        cursor.getColumnIndexOrThrow("child_id")

      val familyIdIndex =
        cursor.getColumnIndexOrThrow("family_id")

      val familyCodeIndex =
        cursor.getColumnIndexOrThrow(
          "family_code"
        )

      val deviceIdIndex =
        cursor.getColumnIndexOrThrow("device_id")

      val causeIndex =
        cursor.getColumnIndexOrThrow("cause")

      val stateIndex =
        cursor.getColumnIndexOrThrow("state")

      val confidenceScoreIndex =
        cursor.getColumnIndexOrThrow(
          "confidence_score"
        )

      val recognizedTextIndex =
        cursor.getColumnIndexOrThrow(
          "recognized_text"
        )

      val wakePhraseIndex =
        cursor.getColumnIndexOrThrow(
          "wake_phrase"
        )

      val emergencyPhraseIndex =
        cursor.getColumnIndexOrThrow(
          "emergency_phrase"
        )

      val latitudeIndex =
        cursor.getColumnIndexOrThrow("latitude")

      val longitudeIndex =
        cursor.getColumnIndexOrThrow("longitude")

      val accuracyIndex =
        cursor.getColumnIndexOrThrow("accuracy")

      val createdAtIndex =
        cursor.getColumnIndexOrThrow("created_at")

      val recordedAtIndex =
        cursor.getColumnIndexOrThrow("recorded_at")

      while (cursor.moveToNext()) {
        events.add(
          GuardianVoiceEvent(
            eventId =
              cursor.getString(idIndex),

            priority =
              parsePriority(
                cursor.getString(
                  priorityIndex
                )
              ),

            createdAt =
              cursor.getLong(createdAtIndex),

            recordedAt =
              cursor.getLong(recordedAtIndex),

            schemaVersion =
              cursor.getInt(
                schemaVersionIndex
              ),

            childId =
              cursor.getString(childIdIndex),

            familyId =
              cursor.getString(familyIdIndex),

            familyCode =
              cursor.getString(
                familyCodeIndex
              ),

            deviceId =
              cursor.getString(deviceIdIndex),

            cause =
              GuardianVoiceCause.valueOf(
                cursor.getString(
                  causeIndex
                )
              ),

            state =
              GuardianVoiceState.valueOf(
                cursor.getString(
                  stateIndex
                )
              ),

            confidenceScore =
              cursor.getInt(
                confidenceScoreIndex
              ),

            recognizedText =
              cursor.getNullableString(
                recognizedTextIndex
              ),

            wakePhrase =
              cursor.getNullableString(
                wakePhraseIndex
              ),

            emergencyPhrase =
              cursor.getNullableString(
                emergencyPhraseIndex
              ),

            latitude =
              cursor.getNullableDouble(
                latitudeIndex
              ),

            longitude =
              cursor.getNullableDouble(
                longitudeIndex
              ),

            accuracy =
              cursor.getNullableFloat(
                accuracyIndex
              )
          )
        )
      }
    }

    return events
  }

  fun getPendingSosEvents(
    limit: Int = 100
  ): List<GuardianSosEvent> {
    val safeLimit =
      limit.coerceIn(
        1,
        500
      )

    val events =
      mutableListOf<GuardianSosEvent>()

    readableDatabase.query(
      TABLE_SOS_EVENTS,
      null,
      "uploaded = ?",
      arrayOf("0"),
      null,
      null,
      "recorded_at ASC",
      safeLimit.toString()
    ).use { cursor ->
      val idIndex =
        cursor.getColumnIndexOrThrow("id")

      val schemaVersionIndex =
        cursor.getColumnIndexOrThrow(
          "schema_version"
        )

      val childIdIndex =
        cursor.getColumnIndexOrThrow("child_id")

      val familyIdIndex =
        cursor.getColumnIndexOrThrow("family_id")

      val familyCodeIndex =
        cursor.getColumnIndexOrThrow(
          "family_code"
        )

      val deviceIdIndex =
        cursor.getColumnIndexOrThrow("device_id")

      val latitudeIndex =
        cursor.getColumnIndexOrThrow("latitude")

      val longitudeIndex =
        cursor.getColumnIndexOrThrow("longitude")

      val accuracyIndex =
        cursor.getColumnIndexOrThrow("accuracy")

      val batteryPercentIndex =
        cursor.getColumnIndexOrThrow(
          "battery_percent"
        )

      val createdAtIndex =
        cursor.getColumnIndexOrThrow("created_at")

      val recordedAtIndex =
        cursor.getColumnIndexOrThrow("recorded_at")

      while (cursor.moveToNext()) {
        events.add(
          GuardianSosEvent(
            eventId =
              cursor.getString(idIndex),

            createdAt =
              cursor.getLong(createdAtIndex),

            recordedAt =
              cursor.getLong(recordedAtIndex),

            schemaVersion =
              cursor.getInt(
                schemaVersionIndex
              ),

            childId =
              cursor.getString(childIdIndex),

            familyId =
              cursor.getString(familyIdIndex),

            familyCode =
              cursor.getString(
                familyCodeIndex
              ),

            deviceId =
              cursor.getString(deviceIdIndex),

            latitude =
              cursor.getNullableDouble(
                latitudeIndex
              ),

            longitude =
              cursor.getNullableDouble(
                longitudeIndex
              ),

            accuracy =
              cursor.getNullableFloat(
                accuracyIndex
              ),

            batteryPercent =
              cursor.getNullableInt(
                batteryPercentIndex
              )
          )
        )
      }
    }

    return events
  }

  fun getPendingServiceEvents(
  limit: Int = 100
): List<GuardianServiceEvent> {
  val safeLimit =
    limit.coerceIn(
      1,
      500
    )

  val events =
    mutableListOf<GuardianServiceEvent>()

  readableDatabase.query(
    TABLE_SERVICE_EVENTS,
    null,
    "uploaded = ?",
    arrayOf("0"),
    null,
    null,
    "recorded_at ASC",
    safeLimit.toString()
  ).use { cursor ->
    val idIndex =
      cursor.getColumnIndexOrThrow("id")

    val priorityIndex =
      cursor.getColumnIndexOrThrow("priority")

    val schemaVersionIndex =
      cursor.getColumnIndexOrThrow(
        "schema_version"
      )

    val childIdIndex =
      cursor.getColumnIndexOrThrow("child_id")

    val familyIdIndex =
      cursor.getColumnIndexOrThrow("family_id")

    val familyCodeIndex =
      cursor.getColumnIndexOrThrow(
        "family_code"
      )

    val deviceIdIndex =
      cursor.getColumnIndexOrThrow("device_id")

    val reasonIndex =
      cursor.getColumnIndexOrThrow("reason")

    val createdAtIndex =
      cursor.getColumnIndexOrThrow("created_at")

    val recordedAtIndex =
      cursor.getColumnIndexOrThrow("recorded_at")

    while (cursor.moveToNext()) {
      events.add(
        GuardianServiceEvent(
          eventId =
            cursor.getString(idIndex),

          priority =
            GuardianEventPriority.valueOf(
              cursor.getString(
                priorityIndex
              )
            ),

          createdAt =
            cursor.getLong(createdAtIndex),

          recordedAt =
            cursor.getLong(recordedAtIndex),

          schemaVersion =
            cursor.getInt(
              schemaVersionIndex
            ),

          childId =
            cursor.getString(childIdIndex),

          familyId =
            cursor.getString(familyIdIndex),

          familyCode =
            cursor.getString(
              familyCodeIndex
            ),

          deviceId =
            cursor.getString(deviceIdIndex),

          reason =
            cursor.getString(reasonIndex)
        )
      )
    }
  }

  return events
}

  fun getPendingServiceHealthEvents(
  limit: Int = 100
): List<GuardianServiceHealthEvent> {
  val safeLimit =
    limit.coerceIn(
      1,
      500
    )

  val events =
    mutableListOf<GuardianServiceHealthEvent>()

  readableDatabase.query(
    TABLE_SERVICE_HEALTH_EVENTS,
    null,
    "uploaded = ?",
    arrayOf("0"),
    null,
    null,
    "recorded_at ASC",
    safeLimit.toString()
  ).use { cursor ->
    val idIndex =
      cursor.getColumnIndexOrThrow("id")

    val priorityIndex =
      cursor.getColumnIndexOrThrow("priority")

    val schemaVersionIndex =
      cursor.getColumnIndexOrThrow(
        "schema_version"
      )

    val childIdIndex =
      cursor.getColumnIndexOrThrow("child_id")

    val familyIdIndex =
      cursor.getColumnIndexOrThrow("family_id")

    val familyCodeIndex =
      cursor.getColumnIndexOrThrow(
        "family_code"
      )

    val deviceIdIndex =
      cursor.getColumnIndexOrThrow("device_id")

    val serviceRunningIndex =
      cursor.getColumnIndexOrThrow(
        "service_running"
      )

    val locationActiveIndex =
      cursor.getColumnIndexOrThrow(
        "location_active"
      )

    val voiceActiveIndex =
      cursor.getColumnIndexOrThrow(
        "voice_active"
      )

    val ptiActiveIndex =
      cursor.getColumnIndexOrThrow(
        "pti_active"
      )

    val lastLocationAtIndex =
      cursor.getColumnIndexOrThrow(
        "last_location_at"
      )

    val lastAudioAtIndex =
      cursor.getColumnIndexOrThrow(
        "last_audio_at"
      )

    val lastSensorAtIndex =
      cursor.getColumnIndexOrThrow(
        "last_sensor_at"
      )

    val lastErrorIndex =
      cursor.getColumnIndexOrThrow(
        "last_error"
      )

    val createdAtIndex =
      cursor.getColumnIndexOrThrow("created_at")

    val recordedAtIndex =
      cursor.getColumnIndexOrThrow("recorded_at")

    while (cursor.moveToNext()) {
      events.add(
        GuardianServiceHealthEvent(
          eventId =
            cursor.getString(idIndex),

          priority =
            parsePriority(
              cursor.getString(
                priorityIndex
              )
            ),

          createdAt =
            cursor.getLong(createdAtIndex),

          recordedAt =
            cursor.getLong(recordedAtIndex),

          schemaVersion =
            cursor.getInt(
              schemaVersionIndex
            ),

          childId =
            cursor.getString(childIdIndex),

          familyId =
            cursor.getString(familyIdIndex),

          familyCode =
            cursor.getString(
              familyCodeIndex
            ),

          deviceId =
            cursor.getString(deviceIdIndex),

          serviceRunning =
            cursor.getInt(
              serviceRunningIndex
            ) != 0,

          locationActive =
            cursor.getInt(
              locationActiveIndex
            ) != 0,

          voiceActive =
            cursor.getInt(
              voiceActiveIndex
            ) != 0,

          ptiActive =
            cursor.getInt(
              ptiActiveIndex
            ) != 0,

          lastLocationAt =
            cursor.getNullableLong(
              lastLocationAtIndex
            ),

          lastAudioAt =
            cursor.getNullableLong(
              lastAudioAtIndex
            ),

          lastSensorAt =
            cursor.getNullableLong(
              lastSensorAtIndex
            ),

          lastError =
            cursor.getNullableString(
              lastErrorIndex
            )
        )
      )
    }
  }

  return events
}

  @Synchronized
  fun markUploaded(
    eventIds: List<String>
  ) {
    markUploaded(
      tableName = TABLE_LOCATIONS,
      eventIds = eventIds,
      eventLabel = "GPS"
    )
  }

  @Synchronized
  fun markPtiUploaded(
    eventIds: List<String>
  ) {
    markUploaded(
      tableName = TABLE_PTI_EVENTS,
      eventIds = eventIds,
      eventLabel = "PTI"
    )
  }

  @Synchronized
  fun markVoiceUploaded(
    eventIds: List<String>
  ) {
    markUploaded(
      tableName = TABLE_VOICE_EVENTS,
      eventIds = eventIds,
      eventLabel = "VOICE"
    )
  }

  @Synchronized
  fun markSosUploaded(
    eventIds: List<String>
  ) {
    markUploaded(
      tableName = TABLE_SOS_EVENTS,
      eventIds = eventIds,
      eventLabel = "SOS"
    )
  }

  @Synchronized
fun markServiceUploaded(
  eventIds: List<String>
) {
  markUploaded(
    tableName = TABLE_SERVICE_EVENTS,
    eventIds = eventIds,
    eventLabel = "SERVICE"
  )
}

@Synchronized
fun markServiceHealthUploaded(
  eventIds: List<String>
) {
  markUploaded(
    tableName = TABLE_SERVICE_HEALTH_EVENTS,
    eventIds = eventIds,
    eventLabel = "SERVICE_HEALTH"
  )
}

  private fun markUploaded(
    tableName: String,
    eventIds: List<String>,
    eventLabel: String
  ) {
    val validEventIds =
      eventIds
        .asSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .toList()

    if (validEventIds.isEmpty()) {
      return
    }

    val database =
      writableDatabase

    database.beginTransaction()

    try {
      validEventIds
        .chunked(
          SQL_BATCH_SIZE
        )
        .forEach { eventIdBatch ->
          markBatchUploaded(
            database = database,
            tableName = tableName,
            eventIds = eventIdBatch
          )
        }

      cleanupUploadedEvents(
        database = database,
        tableName = tableName,
        eventLabel = eventLabel
      )

      database.setTransactionSuccessful()
    } finally {
      database.endTransaction()
    }
  }

  private fun markBatchUploaded(
    database: SQLiteDatabase,
    tableName: String,
    eventIds: List<String>
  ) {
    if (eventIds.isEmpty()) {
      return
    }

    val placeholders =
      eventIds.joinToString(",") {
        "?"
      }

    database.execSQL(
      """
      UPDATE $tableName
      SET uploaded = 1
      WHERE uploaded = 0
        AND id IN ($placeholders)
      """.trimIndent(),
      eventIds.toTypedArray()
    )
  }

  private fun cleanupUploadedEvents(
    database: SQLiteDatabase,
    tableName: String,
    eventLabel: String
  ) {
    val totalCount =
      getTotalCount(
        database = database,
        tableName = tableName
      )

    if (
      totalCount <=
      MAX_STORED_EVENTS
    ) {
      return
    }

    val numberToDelete =
      totalCount -
        TARGET_STORED_EVENTS

    if (numberToDelete <= 0) {
      return
    }

    database.execSQL(
      """
      DELETE FROM $tableName
      WHERE id IN (
        SELECT id
        FROM $tableName
        WHERE uploaded = 1
        ORDER BY recorded_at ASC
        LIMIT ?
      )
      """.trimIndent(),
      arrayOf(
        numberToDelete
      )
    )

    Log.d(
      TAG,
      "Nettoyage SQLite $eventLabel demandé : " +
        "maximum=$numberToDelete événements uploadés"
    )
  }

  private fun getTotalCount(
    database: SQLiteDatabase,
    tableName: String
  ): Int {
    database.rawQuery(
      """
      SELECT COUNT(*)
      FROM $tableName
      """.trimIndent(),
      null
    ).use { cursor ->
      return if (cursor.moveToFirst()) {
        cursor.getInt(0)
      } else {
        0
      }
    }
  }

  private fun eventExists(
    tableName: String,
    eventId: String
  ): Boolean {
    readableDatabase.query(
      tableName,
      arrayOf("id"),
      "id = ?",
      arrayOf(eventId),
      null,
      null,
      null,
      "1"
    ).use { cursor ->
      return cursor.moveToFirst()
    }
  }

  private fun parsePriority(
    rawPriority: String
  ): GuardianEventPriority {
    return runCatching {
      GuardianEventPriority.valueOf(
        rawPriority
      )
    }.getOrDefault(
      GuardianEventPriority.NORMAL
    )
  }

  private fun validateLocationEvent(
    event: GuardianLocationEvent
  ) {
    validateIdentity(
      eventId = event.eventId,
      childId = event.childId,
      familyId = event.familyId,
      familyCode = event.familyCode,
      deviceId = event.deviceId,
      label = "GPS"
    )

    require(
      event.latitude.isFinite() &&
        event.latitude in -90.0..90.0
    ) {
      "Latitude Guardian invalide"
    }

    require(
      event.longitude.isFinite() &&
        event.longitude in -180.0..180.0
    ) {
      "Longitude Guardian invalide"
    }

    require(
      event.accuracy == null ||
        (
          event.accuracy.isFinite() &&
            event.accuracy >= 0f
          )
    ) {
      "Précision Guardian invalide"
    }

    require(
      event.speedMps == null ||
        (
          event.speedMps.isFinite() &&
            event.speedMps >= 0f
          )
    ) {
      "Vitesse Guardian invalide"
    }

    require(
      event.altitude == null ||
        event.altitude.isFinite()
    ) {
      "Altitude Guardian invalide"
    }

    validateTimestamps(
      createdAt = event.createdAt,
      recordedAt = event.recordedAt,
      schemaVersion = event.schemaVersion,
      label = "GPS"
    )
  }

  private fun validatePtiEvent(
    event: GuardianPtiEvent
  ) {
    validateIdentity(
      eventId = event.eventId,
      childId = event.childId,
      familyId = event.familyId,
      familyCode = event.familyCode,
      deviceId = event.deviceId,
      label = "PTI"
    )

    require(
      event.score in 0..100
    ) {
      "Score PTI invalide"
    }

    require(
      event.peakImpactG == null ||
        (
          event.peakImpactG.isFinite() &&
            event.peakImpactG >= 0f
          )
    ) {
      "Impact PTI invalide"
    }

    require(
      event.postImpactForceG == null ||
        (
          event.postImpactForceG.isFinite() &&
            event.postImpactForceG >= 0f
          )
    ) {
      "Force post-impact PTI invalide"
    }

    require(
      event.immobilityDurationSeconds == null ||
        event.immobilityDurationSeconds >= 0
    ) {
      "Durée d'immobilité PTI invalide"
    }

    require(
      (event.latitude == null) ==
        (event.longitude == null)
    ) {
      "Position PTI incomplète"
    }

    require(
      event.latitude == null ||
        (
          event.latitude.isFinite() &&
            event.latitude in -90.0..90.0
          )
    ) {
      "Latitude PTI invalide"
    }

    require(
      event.longitude == null ||
        (
          event.longitude.isFinite() &&
            event.longitude in -180.0..180.0
          )
    ) {
      "Longitude PTI invalide"
    }

    require(
      event.accuracy == null ||
        (
          event.accuracy.isFinite() &&
            event.accuracy >= 0f
          )
    ) {
      "Précision PTI invalide"
    }

    validateTimestamps(
      createdAt = event.createdAt,
      recordedAt = event.recordedAt,
      schemaVersion = event.schemaVersion,
      label = "PTI"
    )
  }


  private fun validateVoiceEvent(
    event: GuardianVoiceEvent
  ) {
    validateIdentity(
      eventId = event.eventId,
      childId = event.childId,
      familyId = event.familyId,
      familyCode = event.familyCode,
      deviceId = event.deviceId,
      label = "VOICE"
    )

    require(
      event.confidenceScore in 0..100
    ) {
      "Score Voice invalide"
    }

    require(
      event.recognizedText == null ||
        event.recognizedText.isNotBlank()
    ) {
      "Texte Voice vide"
    }

    require(
      event.wakePhrase == null ||
        event.wakePhrase.isNotBlank()
    ) {
      "Phrase de réveil Voice vide"
    }

    require(
      event.emergencyPhrase == null ||
        event.emergencyPhrase.isNotBlank()
    ) {
      "Phrase d'urgence Voice vide"
    }

    require(
      event.recognizedText == null ||
        event.recognizedText.length <= 500
    ) {
      "Texte Voice trop long"
    }

    require(
      event.wakePhrase == null ||
        event.wakePhrase.length <= 120
    ) {
      "Phrase de réveil Voice trop longue"
    }

    require(
      event.emergencyPhrase == null ||
        event.emergencyPhrase.length <= 120
    ) {
      "Phrase d'urgence Voice trop longue"
    }

    require(
      (event.latitude == null) ==
        (event.longitude == null)
    ) {
      "Position Voice incomplète"
    }

    require(
      event.latitude == null ||
        (
          event.latitude.isFinite() &&
            event.latitude in -90.0..90.0
          )
    ) {
      "Latitude Voice invalide"
    }

    require(
      event.longitude == null ||
        (
          event.longitude.isFinite() &&
            event.longitude in -180.0..180.0
          )
    ) {
      "Longitude Voice invalide"
    }

    require(
      event.accuracy == null ||
        (
          event.accuracy.isFinite() &&
            event.accuracy >= 0f
          )
    ) {
      "Précision Voice invalide"
    }

    require(
      event.accuracy == null ||
        event.latitude != null
    ) {
      "Précision Voice sans position"
    }

    validateTimestamps(
      createdAt = event.createdAt,
      recordedAt = event.recordedAt,
      schemaVersion = event.schemaVersion,
      label = "VOICE"
    )
  }

  private fun validateSosEvent(
    event: GuardianSosEvent
  ) {
    validateIdentity(
      eventId = event.eventId,
      childId = event.childId,
      familyId = event.familyId,
      familyCode = event.familyCode,
      deviceId = event.deviceId,
      label = "SOS"
    )

    require(
      event.eventType == GuardianEventType.SOS
    ) {
      "Type d'événement SOS invalide"
    }

    require(
      event.priority == GuardianEventPriority.CRITICAL
    ) {
      "Priorité SOS invalide"
    }

    require(
      (event.latitude == null) ==
        (event.longitude == null)
    ) {
      "Position SOS incomplète"
    }

    require(
      event.latitude == null ||
        (
          event.latitude.isFinite() &&
            event.latitude in -90.0..90.0
          )
    ) {
      "Latitude SOS invalide"
    }

    require(
      event.longitude == null ||
        (
          event.longitude.isFinite() &&
            event.longitude in -180.0..180.0
          )
    ) {
      "Longitude SOS invalide"
    }

    require(
      event.accuracy == null ||
        (
          event.accuracy.isFinite() &&
            event.accuracy >= 0f
          )
    ) {
      "Précision SOS invalide"
    }

    require(
      event.accuracy == null ||
        event.latitude != null
    ) {
      "Précision SOS sans position"
    }

    require(
      event.batteryPercent == null ||
        event.batteryPercent in 0..100
    ) {
      "Batterie SOS invalide"
    }

    validateTimestamps(
      createdAt = event.createdAt,
      recordedAt = event.recordedAt,
      schemaVersion = event.schemaVersion,
      label = "SOS"
    )
  }

  private fun validateServiceEvent(
  event: GuardianServiceEvent
) {
  validateIdentity(
    eventId = event.eventId,
    childId = event.childId,
    familyId = event.familyId,
    familyCode = event.familyCode,
    deviceId = event.deviceId,
    label = "SERVICE"
  )

  require(
    event.eventType ==
      GuardianEventType.TRACKING_STOPPED
  ) {
    "Type d'événement SERVICE invalide"
  }

  require(
    event.priority ==
      GuardianEventPriority.HIGH
  ) {
    "Priorité SERVICE invalide"
  }

  require(
    event.reason.isNotBlank()
  ) {
    "Raison SERVICE vide"
  }

  validateTimestamps(
    createdAt = event.createdAt,
    recordedAt = event.recordedAt,
    schemaVersion = event.schemaVersion,
    label = "SERVICE"
  )
}

private fun validateServiceHealthEvent(
  event: GuardianServiceHealthEvent
) {
  validateIdentity(
    eventId = event.eventId,
    childId = event.childId,
    familyId = event.familyId,
    familyCode = event.familyCode,
    deviceId = event.deviceId,
    label = "SERVICE_HEALTH"
  )

  require(
    event.eventType ==
      GuardianEventType.SERVICE_HEALTH
  ) {
    "Type d'événement SERVICE_HEALTH invalide"
  }

  validateTimestamps(
    createdAt = event.createdAt,
    recordedAt = event.recordedAt,
    schemaVersion = event.schemaVersion,
    label = "SERVICE_HEALTH"
  )
}

  private fun validateIdentity(
    eventId: String,
    childId: String,
    familyId: String,
    familyCode: String,
    deviceId: String,
    label: String
  ) {
    require(eventId.isNotBlank()) {
      "eventId $label vide"
    }

    require(childId.isNotBlank()) {
      "childId $label vide"
    }

    require(familyId.isNotBlank()) {
      "familyId $label vide"
    }

    require(familyCode.isNotBlank()) {
      "familyCode $label vide"
    }

    require(deviceId.isNotBlank()) {
      "deviceId $label vide"
    }
  }

  private fun validateTimestamps(
    createdAt: Long,
    recordedAt: Long,
    schemaVersion: Int,
    label: String
  ) {
    require(createdAt > 0L) {
      "createdAt $label invalide"
    }

    require(recordedAt > 0L) {
      "recordedAt $label invalide"
    }

    require(schemaVersion > 0) {
      "schemaVersion $label invalide"
    }
  }


  private fun android.database.Cursor
    .getNullableString(
      columnIndex: Int
    ): String? {
    return if (
      isNull(
        columnIndex
      )
    ) {
      null
    } else {
      getString(
        columnIndex
      )
    }
  }

  private fun android.database.Cursor
    .getNullableFloat(
      columnIndex: Int
    ): Float? {
    return if (
      isNull(
        columnIndex
      )
    ) {
      null
    } else {
      getFloat(
        columnIndex
      )
    }
  }

  private fun android.database.Cursor
    .getNullableDouble(
      columnIndex: Int
    ): Double? {
    return if (
      isNull(
        columnIndex
      )
    ) {
      null
    } else {
      getDouble(
        columnIndex
      )
    }
  }

  private fun android.database.Cursor
    .getNullableInt(
      columnIndex: Int
    ): Int? {
    return if (
      isNull(
        columnIndex
      )
    ) {
      null
    } else {
      getInt(
        columnIndex
      )
    }
  }

  private fun android.database.Cursor
  .getNullableLong(
    columnIndex: Int
  ): Long? {
  return if (
    isNull(
      columnIndex
    )
  ) {
    null
  } else {
    getLong(
      columnIndex
    )
  }
}

  private fun android.database.Cursor
    .getNullableBoolean(
      columnIndex: Int
    ): Boolean? {
    return if (
      isNull(
        columnIndex
      )
    ) {
      null
    } else {
      getInt(
        columnIndex
      ) != 0
    }
  }
}