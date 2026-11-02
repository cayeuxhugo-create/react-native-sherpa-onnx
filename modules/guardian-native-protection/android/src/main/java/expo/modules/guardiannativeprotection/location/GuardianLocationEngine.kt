package expo.modules.guardiannativeprotection

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.Tasks
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class GuardianLocationEngine(
  context: Context,
  private val config: GuardianNativeConfig
) {

  private val appContext =
    context.applicationContext

  private val locationStore =
    GuardianLocationStore(appContext)

  private val fusedLocationClient:
    FusedLocationProviderClient =
      LocationServices.getFusedLocationProviderClient(
        appContext
      )

  private var callbackExecutor:
  ExecutorService? = null

private var previousLatitude: Double? = null

private var previousLongitude: Double? = null

private var previousLocationTimestamp: Long = 0L

  companion object {
    @Volatile
    var locationActive = false
      private set

    @Volatile
    var lastLatitude: Double? = null
      private set

    @Volatile
    var lastLongitude: Double? = null
      private set

    @Volatile
    var lastAccuracy: Float? = null
      private set

    @Volatile
    var lastSpeedMps: Float? = null
      private set

    @Volatile
    var lastLocationAt: Long? = null
      private set

    @Volatile
    var pendingLocationCount = 0
      private set

    @Volatile
    var lastError: String? = null
      private set

    fun refreshPendingCount(
      context: Context
    ) {
      pendingLocationCount =
        GuardianLocationStore(
          context.applicationContext
        ).getPendingCount()
    }
  }

  /*
   * Position souhaitée toutes les 10 secondes.
   * Android peut ralentir cette fréquence selon
   * la batterie, le réseau ou l'état du téléphone.
   */
  private val locationRequest =
    LocationRequest.Builder(
      Priority.PRIORITY_HIGH_ACCURACY,
      10_000L
    )
      .setMinUpdateIntervalMillis(5_000L)
      .setMinUpdateDistanceMeters(1f)
      .setWaitForAccurateLocation(false)
      .build()

  private val locationCallback =
    object : LocationCallback() {
      override fun onLocationResult(
        locationResult: LocationResult
      ) {
        for (location in locationResult.locations) {
          processLocation(
            latitude = location.latitude,
            longitude = location.longitude,

            accuracy =
              if (location.hasAccuracy()) {
                location.accuracy
              } else {
                null
              },

            speedMps =
              if (location.hasSpeed()) {
                location.speed
              } else {
                null
              },

            altitude =
              if (location.hasAltitude()) {
                location.altitude
              } else {
                null
              },

            locationTimestamp =
              if (location.time > 0L) {
                location.time
              } else {
                System.currentTimeMillis()
              }
          )
        }
      }
    }

  private fun processLocation(
    latitude: Double,
    longitude: Double,
    accuracy: Float?,
    speedMps: Float?,
    altitude: Double?,
    locationTimestamp: Long
  ) {
    /*
     * Protection minimale contre une position
     * techniquement invalide.
     */
    if (
  !latitude.isFinite() ||
  !longitude.isFinite() ||
  latitude !in -90.0..90.0 ||
  longitude !in -180.0..180.0
) {
      lastError =
        "Coordonnées GPS natives invalides"

      Log.e(
        "GuardianGPS",
        "Position rejetée : latitude=$latitude, longitude=$longitude"
      )

      return
    }

    /*
     * On refuse aussi les valeurs négatives
     * impossibles pour la précision et la vitesse.
     */
    val safeAccuracy =
  accuracy?.takeIf {
    it.isFinite() &&
      it >= 0f
  }

val safeSpeedMps =
  speedMps?.takeIf {
    it.isFinite() &&
      it >= 0f
  }

val safeAltitude =
  altitude?.takeIf {
    it.isFinite()
  }

    val safeLocationTimestamp =
      if (locationTimestamp > 0L) {
        locationTimestamp
      } else {
        System.currentTimeMillis()
      }

      val elapsedSincePrevious =
  safeLocationTimestamp -
    previousLocationTimestamp

val isImmediateDuplicate =
  previousLatitude == latitude &&
    previousLongitude == longitude &&
    elapsedSincePrevious >= 0L &&
    elapsedSincePrevious < 3_000L

if (isImmediateDuplicate) {
  Log.d(
    "GuardianGPS",
    "Position GPS identique ignorée"
  )

  return
}

lastLatitude =
  latitude

lastLongitude =
  longitude

lastAccuracy =
  safeAccuracy

lastSpeedMps =
  safeSpeedMps

lastLocationAt =
  safeLocationTimestamp

try {
  val event =
    GuardianLocationEvent(
      eventId =
        UUID.randomUUID().toString(),

      priority =
        GuardianEventPriority.NORMAL,

      createdAt =
        safeLocationTimestamp,

      recordedAt =
        System.currentTimeMillis(),

      schemaVersion =
        GuardianNativeEvent
          .CURRENT_SCHEMA_VERSION,

      childId =
        config.childId,

      familyId =
        config.familyId,

      familyCode =
        config.familyCode,

      deviceId =
        config.deviceId,

      latitude =
        latitude,

      longitude =
        longitude,

      accuracy =
        safeAccuracy,

      speedMps =
        safeSpeedMps,

      altitude =
        safeAltitude
    )

  locationStore.saveLocationEvent(
    event
  )

  previousLatitude =
    latitude

  previousLongitude =
    longitude

  previousLocationTimestamp =
    safeLocationTimestamp

  GuardianUploadScheduler.scheduleUpload(
    appContext
  )

  pendingLocationCount += 1

  lastError = null

  Log.d(
    "GuardianGPS",
    "Position native sauvegardée : " +
      "eventId=${event.eventId}, " +
      "pending=$pendingLocationCount, " +
      "accuracy=$safeAccuracy"
  )
} catch (error: Exception) {
  lastError =
    error.message
      ?: "Échec du stockage GPS local"

  Log.e(
    "GuardianGPS",
    "Échec sauvegarde événement GPS",
    error
  )
}
  }

  @Synchronized
  fun start(): Boolean {
    if (locationActive) {
      Log.d(
        "GuardianGPS",
        "Guardian Location Engine déjà actif"
      )

      return true
    }

    val fineLocationGranted =
      ContextCompat.checkSelfPermission(
        appContext,
        Manifest.permission.ACCESS_FINE_LOCATION
      ) == PackageManager.PERMISSION_GRANTED

    val coarseLocationGranted =
      ContextCompat.checkSelfPermission(
        appContext,
        Manifest.permission.ACCESS_COARSE_LOCATION
      ) == PackageManager.PERMISSION_GRANTED

    if (
      !fineLocationGranted &&
      !coarseLocationGranted
    ) {
      locationActive = false

      lastError =
        "Permission de localisation absente"

      Log.e(
        "GuardianGPS",
        lastError
          ?: "Permission GPS absente"
      )

      return false
    }

    previousLatitude = null
previousLongitude = null
previousLocationTimestamp = 0L

    callbackExecutor =
      Executors.newSingleThreadExecutor()

    return try {
      val executor =
        callbackExecutor
          ?: throw IllegalStateException(
            "Executor GPS indisponible"
          )

      val startTask =
        fusedLocationClient
          .requestLocationUpdates(
            locationRequest,
            executor,
            locationCallback
          )

      /*
       * Cette méthode est appelée depuis le scope IO
       * du GuardianProtectionService. On attend donc
       * la confirmation réelle de Google Play Services.
       */
      Tasks.await(startTask)

      locationActive = true
      lastError = null

      refreshPendingCount(
        appContext
      )

      Log.d(
        "GuardianGPS",
        "Guardian Location Engine démarré"
      )

      true
    } catch (securityError: SecurityException) {
      cleanupExecutor()

      locationActive = false

      lastError =
        securityError.message
          ?: "Permission GPS refusée"

      Log.e(
        "GuardianGPS",
        "Permission GPS refusée",
        securityError
      )

      false
    } catch (error: Exception) {
      cleanupExecutor()

      locationActive = false

      /*
       * Tasks.await peut encapsuler l'erreur réelle
       * dans sa cause.
       */
      lastError =
        error.cause?.message
          ?: error.message
          ?: "Erreur GPS native inconnue"

      Log.e(
        "GuardianGPS",
        "Échec du démarrage GPS natif",
        error
      )

      false
    }
  }

  @Synchronized
  fun stop() {
    /*
     * On considère immédiatement le moteur arrêté
     * côté Guardian Core. La demande de retrait est
     * ensuite envoyée à Google Play Services.
     */
    locationActive = false

    try {
      fusedLocationClient
        .removeLocationUpdates(
          locationCallback
        )
        .addOnSuccessListener {
          Log.d(
            "GuardianGPS",
            "Mises à jour GPS retirées"
          )
        }
        .addOnFailureListener { error ->
          lastError =
            error.message
              ?: "Échec de l'arrêt GPS natif"

          Log.e(
            "GuardianGPS",
            "Échec du retrait des mises à jour GPS",
            error
          )
        }
    } catch (error: Exception) {
      lastError =
        error.message
          ?: "Erreur pendant l'arrêt GPS natif"

      Log.e(
        "GuardianGPS",
        "Erreur pendant l'arrêt du moteur GPS",
        error
      )
    } finally {
      cleanupExecutor()

      Log.d(
        "GuardianGPS",
        "Guardian Location Engine arrêté"
      )
    }
  }

  private fun cleanupExecutor() {
  val executor =
    callbackExecutor

  callbackExecutor = null

  if (executor == null) {
    return
  }

  executor.shutdown()

  try {
    if (
      !executor.awaitTermination(
        2,
        TimeUnit.SECONDS
      )
    ) {
      executor.shutdownNow()
    }
  } catch (
    interruptedError: InterruptedException
  ) {
    executor.shutdownNow()

    Thread.currentThread()
      .interrupt()
  }
}
}