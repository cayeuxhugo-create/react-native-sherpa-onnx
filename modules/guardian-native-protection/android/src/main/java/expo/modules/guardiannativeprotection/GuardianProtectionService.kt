package expo.modules.guardiannativeprotection

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.MediaRecorder
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class GuardianProtectionService : Service() {

  private val serviceJob =
    SupervisorJob()

  private val serviceScope =
    CoroutineScope(
      serviceJob + Dispatchers.IO
    )

  private var startupJob: Job? =
    null

  private var healthJob: Job? =
    null

  private var lexicalTimelineJob: Job? =
    null

  @Volatile
  private var locationEngine:
    GuardianLocationEngine? = null

  @Volatile
  private var ptiEngine:
    GuardianPtiEngine? = null

  @Volatile
  private var voiceEngine:
    GuardianVoiceEngine? = null

  @Volatile
  private var conversationEngine:
    GuardianConversationEngine? = null

  /*
   * Guardian Lexical Timeline V3 - autorité phrase/contexte.
   *
   * Qwen ne decode que les fenetres finales confirmees par FireRed,
   * mais un FINAL technique n'est jamais assimilé directement à une phrase.
   * Seules les NATURAL_PHRASE de la timeline alimentent le chemin produit.
   */
  @Volatile
  private var wordTimelineEngine:
    GuardianWordTimelineEngine? = null

  @Volatile
private var audioEngine: GuardianAudioEngine? = null

  @Volatile
  private var qwenPrimaryProcessor: GuardianQwenPrimaryAudioProcessor? = null

  @Volatile
  private var locationStore:
    GuardianLocationStore? = null

  @Volatile
  private var serviceDestroyed =
    false

  // Protège les résultats Qwen asynchrones d'un ancien démarrage Voice.
  @Volatile private var activeVoiceSessionToken: String? = null

  companion object {

    const val CHANNEL_ID =
      "guardian_protection_channel"

    const val NOTIFICATION_ID =
      1001

    private const val TAG =
      "GuardianCore"

    private const val AUDIO_EVIDENCE_LOG_INTERVAL_FINALS =
      5L

    private const val LEXICAL_PUBLISHED_TEXT_MAX_ENTRIES =
      64

    @Volatile
    var isRunning =
      false
      private set

    @Volatile
    var startedAt:
      Long? = null
      private set

    @Volatile
    var lastServiceError:
      String? = null
      private set

    @Volatile
    var lastVoiceStatus:
      GuardianVoiceEngine.Status? = null
      private set

    @Volatile
    var lastPtiStatus:
      GuardianPtiEngine.Status? = null
      private set

    @Volatile
var lastVoiceAudioDiagnostics:
  GuardianAudioEngine.Statistics? = null
  private set

  @Volatile
var lastVoiceAudioCapabilities:
  GuardianAudioCapabilities.Capabilities? = null
  private set

  @Volatile
var lastVoiceRuntimeAudioProfile:
    GuardianAudioEngine.RuntimeAudioProfile? = null
    private set

  @Volatile
var lastVoiceConversationFinalText:
    String? = null
    private set

@Volatile
var lastVoiceConversationSegmentCount:
    Int? = null
    private set

@Volatile
var lastVoiceConversationFinalizedAt:
    Long? = null
    private set

@Volatile
var lastVoiceSherpaDiagnostics:
  GuardianAsrContract.Diagnostics? = null
  private set

@Volatile
var lastVoiceUnderstandingDiagnostics:
  GuardianVoiceUnderstandingEngine.UnderstandingResult? = null
  private set

@Volatile
var lastVoiceFusionDiagnostics:
  GuardianVoiceFusionEngine.FusionResult? = null
  private set
  }

  override fun onCreate() {
    super.onCreate()

    serviceDestroyed =
      false

    createNotificationChannel()
    startGuardianForeground()

    isRunning =
      true

    startedAt =
      System.currentTimeMillis()

    lastServiceError =
      null

    lastVoiceStatus =
  null

lastVoiceAudioDiagnostics =
  null

lastVoiceAudioCapabilities =
  null

lastVoiceRuntimeAudioProfile =
    null

 lastVoiceConversationFinalText =
    null

lastVoiceConversationSegmentCount =
    null

lastVoiceConversationFinalizedAt =
    null

lastVoiceSherpaDiagnostics =
  null

lastVoiceUnderstandingDiagnostics =
  null

lastVoiceFusionDiagnostics =
  null

lastPtiStatus =
  null

    Log.d(
      TAG,
      "Guardian Protection Service démarré"
    )

    
  }

  /**
   * Démarre immédiatement le service au premier plan.
   *
   * Le type précis du service sera ensuite actualisé
   * lorsque la configuration Guardian aura été chargée.
   */
  private fun startGuardianForeground() {
    val notification =
      createNotification()

    if (
      Build.VERSION.SDK_INT >=
      Build.VERSION_CODES.Q
    ) {
      startForeground(
        NOTIFICATION_ID,
        notification,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
      )
    } else {
      startForeground(
        NOTIFICATION_ID,
        notification
      )
    }
  }

  /**
   * Charge la configuration native puis démarre
   * uniquement les moteurs activés.
   */
  private fun loadConfigurationAndStartEngines() {
    startupJob?.cancel()

    startupJob =
      serviceScope.launch {
        try {
          val configStore =
            GuardianNativeConfigStore(
              applicationContext
            )

          val config =
            configStore.loadConfig()

          if (serviceDestroyed) {
            return@launch
          }

          if (config == null) {
            stopServiceWithError(
              "Configuration native Guardian absente"
            )

            return@launch
          }

          Log.d(
            TAG,
            "Configuration native chargée : " +
              "childId=${config.childId}, " +
              "gps=${config.gpsEnabled}, " +
              "voice=${config.voiceEnabled}, " +
              "pti=${config.ptiEnabled}"
          )

          if (
            !config.gpsEnabled &&
            !config.ptiEnabled &&
            !config.voiceEnabled
          ) {
            stopServiceWithError(
              "Aucun moteur natif Guardian activé"
            )

            return@launch
          }

          /*
           * Android 11+ : la déclaration du type FOREGROUND_SERVICE_MICROPHONE
           * doit précéder l'ouverture d'AudioRecord. Android 14 vérifie
           * également les permissions du type au moment de startForeground.
           */
          // Une permission FGS microphone manquante ne doit pas couper GPS/PTI.
          var voiceForegroundAllowed = config.voiceEnabled
          try {
            updateForegroundServiceTypes(config)
          } catch (error: Exception) {
            if (!config.voiceEnabled) throw error
            voiceForegroundAllowed = false
            lastServiceError =
              "Voice indisponible : impossible d'activer le service microphone (${error.javaClass.simpleName})"
            Log.e(TAG, "GUARDIAN_VOICE_FGS_UNAVAILABLE : GPS/PTI conservés si disponibles", error)
            // Revenir au type LOCATION uniquement si le GPS est configuré.
            // Une erreur persistante ici concerne le Core, pas seulement Voice.
            updateForegroundServiceTypes(config, includeMicrophone = false)
          }

          /*
           * Un seul store local est partagé par tous
           * les événements Guardian.
           */
          val store =
            locationStore
              ?: GuardianLocationStore(
                applicationContext
              ).also { createdStore ->
                locationStore =
                  createdStore
              }

          if (config.gpsEnabled) {
            startLocationEngine(
              config
            )
          }

          if (serviceDestroyed) {
            return@launch
          }

          if (config.ptiEnabled) {
            startPtiEngine(
              config = config,
              store = store
            )
          }

          if (serviceDestroyed) {
            return@launch
          }

          if (config.voiceEnabled && voiceForegroundAllowed) {
            try {
              startVoiceEngine(config = config, store = store)
            } catch (cancelled: CancellationException) {
              throw cancelled
            } catch (error: Throwable) {
              // Une JVM en détresse (OOM, ThreadDeath) ne peut pas être
              // sécurisée par un simple catch dans le service Android.
              if (error is VirtualMachineError || error is ThreadDeath) throw error
              isolateVoiceStartupFailure(error)
            }
          }

          if (serviceDestroyed) {
            return@launch
          }

          // Ne pas annoncer une protection active si Voice était le seul
          // moteur demandé et qu'aucun démarrage n'a abouti.
          if (config.voiceEnabled && locationEngine == null && ptiEngine == null &&
              (voiceEngine == null || lastVoiceStatus?.running != true)) {
            stopServiceWithError(lastServiceError ?: "Protection vocale indisponible")
            return@launch
          }

          // Les types FGS ont été déclarés avant les moteurs.
          Log.d(
            TAG,
            "Moteurs natifs Guardian démarrés : " +
              "gps=${locationEngine != null}, " +
              "pti=${ptiEngine != null}, " +
              "voice=${voiceEngine != null}"
          )

          healthJob?.cancel()

healthJob =
  serviceScope.launch {
    while (!serviceDestroyed) {

      val now =
  System.currentTimeMillis()

val event =
  GuardianServiceHealthEvent(
    eventId =
      UUID.randomUUID()
        .toString(),

    priority =
      GuardianEventPriority.NORMAL,

    createdAt =
      now,

    recordedAt =
      now,

    schemaVersion =
      GuardianNativeEvent.CURRENT_SCHEMA_VERSION,

    childId =
      config.childId,

    familyId =
      config.familyId,

    familyCode =
      config.familyCode,

    deviceId =
      config.deviceId,

    serviceRunning =
      isRunning,

    locationActive =
      GuardianLocationEngine.locationActive,

    voiceActive =
      lastVoiceStatus?.running
        ?: false,

    ptiActive =
      lastPtiStatus?.running
        ?: false,

    lastLocationAt =
      GuardianLocationEngine.lastLocationAt,

    lastAudioAt =
    lastVoiceStatus?.lastAudioAt,

    lastSensorAt =
  lastPtiStatus?.lastSensorAt,

    lastError =
      lastServiceError
  )

    store.saveServiceHealthEvent(
  event
)

Log.d(
  TAG,
  "SERVICE_HEALTH sauvegardé : " +
    "id=${event.eventId}, " +
    "running=${event.serviceRunning}, " +
    "gps=${event.locationActive}, " +
    "voice=${event.voiceActive}, " +
    "pti=${event.ptiActive}"
)

  GuardianUploadScheduler.forceUpload(
  applicationContext
)

      delay(60000)
    }
  }
        } catch (
          cancellationError: CancellationException
        ) {
          throw cancellationError
        } catch (error: Exception) {
          stopServiceWithError(
            message =
              error.message
                ?: "Erreur d'initialisation du Native Core",
            error = error
          )
        }
      }
  }

  /**
   * Une panne du sous-système vocal ne doit pas atteindre stopSelf() quand
   * le GPS ou le PTI fonctionnent. Le jeton est invalidé AVANT les fermetures
   * pour écarter les callbacks asynchrones de l'ancien décodeur.
   *
   * Les libérations sont indépendantes : l'échec de l'une ne doit pas
   * empêcher les suivantes. Cette méthode ne touche ni au GPS ni au PTI.
   */
  private fun isolateVoiceStartupFailure(error: Throwable) {
    activeVoiceSessionToken = null
    lastServiceError =
      "Voice Qwen indisponible : ${error.message ?: error.javaClass.simpleName}"
    Log.e(TAG, "GUARDIAN_VOICE_STARTUP_FAILED : GPS/PTI conservés", error)

    lexicalTimelineJob?.cancel()
    lexicalTimelineJob = null

    fun safelyClose(label: String, operation: () -> Unit) {
      try {
        operation()
      } catch (closeError: Exception) {
        Log.e(TAG, "VOICE_CLEANUP_FAILED : $label", closeError)
      }
    }

    // Arrêter la capture avant de libérer son décodeur asynchrone.
    safelyClose("audio stop") { audioEngine?.stop() }
    safelyClose("audio release") { audioEngine?.release() }
    audioEngine = null

    safelyClose("Qwen close") { qwenPrimaryProcessor?.close() }
    qwenPrimaryProcessor = null

    safelyClose("Word Timeline") { wordTimelineEngine?.reset() }
    wordTimelineEngine = null

    safelyClose("Conversation") { conversationEngine?.destroy() }
    conversationEngine = null

    safelyClose("VoiceEngine") { voiceEngine?.destroy() }
    voiceEngine = null

    lastVoiceStatus = null
    lastVoiceAudioDiagnostics = null
    lastVoiceAudioCapabilities = null
    lastVoiceRuntimeAudioProfile = null
    lastVoiceConversationFinalText = null
    lastVoiceConversationSegmentCount = null
    lastVoiceConversationFinalizedAt = null
    lastVoiceSherpaDiagnostics = null
    lastVoiceUnderstandingDiagnostics = null
    lastVoiceFusionDiagnostics = null
  }

  /**
   * Démarre le moteur GPS natif.
   */
  private fun startLocationEngine(
    config: GuardianNativeConfig
  ) {
    val engine =
      GuardianLocationEngine(
        applicationContext,
        config
      )

    locationEngine =
      engine

    val locationStarted =
      engine.start()

    if (serviceDestroyed) {
      engine.stop()

      locationEngine =
        null

      return
    }

    if (!locationStarted) {
      locationEngine =
        null

      throw IllegalStateException(
        GuardianLocationEngine.lastError
          ?: "Le moteur GPS natif n'a pas démarré"
      )
    }

    Log.d(
      TAG,
      "Moteur GPS natif démarré"
    )
  }

  /**
   * Démarre le moteur PTI natif.
   */
  private fun startPtiEngine(
    config: GuardianNativeConfig,
    store: GuardianLocationStore
  ) {
    val ptiListener =
      object : GuardianPtiEngine.Listener {

        override fun onPtiEvent(
          event: GuardianPtiEvent
        ) {
          try {
            /*
             * L'événement PTI est rendu durable avant
             * toute tentative réseau.
             */
            store.savePtiEvent(
              event
            )

            Log.w(
              TAG,
              "Événement PTI sauvegardé : " +
                "id=${event.eventId}, " +
                "cause=${event.cause}, " +
                "state=${event.state}, " +
                "score=${event.score}"
            )

            GuardianUploadScheduler.forceUpload(
              applicationContext
            )

            Log.d(
              TAG,
              "Upload PTI immédiat demandé : " +
                "id=${event.eventId}"
            )
          } catch (error: Exception) {
            lastServiceError =
              error.message
                ?: "Échec du stockage de l'événement PTI"

            Log.e(
              TAG,
              "Échec du stockage SQLite PTI",
              error
            )
          }
        }

        override fun onPtiStatusChanged(
          status: GuardianPtiEngine.Status
        ) {
          lastPtiStatus =
            status

          Log.d(
            TAG,
            "PTI status : " +
              "running=${status.running}, " +
              "sensor=${status.sensorAvailable}, " +
              "phase=${status.phase}, " +
              "force=${status.currentForceG}, " +
              "peak=${status.peakImpactG}, " +
              "horizontal=${status.horizontal}, " +
              "still=${status.stillDurationSeconds}s, " +
              "score=${status.lastScore}"
          )
        }

        override fun getLastKnownLocation():
          GuardianPtiEngine.LastKnownLocation? {

          val latitude =
            GuardianLocationEngine.lastLatitude

          val longitude =
            GuardianLocationEngine.lastLongitude

          if (
            latitude == null ||
            longitude == null
          ) {
            return null
          }

          return GuardianPtiEngine.LastKnownLocation(
            latitude = latitude,
            longitude = longitude,
            accuracy =
              GuardianLocationEngine.lastAccuracy
          )
        }
      }

    val engine =
      GuardianPtiEngine(
        context = applicationContext,
        config = config,
        listener = ptiListener
      )

    ptiEngine =
      engine

    engine.start()

    if (serviceDestroyed) {
    engine.destroy()

    ptiEngine = null

    return
}

    if (!engine.isRunning()) {
      engine.destroy()

      ptiEngine =
        null

      throw IllegalStateException(
        "Le moteur PTI natif n'a pas démarré"
      )
    }

    lastPtiStatus =
      engine.getStatus()

    Log.d(
      TAG,
      "Moteur PTI natif démarré"
    )
  }


  /**
   * Override diagnostic du source AudioRecord.
   *
   * Production :
   * - aucun fichier / AUTO / DEFAULT -> source recommandée par GuardianAudioCapabilities.
   *
   * Diagnostic terrain :
   * - MIC -> MediaRecorder.AudioSource.MIC
   * - VOICE_RECOGNITION -> MediaRecorder.AudioSource.VOICE_RECOGNITION
   *
   * Fichier :
   * /sdcard/Android/data/com.hugo3154.guardianvoice/files/guardian-audio-source.txt
   *
   * Le fichier n'est lu qu'au démarrage du moteur Voice.
   * Il faut donc redémarrer le service après chaque changement.
   */
  private data class DiagnosticAudioSourceSelection(
    val source: Int,
    val sourceName: String,
    val explicitOverride: Boolean,
  )

  private fun resolveDiagnosticAudioSource(
    audioCapabilities: GuardianAudioCapabilities.Capabilities
  ): DiagnosticAudioSourceSelection {
    val recommendedSource =
      audioCapabilities.recommendedAudioSource

    val overrideFile = GuardianDiagnosticOptions.controlFile(
        applicationContext.filesDir,
        runCatching { applicationContext.getExternalFilesDir(null) }.getOrNull(),
        "guardian-audio-source.txt",
    )

    val requestedSource =
      try {
        if (overrideFile?.isFile == true) {
          overrideFile
            .readText()
            .trim()
        } else {
          ""
        }
      } catch (error: Throwable) {
        Log.w(
          TAG,
          "AUDIO SOURCE DIAGNOSTIC : lecture override impossible, fallback automatique",
          error
        )

        ""
      }

    val explicitOverride =
      requestedSource.equals(
        "MIC",
        ignoreCase = true
      ) ||
        requestedSource.equals(
          "VOICE_RECOGNITION",
          ignoreCase = true
        )

    val selectedSource =
      when {
        requestedSource.equals(
          "MIC",
          ignoreCase = true
        ) ->
          MediaRecorder.AudioSource.MIC

        requestedSource.equals(
          "VOICE_RECOGNITION",
          ignoreCase = true
        ) ->
          MediaRecorder.AudioSource.VOICE_RECOGNITION

        requestedSource.isBlank() ||
          requestedSource.equals(
            "AUTO",
            ignoreCase = true
          ) ||
          requestedSource.equals(
            "DEFAULT",
            ignoreCase = true
          ) ->
          recommendedSource

        else -> {
          Log.w(
            TAG,
            "AUDIO SOURCE DIAGNOSTIC : valeur inconnue=\"$requestedSource\", fallback source recommandée=$recommendedSource"
          )

          recommendedSource
        }
      }

    val selectedSourceName =
      when (selectedSource) {
        MediaRecorder.AudioSource.MIC ->
          "MIC"

        MediaRecorder.AudioSource.VOICE_RECOGNITION ->
          "VOICE_RECOGNITION"

        else ->
          "SOURCE_$selectedSource"
      }

    val requestedSourceName =
      requestedSource
        .ifBlank {
          "AUTO"
        }

    Log.i(
      TAG,
      "AUDIO SOURCE DIAGNOSTIC : " +
        "requested=$requestedSourceName, " +
        "effective=$selectedSourceName, " +
        "recommended=${audioCapabilities.recommendedAudioSourceName}, " +
        "value=$selectedSource, " +
        "explicitOverride=$explicitOverride"
    )

    return DiagnosticAudioSourceSelection(
      source = selectedSource,
      sourceName = selectedSourceName,
      explicitOverride = explicitOverride,
    )
  }

    private data class DiagnosticMicrophoneField(
    val enabled: Boolean,
    val dimension: Float,
    val name: String,
)

private fun resolveDiagnosticMicrophoneField(): DiagnosticMicrophoneField {
    val externalFilesDirectory =
        applicationContext.getExternalFilesDir(null)

    if (externalFilesDirectory == null) {
        Log.w(
            TAG,
            "MIC FIELD DIAGNOSTIC : externalFilesDir indisponible, fallback OFF"
        )

        return DiagnosticMicrophoneField(
            enabled = false,
            dimension = 0.0f,
            name = "OFF",
        )
    }

    val overrideFile =
        File(
            externalFilesDirectory,
            "guardian-microphone-field.txt"
        )

    val requested =
        try {
            if (overrideFile.isFile) {
                overrideFile.readText().trim()
            } else {
                "OFF"
            }
        } catch (error: Throwable) {
            Log.w(
                TAG,
                "MIC FIELD DIAGNOSTIC : lecture override impossible, fallback OFF",
                error
            )

            "OFF"
        }

    val selected =
        when {
            requested.equals("WIDE", ignoreCase = true) ||
                requested == "-1" ||
                requested == "-1.0" ->
                DiagnosticMicrophoneField(
                    enabled = true,
                    dimension = -1.0f,
                    name = "WIDE",
                )

            requested.equals("NEUTRAL", ignoreCase = true) ||
                requested == "0" ||
                requested == "0.0" ->
                DiagnosticMicrophoneField(
                    enabled = true,
                    dimension = 0.0f,
                    name = "NEUTRAL",
                )

            requested.equals("ZOOM", ignoreCase = true) ||
                requested == "+1" ||
                requested == "1" ||
                requested == "1.0" ->
                DiagnosticMicrophoneField(
                    enabled = true,
                    dimension = 1.0f,
                    name = "ZOOM",
                )

            requested.isBlank() ||
                requested.equals("OFF", ignoreCase = true) ||
                requested.equals("AUTO", ignoreCase = true) ||
                requested.equals("DEFAULT", ignoreCase = true) ->
                DiagnosticMicrophoneField(
                    enabled = false,
                    dimension = 0.0f,
                    name = "OFF",
                )

            else -> {
                Log.w(
                    TAG,
                    "MIC FIELD DIAGNOSTIC : valeur inconnue=\"$requested\", fallback OFF"
                )

                DiagnosticMicrophoneField(
                    enabled = false,
                    dimension = 0.0f,
                    name = "OFF",
                )
            }
        }

    Log.i(
        TAG,
        "MIC FIELD DIAGNOSTIC : " +
            "requested=${requested.ifBlank { "OFF" }}, " +
            "effective=${selected.name}, " +
            "enabled=${selected.enabled}, " +
            "dimension=${selected.dimension}"
    )

    return selected
}

private data class DiagnosticMultiMic(
    val enabled: Boolean,

    val channelSelection: Int,

    val name: String,

    /*
     * true :
     * le fichier guardian-multimic.txt impose
     * explicitement un comportement.
     *
     * false :
     * Guardian est libre de choisir automatiquement
     * selon AdaptiveCaptureProfile.
     */
    val explicitOverride: Boolean,
    val focusObserve: Boolean = false,
)

private fun resolveDiagnosticMultiMic(): DiagnosticMultiMic {

    val overrideFile = GuardianDiagnosticOptions.controlFile(
        applicationContext.filesDir,
        runCatching { applicationContext.getExternalFilesDir(null) }.getOrNull(),
        "guardian-multimic.txt",
    )

    val requested =
        try {
            if (overrideFile?.isFile == true) {
                overrideFile
                    .readText()
                    .trim()
            } else {
                "AUTO"
            }
        } catch (error: Throwable) {

            Log.w(
                TAG,
                "MULTIMIC DIAGNOSTIC : " +
                    "lecture override impossible, fallback AUTO",
                error,
            )

            "AUTO"
        }

    val selected =
        when {

            requested.equals(
                "LEFT",
                ignoreCase = true,
            ) ->
                DiagnosticMultiMic(
                    enabled = true,
                    channelSelection =
                        GuardianAudioEngine
                            .STEREO_CHANNEL_LEFT,
                    name = "LEFT",
                    explicitOverride = true,
                )

            requested.equals(
                "RIGHT",
                ignoreCase = true,
            ) ->
                DiagnosticMultiMic(
                    enabled = true,
                    channelSelection =
                        GuardianAudioEngine
                            .STEREO_CHANNEL_RIGHT,
                    name = "RIGHT",
                    explicitOverride = true,
                )

            requested.equals(
                "MIX",
                ignoreCase = true,
            ) ->
                DiagnosticMultiMic(
                    enabled = true,
                    channelSelection =
                        GuardianAudioEngine
                            .STEREO_CHANNEL_MIX,
                    name = "MIX",
                    explicitOverride = true,
                )

            requested.equals(
                "ALIGNED_MIX",
                ignoreCase = true,
            ) ||
                requested.equals(
                    "ALIGNED",
                    ignoreCase = true,
                ) ||
                requested.equals(
                    "SPATIAL",
                    ignoreCase = true,
                ) ->
                DiagnosticMultiMic(
                    enabled = true,
                    channelSelection =
                        GuardianAudioEngine
                            .STEREO_CHANNEL_ALIGNED_MIX,
                    name = "ALIGNED_MIX",
                    explicitOverride = true,
                )

            requested.equals("FOCUS", ignoreCase = true) ||
                requested.equals("VOICE_FOCUS", ignoreCase = true) ->
                DiagnosticMultiMic(
                    enabled = true,
                    channelSelection = GuardianAudioEngine.STEREO_CHANNEL_VOICE_FOCUS,
                    name = "VOICE_FOCUS",
                    explicitOverride = true,
                )

            requested.equals("FOCUS_OBSERVE", ignoreCase = true) ->
                DiagnosticMultiMic(
                    enabled = true,
                    channelSelection = GuardianAudioEngine.STEREO_CHANNEL_ALIGNED_MIX,
                    name = "FOCUS_OBSERVE",
                    explicitOverride = true,
                    focusObserve = true,
                )

            /*
             * OFF signifie :
             *
             * "Je demande volontairement le mono."
             *
             * Ce n'est donc PAS le mode automatique.
             */
            requested.equals(
                "OFF",
                ignoreCase = true,
            ) ->
                DiagnosticMultiMic(
                    enabled = false,
                    channelSelection =
                        GuardianAudioEngine
                            .STEREO_CHANNEL_LEFT,
                    name = "OFF",
                    explicitOverride = true,
                )

            /*
             * AUTO / DEFAULT / fichier vide :
             *
             * Guardian décidera ensuite à partir
             * de AdaptiveCaptureProfile.
             */
            requested.isBlank() ||
                requested.equals(
                    "AUTO",
                    ignoreCase = true,
                ) ||
                requested.equals(
                    "DEFAULT",
                    ignoreCase = true,
                ) ->
                DiagnosticMultiMic(
                    enabled = false,
                    channelSelection =
                        GuardianAudioEngine
                            .STEREO_CHANNEL_LEFT,
                    name = "AUTO",
                    explicitOverride = false,
                )

            else -> {

                /*
                 * Une valeur inconnue ne doit jamais
                 * forcer une route matérielle.
                 */
                Log.w(
                    TAG,
                    "MULTIMIC DIAGNOSTIC : " +
                        "valeur inconnue=\"$requested\", " +
                        "fallback AUTO"
                )

                DiagnosticMultiMic(
                    enabled = false,
                    channelSelection =
                        GuardianAudioEngine
                            .STEREO_CHANNEL_LEFT,
                    name = "AUTO",
                    explicitOverride = false,
                )
            }
        }

    Log.i(
        TAG,
        "MULTIMIC DIAGNOSTIC : " +
            "requested=${requested.ifBlank { "AUTO" }}, " +
            "effective=${selected.name}, " +
            "enabled=${selected.enabled}, " +
            "explicitOverride=${selected.explicitOverride}"
    )

    return selected
}

  /*
   * Les preuves AUTO ne doivent jamais être mélangées entre deux firmwares
   * audio différents. Le fingerprint Android est hashé : on ne stocke pas
   * la chaîne constructeur complète dans les clés SharedPreferences.
   *
   * Si le contrat d'évidence lui-même change, le store possède en plus son
   * propre numéro de version de préférences.
   */
  private fun audioEvidenceEnvironmentKey(): String {
    val identity =
      Build.MANUFACTURER + "|" +
        Build.MODEL + "|" +
        Build.DEVICE + "|" +
        Build.FINGERPRINT

    val hash =
      Integer.toUnsignedString(
        identity.hashCode(),
        16,
      ).uppercase()

    return "ENV_$hash"
  }

  private fun automaticAudioEvidenceRouteKey(
    candidate:
      GuardianAudioCapabilities.AutomaticCaptureRouteCandidate
  ): String {
    // Chaque sous-route a une identité propre : jamais « stéréo = mix ».
    val route = candidate.label

    return "${audioEvidenceEnvironmentKey()}_$route"
  }

  private fun runtimeAudioEvidenceRouteKey(
    audioSource: Int,
    multiMic: DiagnosticMultiMic,
  ): String {
    val sourceName =
      when (audioSource) {
        MediaRecorder.AudioSource.VOICE_RECOGNITION ->
          "VOICE_RECOGNITION"

        MediaRecorder.AudioSource.MIC ->
          "MIC"

        else ->
          "SOURCE_$audioSource"
      }

    if (!multiMic.enabled) {
      return "${audioEvidenceEnvironmentKey()}_${sourceName}_MONO"
    }

    val selectionName =
      when (multiMic.channelSelection) {
        GuardianAudioEngine.STEREO_CHANNEL_RIGHT ->
          "CHANNEL_1"

        GuardianAudioEngine.STEREO_CHANNEL_MIX ->
          "MIX"

        GuardianAudioEngine.STEREO_CHANNEL_ALIGNED_MIX ->
          "ALIGNED_MIX"

        GuardianAudioEngine.STEREO_CHANNEL_VOICE_FOCUS ->
          "VOICE_FOCUS"

        else ->
          "CHANNEL_0"
      }

    return "${audioEvidenceEnvironmentKey()}_${sourceName}_STEREO_$selectionName"
  }

  private fun logAudioRouteEvidence(
    prefix: String,
    snapshot: GuardianAudioRouteEvidenceStore.Snapshot,
  ) {
    Log.i(
      "GuardianAudioEvidence",
      "$prefix : " +
        "route=${snapshot.routeKey}, " +
        "sessions=${snapshot.sessionCount}, " +
        "commits=${snapshot.speechCommitCount}, " +
        "finals=${snapshot.finalCount}, " +
        "yield=${"%.3f".format(snapshot.finalYield)}, " +
        "qwenCorrections=${snapshot.qwenCorrectionCount}, " +
        "qwenRate=${"%.3f".format(snapshot.qwenCorrectionRate)}, " +
        "errors=${snapshot.recognizerErrorCount}, " +
        "confidence=${snapshot.averageConfidence?.let { "%.3f".format(it) } ?: "--"}, " +
        "distantFinals=${snapshot.distantFinalCount}, " +
        "mature=${snapshot.mature}"
    )
  }

  /**
   * Démarre le moteur vocal natif.
   */
  private fun startVoiceEngine(
    config: GuardianNativeConfig,
    store: GuardianLocationStore
  ) {
    val diagnosticOptions = GuardianDiagnosticOptions.resolve(
      configEnabled = config.voiceDiagnosticsEnabled,
      configuredProfile = GuardianVoiceProfile.parse(config.voiceProfile),
      privateRoot = applicationContext.filesDir,
      externalRoot = runCatching { applicationContext.getExternalFilesDir(null) }.getOrNull(),
    )
    val voiceProfile = diagnosticOptions.profile
    val diagnosticWavDirectory = diagnosticOptions.wavDirectory
    val voiceDiagnostics = if (diagnosticOptions.enabled)
      GuardianVoiceDiagnostics(enabled = true, profile = voiceProfile.name) else null
    voiceDiagnostics?.event("SESSION_START",
      "engineRevision" to "guardian-voice-v3.4.2-float-diagnostics",
      "diagnosticControlRevision" to 3,
      "wavStageCount" to 5,
      "decoderMode" to "FINAL_ONLY",
      "admissionModel" to GuardianFireRedConfirmation.MODEL_ID,
      "admissionThreshold" to GuardianFireRedPolicy.THRESHOLD,
      "admissionMaxInputGain" to GuardianFireRedPolicy.MAX_INPUT_GAIN,
      "candidateVad" to GuardianFireRedStreaming.MODEL_ID,
      "diagnosticsEnabledBy" to diagnosticOptions.controlSource,
      "wavStorage" to "PRIVATE",
      "sampleRateHz" to 16000, "vadThreshold" to 0.5,
      "vadMinSpeechMs" to 80, "preRollMs" to GuardianSpeechSegmenter.PRE_ROLL_MS,
      "endSilenceMs" to GuardianSpeechSegmenter.END_SILENCE_MS,
      "tailMs" to GuardianSpeechSegmenter.TAIL_MS, "vadMaxInputGain" to 64,
      "gtcrnMaxModelGain" to if (diagnosticWavDirectory != null && voiceProfile == GuardianVoiceProfile.NOISY) 6 else 0,
      "asrInput" to "TRUTH", "gtcrnRole" to if (diagnosticWavDirectory != null && voiceProfile == GuardianVoiceProfile.NOISY) "PAIRED_RECORDING_ONLY" else "DISABLED_ORIGINAL_PCM",
      "gtcrnMaxWaitMs" to 0, "maxSegmentMs" to 8000,
      "rolloverStrategy" to "NEURAL_PAUSE_FIRST",
      "rolloverMinPauseMs" to GuardianSpeechSegmenter.MIN_ROLLOVER_PAUSE_MS,
      "wavEnabled" to (diagnosticWavDirectory != null),
      "profileSource" to if (diagnosticOptions.profileFromFile) "TEST_FILE" else "CONFIG")
    val thisVoiceSessionToken = UUID.randomUUID().toString()
    activeVoiceSessionToken = thisVoiceSessionToken

    // Un résultat asynchrone d'une ancienne session ne doit jamais être
    // transféré vers le VoiceEngine ou le ConversationEngine de la suivante.
    fun isCurrentVoiceSession(): Boolean =
      !serviceDestroyed &&
        activeVoiceSessionToken == thisVoiceSessionToken

    /*
     * Lexical V3.2 - l'identité produit d'une phrase reconstruite est
     * indépendante des originId techniques Nemotron. Les originId produits
     * sont négatifs afin de ne jamais entrer en collision avec les originId
     * positifs attribués aux FINAL techniques.
     */
    val lexicalPublishedUnitTexts = linkedMapOf<Long, String>()

    fun rememberLexicalPublishedUnitText(
      unitId: Long,
      text: String,
    ) {
      lexicalPublishedUnitTexts[unitId] = text

      while (
        lexicalPublishedUnitTexts.size >
          LEXICAL_PUBLISHED_TEXT_MAX_ENTRIES
      ) {
        val oldestKey = lexicalPublishedUnitTexts.keys.firstOrNull() ?: break
        lexicalPublishedUnitTexts.remove(oldestKey)
      }
    }

    fun lexicalOriginId(unitId: Long): Long =
      -unitId.coerceAtLeast(1L)

    fun lexicalRecognitionResult(
      unit: GuardianWordTimelineEngine.Unit,
      text: String = unit.text,
    ): GuardianAsrContract.RecognitionResult =
      GuardianAsrContract.RecognitionResult(
        text = text,
        language = "fr-FR",
        confidence = unit.confidence,
        isFinal = true,
        timestampMs = System.currentTimeMillis(),
        originId = lexicalOriginId(unit.unitId),
        technicalSegmentId = unit.unitId,
        boundaryReason = "LEXICAL_${unit.boundary.name}",
        observedAtElapsedRealtimeMs = unit.createdAtMs,
      )

    val audioRouteEvidenceStore =
      GuardianAudioRouteEvidenceStore(
        applicationContext
      )

    var activeAudioEvidenceRouteKey:
      String? = null

    /*
     * L'apprentissage reste désarmé tant que :
     * - AudioRecord n'est pas réellement RUNNING ;
     * - GuardianVoiceEngine n'est pas réellement RUNNING ;
     * - le Runtime Audio Monitor n'a pas validé le chemin de capture.
     */
    var audioEvidenceCollectionArmed =
      false

    var pendingAudioEvidenceSpeechLevel:
      String? = null

    // Un seul snapshot RAM en attente de FINAL, jamais d'alerte additionnelle.
    var pendingFocusShadow: GuardianVoiceFocusEngine.ShadowUtterance? = null
    var shadowTestReference: String? = null
    var shadowTestScenario: String? = null



    var lastFocusSelected = List(4) { 0L }
    var lastFocusWinner = List(4) { 0L }
    var lastFocusStable = 0L
    var lastFocusTransitions = 0L

    fun canRecordAudioEvidence(): Boolean =
      audioEvidenceCollectionArmed &&
        activeAudioEvidenceRouteKey != null &&
        audioEngine
          ?.isRouteEvidenceValid() ==
          true

    val voiceListener =
      object : GuardianVoiceEngine.Listener {

        override fun onVoiceEvent(
          event: GuardianVoiceEvent
        ) {
          try {
            /*
             * L'événement vocal est rendu durable avant
             * toute tentative réseau.
             */
            store.saveVoiceEvent(
              event
            )

            Log.w(
              TAG,
              "Événement Voice sauvegardé : " +
                "id=${event.eventId}, " +
                "cause=${event.cause}, " +
                "state=${event.state}, " +
                "confidence=${event.confidenceScore}"
            )

            GuardianUploadScheduler.forceUpload(
              applicationContext
            )

            Log.d(
              TAG,
              "Upload Voice immédiat demandé : " +
                "id=${event.eventId}"
            )
          } catch (error: Exception) {
            lastServiceError =
              error.message
                ?: "Échec du stockage de l'événement Voice"

            Log.e(
              TAG,
              "Échec du stockage SQLite Voice",
              error
            )
          }
        }

        override fun onVoiceStatusChanged(
          status: GuardianVoiceEngine.Status
        ) {
          if (!isCurrentVoiceSession()) return

          lastVoiceStatus =
            status

          lastVoiceAudioDiagnostics =
            audioEngine
            ?.getLastStatistics()

          lastVoiceSherpaDiagnostics =
            null

          lastVoiceUnderstandingDiagnostics =
            voiceEngine
            ?.getUnderstandingDiagnostics()

            lastVoiceFusionDiagnostics =
            voiceEngine
            ?.getFusionDiagnostics()

          Log.d(
            TAG,
            "Voice status : " +
              "running=${status.running}, " +
              "recognizer=${status.recognizerAvailable}, " +
              "microphone=${status.microphonePermissionGranted}, " +
              "phase=${status.phase}, " +
              "listening=${status.listening}, " +
              "wakeArmed=${status.wakePhraseArmed}, " +
              "confidence=${status.lastConfidenceScore}, " +
              "wakePhrase=${status.lastWakePhrase}, " +
              "emergencyPhrase=${status.lastEmergencyPhrase}, " +
              "error=${status.lastErrorCode}, " +
              "restarts=${status.restartCount}"
          )
        }

        override fun getLastKnownLocation():
          GuardianVoiceEngine.LastKnownLocation? {

          val latitude =
            GuardianLocationEngine.lastLatitude

          val longitude =
            GuardianLocationEngine.lastLongitude

          if (
            latitude == null ||
            longitude == null
          ) {
            return null
          }

          return GuardianVoiceEngine.LastKnownLocation(
            latitude = latitude,
            longitude = longitude,
            accuracy =
              GuardianLocationEngine.lastAccuracy
          )
        }
      }

      val asrListener =
    object : GuardianAsrContract.Listener {

        override fun onPartialResult(
            result: GuardianAsrContract.RecognitionResult
        ) {
            if (!isCurrentVoiceSession()) return

            wordTimelineEngine?.observePartial(
                segmentId = result.technicalSegmentId,
                text = result.text,
                observedAtMs =
                    result.observedAtElapsedRealtimeMs
                        .takeIf { it > 0L }
                        ?: android.os.SystemClock.elapsedRealtime(),
                confidence = result.confidence,
                evidence = result.wordEvidence,
                source = GuardianWordTimelineEngine.WordSource.QWEN_PRIMARY_PARTIAL,
            )

            voiceEngine?.onSherpaPartialResult(
                result
            )
        }

        override fun onFinalResult(
    result: GuardianAsrContract.RecognitionResult
) {
    if (!isCurrentVoiceSession()) return

    wordTimelineEngine?.observeFinal(
        segmentId = result.technicalSegmentId,
        originId = result.originId,
        text = result.text,
        boundaryReason = result.boundaryReason,
        observedAtMs =
            result.observedAtElapsedRealtimeMs
                .takeIf { it > 0L }
                ?: android.os.SystemClock.elapsedRealtime(),
        confidence = result.confidence,
        evidence = result.wordEvidence,
        recovered = result.boundaryReason?.startsWith("RECOVERY_") == true,
        finalSource = GuardianWordTimelineEngine.WordSource.QWEN_PRIMARY_FINAL,
    )

    if (
        result.text.isNotBlank() &&
        canRecordAudioEvidence()
    ) {
        activeAudioEvidenceRouteKey
            ?.let { routeKey ->
                val snapshot =
                    audioRouteEvidenceStore
                        .recordFinal(
                            routeKey = routeKey,
                            confidence = result.confidence,
                            speechLevel =
                                pendingAudioEvidenceSpeechLevel,
                        )

                if (
                    snapshot.finalCount %
                    AUDIO_EVIDENCE_LOG_INTERVAL_FINALS ==
                    0L
                ) {
                    logAudioRouteEvidence(
                        prefix = "Evidence FINAL",
                        snapshot = snapshot,
                    )
                }
            }
    }

    /*
     * Le speechLevel appartient à cette utterance. Un nouvel onSpeechStarted()
     * le remplacera si aucun FINAL n'a été émis.
     */
    pendingAudioEvidenceSpeechLevel = null

    // Le FINAL immédiat et le Rescue prioritaire sont déjà planifiés par
    // GuardianAsrContract AVANT cet appel de Listener. Shadow vient après.
    pendingFocusShadow = null


    audioEngine?.getVoiceFocusSnapshot()?.let { focus ->
        Log.i("GuardianVoiceFocus",
            "UTTERANCE FOCUS (diagnostic, pas accuracy ASR) : " +
                "selected=${focus.selected}, state=${focus.state}, " +
                "scoresDb=${focus.scoresDb}, stableLag=${focus.relativeLagStable}, " +
                "lag=${focus.relativeLagSamples}, transitions=${focus.transitionCount}, " +
                "fusionGainDb=${focus.acceptedFusionGainDb}, " +
                "evidenceReady=${focus.readyForEvidence}")
    }

    /*
     * Lexical V3.2 - un FINAL Sherpa est désormais une frontière TECHNIQUE.
     *
     * Il alimente la Word Timeline et le diagnostic/Qwen, mais il n'est plus
     * envoyé comme phrase produit à GuardianVoiceEngine/ConversationEngine.
     * Seule une NATURAL_PHRASE émise par GuardianWordTimelineEngine devient
     * autoritaire pour le chemin produit (voir onUnitReady ci-dessous).
     *
     * Les PARTIAL restent envoyés en temps réel plus haut afin de conserver la
     * réactivité d'urgence pendant que la phrase complète se construit.
     */
    Log.d(
        "GuardianWordTimeline",
        "TECHNICAL FINAL CAPTURED : " +
            "segmentId=${result.technicalSegmentId}, " +
            "originId=${result.originId ?: -1L}, " +
            "boundary=${result.boundaryReason}, " +
            "text=\"${result.text}\""
    )
    }

        override fun onLexicalWordResolution(
            result: GuardianAsrContract.LexicalWordResolutionResult,
        ) {
            if (!isCurrentVoiceSession()) return

            val applied = wordTimelineEngine?.completeWordResolution(
                wordId = result.wordId,
                originalWord = result.originalWord,
                replacementWord = result.replacementWord,
                observedAtMs = result.observedAtElapsedRealtimeMs,
            ) ?: false

            Log.i(
                "GuardianWordTimeline",
                "WORD REVIEW COMPLETE : " +
                    "wordId=${result.wordId}, " +
                    "original=${result.originalWord}, " +
                    "replacement=${result.replacementWord ?: "KEEP"}, " +
                    "views=${result.supportingViews}, " +
                    "applied=$applied"
            )
        }

        /*
         * Lot 6L - la conversation protège explicitement l'originId tant que
         * la FINAL_REVIEW secondaire est réellement en cours. Ces callbacks
         * arrivent indépendamment de la température du modèle Qwen.
         */
        override fun onFinalReviewStarted(
            originId: Long,
        ) {
            if (!isCurrentVoiceSession()) return

            conversationEngine
                ?.onFinalReviewStarted(
                    originId
                )
        }

        override fun onFinalReviewCompleted(
            originId: Long,
        ) {
            if (!isCurrentVoiceSession()) return

            conversationEngine
                ?.onFinalReviewCompleted(
                    originId
                )
        }

        /*
         * Guardian Dual-ASR V1.4.
         *
         * Qwen ne rappelle jamais onFinalResult().
         * Une correction validée par consensus arrive ici sur un canal
         * distinct afin d'éviter tout double événement Voice.
         *
         * GuardianVoiceEngine garde la responsabilité produit :
         * - ré-analyser la correction dans Understanding/Fusion ;
         * - ne jamais dupliquer une alerte déjà confirmée par le FINAL primaire ;
         * - pouvoir récupérer une urgence manquée si le primaire n'avait
         *   produit aucun événement critique.
         */
        override fun onFinalCorrection(
            original: GuardianAsrContract.RecognitionResult,
            corrected: GuardianAsrContract.RecognitionResult,
        ) {
            if (!isCurrentVoiceSession()) return

            val originalText =
                original.text.trim()

            val correctedText =
                corrected.text.trim()

            if (
                originalText.isBlank() ||
                correctedText.isBlank() ||
                originalText == correctedText
            ) {
                return
            }

            val lexicalCorrection =
                wordTimelineEngine?.applyCorrection(
                    originId = original.originId,
                    correctedText = correctedText,
                    observedAtMs = android.os.SystemClock.elapsedRealtime(),
                    confidence = corrected.confidence,
                )

            if (lexicalCorrection?.applied == true) {
                Log.i(
                    "GuardianWordTimeline",
                    "QWEN WORD CORRECTION : " +
                        "originId=${original.originId ?: -1L}, " +
                        "changed=${lexicalCorrection.changedWords}, " +
                        "inserted=${lexicalCorrection.insertedWords}, " +
                        "deleted=${lexicalCorrection.deletedWords}"
                )
            }

            if (canRecordAudioEvidence()) {
                activeAudioEvidenceRouteKey
                    ?.let { routeKey ->
                        val snapshot =
                            audioRouteEvidenceStore
                                .recordQwenCorrection(
                                    routeKey
                                )

                        logAudioRouteEvidence(
                            prefix = "Evidence Qwen correction (diagnostic)",
                            snapshot = snapshot,
                        )
                    }
            }

            Log.i(
                TAG,
                "ASR QWEN FINAL CORRECTION : " +
                    "original=\"$originalText\", " +
                    "corrected=\"$correctedText\""
            )

            /*
             * La correction technique Qwen ne touche plus directement le
             * produit. Elle corrige les wordId de la timeline. Si la phrase a
             * déjà été publiée, onUnitCorrected() répercutera la révision sur
             * le même originId lexical sans créer un second événement.
             */
            Log.i(
                TAG,
                "ASR FINAL CORRECTION ROUTING : " +
                    "technicalOriginId=${original.originId ?: -1L}, " +
                    "lexicalApplied=${lexicalCorrection?.applied == true}, " +
                    "productRouting=WORD_TIMELINE"
            )
        }

        override fun onSpeechStarted() {
            if (!isCurrentVoiceSession()) return
            pendingFocusShadow = null
            // Le ring contient déjà 3 s du même PCM; le lexical COMMIT peut
            // arriver en retard, sans sacrifier le début de la phrase.
            if (audioEngine?.getVoiceFocusMode() == GuardianVoiceFocusMode.OBSERVE)
                audioEngine?.beginFocusShadowUtterance()
            pendingAudioEvidenceSpeechLevel =
                if (canRecordAudioEvidence()) {
                    audioEngine
                        ?.getCurrentSpeechLevelName()
                        ?.takeIf { level ->
                            level == "DISTANT_SPEECH" ||
                                level == "CLEAR_SPEECH"
                        }
                } else {
                    null
                }

            if (canRecordAudioEvidence()) {
                activeAudioEvidenceRouteKey
                    ?.let { routeKey ->
                        audioRouteEvidenceStore
                            .recordSpeechCommit(
                                routeKey
                            )
                    }
            }

            audioEngine?.setVoiceActivityHint(true)
            conversationEngine
             ?.onSpeechStarted()
      }

        override fun onSpeechEnded() {
          if (!isCurrentVoiceSession()) return
          if (audioEngine?.getVoiceFocusMode() == GuardianVoiceFocusMode.OBSERVE)
              pendingFocusShadow = audioEngine?.endFocusShadowUtterance()
          audioEngine?.setVoiceActivityHint(false)
          val currentAudio = audioEngine
          val focus = currentAudio?.getVoiceFocusSnapshot()
          if (focus != null && canRecordAudioEvidence()) {
              val routeKey = activeAudioEvidenceRouteKey
              if (routeKey != null) {
                  val selected = focus.selectedSpeechFrameCounts.mapIndexed { i, count ->
                      (count - lastFocusSelected[i]).coerceAtLeast(0L)
                  }
                  val winners = focus.acousticBestViewFrameCounts.mapIndexed { i, count ->
                      (count - lastFocusWinner[i]).coerceAtLeast(0L)
                  }
                  val stableDelta = (focus.alignedSpeechFrameCount - lastFocusStable)
                      .coerceAtLeast(0L)
                  val transitionsDelta = (focus.transitionCount - lastFocusTransitions)
                      .coerceAtLeast(0L)
                  val summary = audioRouteEvidenceStore.recordConfirmedFocusAcoustics(
                      routeKey = routeKey + "_" + (currentAudio?.getVoiceFocusMode()?.name ?: "UNKNOWN"),
                      selectedFrameDelta = selected,
                      acousticWinnerDelta = winners,
                      stableAlignedDelta = stableDelta,
                      transitionsDelta = transitionsDelta,
                  )
                  lastFocusSelected = focus.selectedSpeechFrameCounts
                  lastFocusWinner = focus.acousticBestViewFrameCounts
                  lastFocusStable = focus.alignedSpeechFrameCount
                  lastFocusTransitions = focus.transitionCount
                  Log.i("GuardianAudioEvidence", "Focus confirmed acoustic evidence: " +
                      "selected=${summary.selectedFrames}, shadowBest=${summary.acousticWinnerFrames}, " +
                      "stableAligned=${summary.stableAlignedFrames}, transitions=${summary.transitions} " +
                      "(NO ASR ACCURACY INFERRED)")
              }
          }
          conversationEngine
          ?.onSpeechEnded()
      }

        override fun onVoiceFocusShadowDiagnostic(
            result: GuardianAsrContract.FocusShadowResult,
        ) {
            val reference = shadowTestReference
            val scenario = shadowTestScenario
            val currentRouteKey = activeAudioEvidenceRouteKey
            if (result.sessionToken != activeVoiceSessionToken || serviceDestroyed) return
            if (result.completedViews >= 3 && !result.interrupted &&
                reference != null && scenario != null && currentRouteKey != null &&
                canRecordAudioEvidence()) {
                runCatching {
                    val review = audioRouteEvidenceStore.recordControlledShadowReference(
                        routeKey = currentRouteKey,
                        scenario = scenario,
                        referenceText = reference,
                        primaryText = result.primaryText,
                        shadowTextByView = result.textByView,
                    )
                    Log.i("GuardianAudioEvidence", "CONTROLLED SHADOW WER : " +
                        "scenario=${review.scenario}, " +
                        "trials=${review.scores.mapValues { it.value.trials }}, " +
                        "wer=${review.scores.mapValues { it.value.wer }}, " +
                        "NOT_AUTO_PROMOTED")
                }.onFailure { error ->
                    Log.w("GuardianAudioEvidence", "Shadow reference refusée, " +
                        "le moteur de protection n'est pas affecté", error)
                }
            }
            Log.i("GuardianVoiceFocus", "SHADOW ASR (diagnostic ONLY) : " +
                "views=${result.textByView}, primary=${result.primaryText}, " +
                "completed=${result.completedViews}/4, " +
                "alignedMeaningful=${result.alignedMeaningful}, " +
                "interrupted=${result.interrupted}, " +
                "elapsedMs=${result.elapsedMs}; " +
                "WITHOUT_REFERENCE_NO_WER_NO_AUTO_PROMOTION")
        }

        override fun onRecognizerError(
            error: Throwable
        ) {
            if (!isCurrentVoiceSession()) return
            if (canRecordAudioEvidence()) {
                activeAudioEvidenceRouteKey
                    ?.let { routeKey ->
                        audioRouteEvidenceStore
                            .recordRecognizerError(
                                routeKey
                            )
                    }
            }

            Log.e(
                TAG,
                "Erreur Sherpa",
                error
            )
        }
    }

    val engine =
      GuardianVoiceEngine(
        context = applicationContext,
        config = config,
        settings =
          GuardianVoiceEngine.Settings(
            enabled =
              config.voiceEnabled
          ),
        listener = voiceListener
      )

    voiceEngine =
      engine

    val conversation =
    GuardianConversationEngine(
        listener =
            object :
                GuardianConversationEngine.Listener {

                override fun onConversationFinalized(
                    assembledText: String,
                    segmentCount: Int,
                ) {
                    if (!isCurrentVoiceSession()) return
                    Log.i(
                        "GuardianConversation",
                        "CONTEXTE FINAL : " +
                            "segments=$segmentCount, " +
                            "text=\"$assembledText\""
                    )

                    lastVoiceConversationFinalText =
                      assembledText

                    lastVoiceConversationSegmentCount =
                      segmentCount

                    lastVoiceConversationFinalizedAt =
                      System.currentTimeMillis()

                    voiceEngine
                      ?.onConversationFinalized(
                      text =
                      assembledText,

                      segmentCount =
                      segmentCount,
                  )
                }
            }
    )

conversationEngine =
    conversation

    val lexicalTimeline =
        GuardianWordTimelineEngine(
            configuration =
                GuardianWordTimelineEngine.Configuration(
                    // Hard cap lexical produit : 24 mots actifs.
                    maxActiveWords = 24,
                    correctionHistoryWords = 96,
                    stableTrailingWords = 2,
                    // Watchdog technique ultime, jamais une définition de phrase.
                    maxOpenContextAgeMs = 45_000L,
                ),
            listener =
                object : GuardianWordTimelineEngine.Listener {
                    override fun onWordNeedsResolution(
                        request: GuardianWordTimelineEngine.WordResolutionRequest,
                    ) {
                        if (!isCurrentVoiceSession()) return

                        val accepted = false // Aucun second ASR lexical : Qwen principal uniquement.

                        if (!accepted) {
                            wordTimelineEngine?.releaseWordResolutionRequest(
                                request.wordId
                            )
                        }

                        Log.d(
                            "GuardianWordTimeline",
                            "WORD REVIEW REQUEST : " +
                                "wordId=${request.wordId}, " +
                                "word=${request.originalWord}, " +
                                "logProb=${request.averageLogProb}, " +
                                "observations=${request.observationCount}, " +
                                "reason=${request.doubtReason}, " +
                                "audio=${request.contextStartMs}-${request.contextEndMs}, " +
                                "accepted=$accepted"
                        )
                    }

                    override fun onUnitReady(
                        unit: GuardianWordTimelineEngine.Unit,
                    ) {
                        if (!isCurrentVoiceSession()) return

                        Log.i(
                            "GuardianWordTimeline",
                            "UNIT READY : " +
                                "id=${unit.unitId}, " +
                                "type=${unit.type}, " +
                                "boundary=${unit.boundary}, " +
                                "words=${unit.wordIds.size}, " +
                                "confidence=${unit.confidence ?: -1f}, " +
                                "text=\"${unit.text}\""
                        )

                        // CONTEXT_CHUNK est un bloc de continuité, pas une fin de phrase.
                        // Il contient des mots non chevauchants et doit rejoindre le
                        // chemin produit, sinon les 24 premiers mots disparaissent.
                        // Chaque unitId est stable pour permettre les corrections Qwen.
                        val isContextChunk =
                            unit.type == GuardianWordTimelineEngine.UnitType.CONTEXT_CHUNK
                        if (unit.text.isBlank()) return

                        Log.i(
                            "GuardianWordTimeline",
                            "PRODUCT UNIT DISPATCH : unitId=${unit.unitId}, " +
                                "contextChunk=$isContextChunk, boundary=${unit.boundary}"
                        )

                        val productOriginId = lexicalOriginId(unit.unitId)
                        rememberLexicalPublishedUnitText(unit.unitId, unit.text)

                        /*
                         * Autorité produit : unités lexicales disjointes.
                         * NATURAL_PHRASE = frontière naturelle ; CONTEXT_CHUNK =
                         * fenêtre de contexte de 24 mots (aucune frontière inventée).
                         * Le même texte n'est publié qu'une fois par unitId.
                         */
                        voiceDiagnostics?.event("PRODUCT_TEXT", "unitId" to unit.unitId,
                            "boundary" to unit.boundary.name, "text" to unit.text,
                            "contextChunk" to isContextChunk)
                        val lexicalResult = lexicalRecognitionResult(unit)
                        voiceEngine?.onSherpaFinalResult(lexicalResult)

                        /*
                         * Le contexte conversationnel reçoit exactement la même
                         * phrase. On protège brièvement son originId afin qu'une
                         * correction Qwen tardive puisse encore remplacer cette
                         * phrase après finalisation sans la dupliquer.
                         */
                        conversationEngine?.onFinalReviewStarted(productOriginId)
                        conversationEngine?.addSegment(
                            GuardianConversationEngine.Segment(
                                text = unit.text,
                                originId = productOriginId,
                                confidence = unit.confidence,
                            )
                        )
                    }

                    override fun onUnitCorrected(
                        unit: GuardianWordTimelineEngine.Unit,
                    ) {
                        if (!isCurrentVoiceSession()) return

                        Log.i(
                            "GuardianWordTimeline",
                            "UNIT CORRECTED : " +
                                "id=${unit.unitId}, " +
                                "text=\"${unit.text}\""
                        )

                        // Les corrections tardives des CONTEXT_CHUNK suivent le même
                        // originId stable. Ne jamais recréer une seconde unité.
                        val previousText = lexicalPublishedUnitTexts[unit.unitId]
                            ?.takeIf { it.isNotBlank() }
                            ?: return

                        if (previousText == unit.text) return

                        val productOriginId = lexicalOriginId(unit.unitId)
                        val originalResult = lexicalRecognitionResult(unit, previousText)
                        val correctedResult = lexicalRecognitionResult(unit, unit.text)

                        rememberLexicalPublishedUnitText(unit.unitId, unit.text)

                        val conversationUpdated =
                            conversationEngine?.correctSegment(
                                originId = productOriginId,
                                correctedText = unit.text,
                                confidence = unit.confidence,
                            ) ?: false

                        /*
                         * Ne pas libérer ici la protection conversationnelle :
                         * plusieurs mots de la même phrase peuvent être corrigés
                         * successivement. GuardianConversationEngine possède déjà
                         * un TTL de sécurité borné pour ce snapshot.
                         */
                        voiceEngine?.onSherpaFinalCorrection(
                            original = originalResult,
                            corrected = correctedResult,
                        )

                        Log.i(
                            "GuardianWordTimeline",
                            "PRODUCT PHRASE CORRECTED : " +
                                "unitId=${unit.unitId}, " +
                                "originId=$productOriginId, " +
                                "conversationUpdated=$conversationUpdated"
                        )
                    }
                },
        )

    wordTimelineEngine =
        lexicalTimeline

    lexicalTimelineJob?.cancel()
    lexicalTimelineJob =
        serviceScope.launch {
            while (!serviceDestroyed && isCurrentVoiceSession()) {
                wordTimelineEngine?.tick(
                    android.os.SystemClock.elapsedRealtime()
                )
                delay(1_000L)
            }
        }

val qwenPrimaryRecognizer = GuardianQwenRescueRecognizer(
        applicationContext,
        GuardianQwenRescueRecognizer.Configuration(
            enabled = true,
            modelDirectoryName = "sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25",
            numberOfThreads = 2,
            forcedLanguage = "French",
            hotwords = emptyList(),
            maxTotalLen = 512,
            maxNewTokens = 256,
            temperature = 1e-6f,
        ),

)

fun qwenAsRecognitionResult(
    hypothesis: GuardianQwenProgressiveDecoder.Hypothesis,
): GuardianAsrContract.RecognitionResult =
    GuardianAsrContract.RecognitionResult(
        text = hypothesis.text,
        language = hypothesis.language,
        confidence = null, // Qwen offline ne fournit pas de score mot fiable.
        isFinal = hypothesis.isFinal,
        originId = if (hypothesis.isFinal) hypothesis.segmentId else null,
        technicalSegmentId = hypothesis.segmentId,
        boundaryReason = hypothesis.boundaryReason ?: "QWEN_PROGRESSIVE_PARTIAL",
        observedAtElapsedRealtimeMs = hypothesis.observedAtElapsedRealtimeMs,
    )

val qwenPrimary = qwenPrimaryRecognizer.let { qwen ->
    val decoder = GuardianQwenProgressiveDecoder(
        qwen = qwen,
        diagnostics = voiceDiagnostics,
        listener = object : GuardianQwenProgressiveDecoder.Listener {
            override fun onPartial(hypothesis: GuardianQwenProgressiveDecoder.Hypothesis) {
                if (!isCurrentVoiceSession()) return
                if (hypothesis.acousticEvidence !=
                    GuardianQwenProgressiveDecoder.AcousticEvidence.NEURAL_CONFIRMED) {
                    Log.i(TAG, "QWEN UNTRUSTED PARTIAL (diagnostic only) : segment=${hypothesis.segmentId}, " +
                        "reason=NO_NEURAL_VOICE_PROOF, chars=${hypothesis.text.length}")
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "QWEN ACOUSTIC SHADOW PARTIAL : " +
                            "segment=${hypothesis.segmentId}, text=\"${hypothesis.text.take(160)}\"")
                    }
                    return
                }
                asrListener.onPartialResult(qwenAsRecognitionResult(hypothesis))
            }
            override fun onFinal(hypothesis: GuardianQwenProgressiveDecoder.Hypothesis) {
                if (!isCurrentVoiceSession()) return
                if (hypothesis.acousticEvidence !=
                    GuardianQwenProgressiveDecoder.AcousticEvidence.NEURAL_CONFIRMED) {
                    Log.i(TAG, "QWEN UNTRUSTED FINAL (diagnostic only) : segment=${hypothesis.segmentId}, " +
                        "reason=NO_NEURAL_VOICE_PROOF, chars=${hypothesis.text.length}")
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "QWEN ACOUSTIC SHADOW FINAL : " +
                            "segment=${hypothesis.segmentId}, text=\"${hypothesis.text.take(160)}\"")
                    }
                    return
                }
                asrListener.onFinalResult(qwenAsRecognitionResult(hypothesis))
            }
            override fun onDecoderUnavailable(segmentId: Long, isFinal: Boolean) {
                if (!isCurrentVoiceSession()) return
                Log.e(TAG, "Qwen decode indisponible: segment=$segmentId final=$isFinal")
            }
            override fun onBackpressure(segmentId: Long, pendingFinalCount: Int) {
                if (!isCurrentVoiceSession()) return
                Log.e(TAG, "Qwen FINAL backpressure: segment=$segmentId pending=$pendingFinalCount")
            }
        },
    )
    try { GuardianQwenPrimaryAudioProcessor(
        context = applicationContext,
        decoder = decoder,
        profile = voiceProfile,
        diagnostics = voiceDiagnostics,
        diagnosticWavDirectory = diagnosticWavDirectory,
        listener = object : GuardianQwenPrimaryAudioProcessor.Listener {
            override fun onSpeechStarted() {
                if (isCurrentVoiceSession()) asrListener.onSpeechStarted()
            }
            override fun onSpeechEnded() {
                if (isCurrentVoiceSession()) asrListener.onSpeechEnded()
            }
            override fun onWarning(message: String) {
                if (isCurrentVoiceSession()) Log.w(TAG, message)
            }
        },
    ).also { qwenPrimaryProcessor = it } } catch (error: Throwable) {
        // The decoder owns Qwen; release it if native admission cannot start.
        decoder.close()
        voiceDiagnostics?.event("FIRERED_START_FAILED", "error" to error.javaClass.simpleName)
        throw error
    }
}

  val audioCapabilities =
    GuardianAudioCapabilities.detect(
        applicationContext
    )
    lastVoiceAudioCapabilities =
    audioCapabilities

    Log.i(
    "GuardianAudioCapabilities",
    "Profil détecté par Guardian : " +
        "source=${audioCapabilities.recommendedAudioSourceName}, " +
        "profile=${audioCapabilities.performanceProfile}, " +
        "headroom=${audioCapabilities.realtimeHeadroomRatio}"
)

    /*
 * Probe matériel Guardian.
 *
 * Il est exécuté avant le démarrage du vrai
 * GuardianAudioEngine afin d'éviter deux captures
 * AudioRecord concurrentes.
 */
val audioProbeResults =
    GuardianAudioCapabilities
        .probeCaptureConfigurations(
            applicationContext
        )

Log.i(
    "GuardianAudioProbe",
    "Probe audio terminé : " +
        "${audioProbeResults.size} configurations testées"
)

audioProbeResults.forEach { result ->
    Log.i(
        "GuardianAudioProbe",
        "RESULT : " +
            "source=${result.sourceName}, " +
            "mode=${result.channelModeName}, " +
            "initialized=${result.initialized}, " +
            "recording=${result.recordingStarted}, " +
            "channels=${result.channelCount}, " +
            "activeMics=${result.activeMicrophoneCount}, " +
            "microphones=${result.activeMicrophoneDescriptions}, " +
            "mapping=${result.channelMappings}, " +
            "direction=${result.directionApplied}, " +
            "field=${result.fieldApplied}, " +
            "error=${result.error}"
    )
}

    /*
 * Guardian Adaptive Audio.
 *
 * Transforme les résultats bruts du probe matériel
 * en profil de capture exploitable par Guardian.
 *
 * IMPORTANT :
 * à ce stade le profil est uniquement diagnostique.
 * Il ne change pas encore automatiquement la route
 * audio de production.
 */
val adaptiveCaptureProfile =
    GuardianAudioCapabilities
        .resolveAdaptiveCaptureProfile(
            audioProbeResults
        )

Log.i(
    "GuardianAudioProfile",
    "Profil adaptatif : " +
        "topology=${adaptiveCaptureProfile.topology}, " +
        "opened=${adaptiveCaptureProfile.maximumOpenedChannelCount}, " +
        "usable=${adaptiveCaptureProfile.maximumUsableChannelCount}, " +
        "independent=${adaptiveCaptureProfile.maximumIndependentChannelCount}, " +
        "dualMicSpatial=${adaptiveCaptureProfile.dualMicSpatialAvailable}, " +
        "runtimeChannels=${adaptiveCaptureProfile.runtimeChannelCount}, " +
        "reason=${adaptiveCaptureProfile.reason}"
)

    /*
 * B5.1 - Warm-up Sherpa avant capture.
 *
 * Le recognizer, Silero VAD et le premier stream
 * doivent être complètement prêts AVANT que
 * GuardianAudioEngine ouvre AudioRecord.
 *
 * Cela évite que le chargement ONNX bloque
 * le thread de traitement des premières frames.
 */
try {
    Log.i(
        TAG,
        "Initialisation Sherpa avant démarrage microphone..."
    )

    voiceDiagnostics?.event("QWEN_LOAD_START")
    check(qwenPrimaryRecognizer.initializeIfAvailable()) {
        "Qwen primaire non disponible : verifier les modeles dans le stockage prive"
    }
    Log.i(TAG, "Qwen PRIMARY READY avant demarrage microphone")
    voiceDiagnostics?.event("QWEN_LOAD_READY")

} catch (error: Throwable) {

    conversation.destroy()

    conversationEngine =
        null

    qwenPrimary?.close()
    if (qwenPrimaryProcessor === qwenPrimary) qwenPrimaryProcessor = null

    voiceEngine =
        null

    engine.destroy()

    throw IllegalStateException(
        "Échec du warm-up Sherpa avant démarrage audio",
        error
    )
}

/*
 * Phase C8.1 - préparation de la session Speech Enhancement.
 *
 * Ces décisions appartiennent au service et doivent être
 * calculées AVANT la construction de GuardianAudioEngine.Configuration.
 */
val gtcrnAllowedForSession = diagnosticWavDirectory != null && voiceProfile == GuardianVoiceProfile.NOISY // Paired recording only; original admission/ASR unchanged.

Log.i(TAG, "VOICE_PROFILE=$voiceProfile neuralEnhancementRecommended=${audioCapabilities.neuralEnhancementAllowed}")

/*
 * Diagnostic capture Android.
 *
 * Sans fichier override, le comportement production reste strictement
 * identique : Guardian utilise recommendedAudioSource.
 *
 * Avec guardian-audio-source.txt, le même APK peut comparer
 * VOICE_RECOGNITION et MIC sans nouveau build.
 */
val selectedAudioSource =
    resolveDiagnosticAudioSource(
        audioCapabilities
    )

val selectedMicrophoneField =
    resolveDiagnosticMicrophoneField()

val requestedMultiMic =
    resolveDiagnosticMultiMic()

// Référence lexicale explicite, uniquement dans le mode de TEST FOCUS_OBSERVE.
// Ne jamais comparer à une phrase supposée en usage normal.
if (requestedMultiMic.focusObserve) {
    val external = applicationContext.getExternalFilesDir(null)
    shadowTestReference = external?.let { root ->
        runCatching { File(root, "guardian-shadow-reference.txt").readText().trim() }
            .getOrNull()?.takeIf { it.isNotBlank() && it.length <= 250 }
    }
    shadowTestScenario = external?.let { root ->
        runCatching { File(root, "guardian-shadow-scenario.txt").readText().trim().uppercase() }
            .getOrNull()?.takeIf { it in setOf("FRONT", "MOVING", "POCKET") }
    }
    Log.i("GuardianAudioEvidence", "Controlled reference armed=" +
        "${shadowTestReference != null && shadowTestScenario != null}, " +
        "scenario=${shadowTestScenario ?: "NONE"}")
}


/*
 * Guardian Adaptive Audio AUTO V1.
 *
 * Le plan automatique n'est utilisé que lorsque les DEUX diagnostics
 * sont réellement en AUTO. Un override manuel conserve exactement la
 * priorité historique.
 */
val automaticCaptureRoutePlan =
    if (
        !selectedAudioSource.explicitOverride &&
        !requestedMultiMic.explicitOverride
    ) {
        GuardianAudioCapabilities
            .resolveAutomaticCaptureRoutePlan(
                capabilities =
                    audioCapabilities,
                probeResults =
                    audioProbeResults,
            )
    } else {
        null
    }

automaticCaptureRoutePlan?.let { plan ->
    Log.i(
        "GuardianAudioProfile",
        "AUTO Route Plan : " +
            "selected=${plan.selected.label}, " +
            "selectedClass=${plan.selected.qualification?.classification}, " +
            "challenger=${plan.challenger?.label}, " +
            "challengerClass=${plan.challenger?.qualification?.classification}, " +
                "structuralConfidence=${plan.confidence}, " +
            "comparativeSpeechPerformance=UNVERIFIED, " +
            "reason=${plan.reason}"
    )

    plan.candidates.forEach { candidate ->
        Log.i(
            "GuardianAudioProfile",
            "AUTO Candidate : " +
                "label=${candidate.label}, " +
                "eligible=${candidate.structurallyEligible}, " +
                "class=${candidate.qualification?.classification}, " +
                "usable=${candidate.qualification?.usableChannelCount}, " +
                "independent=${candidate.qualification?.independentChannelCount}, " +
                "evidenceRequired=${candidate.performanceEvidenceRequired}, " +
                "reason=${candidate.reason}"
        )
    }
}

/*
 * Guardian Adaptive Audio Evidence V2 - audit Step 6.
 *
 * Toutes les routes alternatives structurellement connues sont évaluées.
 * Une mauvaise première alternative ne peut donc plus empêcher Guardian
 * d'apprendre une seconde route potentiellement meilleure.
 */
val automaticBaselineEvidenceRouteKey =
    automaticCaptureRoutePlan
        ?.selected
        ?.let { candidate ->
            automaticAudioEvidenceRouteKey(
                candidate
            )
        }

val automaticAlternativeCandidates =
    automaticCaptureRoutePlan
        ?.let { plan ->
            plan.candidates
                // Pas de promotion/exploration AUTO du Focus sans validation
                // lexicale contre phrase de référence. FOCUS manuel disponible.
                .filter { it.label != plan.selected.label &&
                    it.label != "MIC_STEREO_VOICE_FOCUS" }
                // Ordre EXPLICITE et diagnostique : Focus observe les 4 vues
                // simultanément, puis on confirme chaque canal séparément.
                // Ce n'est en aucun cas un classement de performances.
                .sortedBy { candidate ->
                    when (candidate.label) {
                        "MIC_STEREO_VOICE_FOCUS" -> 0
                        "MIC_STEREO_CHANNEL_0" -> 1
                        "MIC_STEREO_CHANNEL_1" -> 2
                        "MIC_MONO" -> 3
                        else -> 99
                    }
                }
        }
        ?: emptyList()

val automaticPromotionReviews =
    if (
        automaticBaselineEvidenceRouteKey !=
        null
    ) {
        automaticAlternativeCandidates
            .map { candidate ->
                candidate to
                    audioRouteEvidenceStore
                        .reviewPromotion(
                            baselineRouteKey =
                                automaticBaselineEvidenceRouteKey,
                            challengerRouteKey =
                                automaticAudioEvidenceRouteKey(
                                    candidate
                                ),
                        )
            }
    } else {
        emptyList()
    }

/*
 * Il ne doit normalement exister qu'une promotion active. Si une ancienne
 * version en a laissé plusieurs, on n'en active qu'une et les autres restent
 * sans effet jusqu'à leur nettoyage par une future démotion.
 */
val automaticActivePromotion =
    automaticPromotionReviews
        .firstOrNull { (_, review) ->
            review.promotionActive
        }

val automaticEvidenceComparisons =
    if (
        automaticBaselineEvidenceRouteKey !=
        null
    ) {
        automaticAlternativeCandidates
            .filter { candidate ->
                candidate.structurallyEligible
            }
            .map { candidate ->
                candidate to
                    audioRouteEvidenceStore
                        .compare(
                            baselineRouteKey =
                                automaticBaselineEvidenceRouteKey,
                            challengerRouteKey =
                                automaticAudioEvidenceRouteKey(
                                    candidate
                                ),
                        )
            }
    } else {
        emptyList()
    }

automaticEvidenceComparisons
    .forEach { (candidate, comparison) ->
        Log.i(
            "GuardianAudioEvidence",
            "AUTO comparison : " +
                "candidate=${candidate.label}, " +
                "decision=${comparison.decision}, " +
                "positiveSignals=${comparison.positiveSignals}, " +
                "qwenCorrectionRateDiagnostic=" +
                "${"%.3f".format(comparison.challenger.qwenCorrectionRate)}, " +
                "reason=${comparison.reason}"
        )
    }

automaticPromotionReviews
    .forEach { (candidate, review) ->
        if (
            review.promotionActive ||
            review.lastDemotedAtMs !=
            null
        ) {
            Log.i(
                "GuardianAudioEvidence",
                "AUTO promotion review : " +
                    "candidate=${candidate.label}, " +
                    "active=${review.promotionActive}, " +
                    "decision=${review.decision}, " +
                    "negativeSignals=${review.negativeSignals}, " +
                    "severe=${review.severeRegression}, " +
                    "cooldown=${review.cooldownActive}, " +
                    "repromotionEvidenceReady=${review.repromotionEvidenceReady}, " +
                    "repromotionBlocked=${review.repromotionBlocked}, " +
                    "reason=${review.reason}"
            )
        }
    }

/*
 * Démotion :
 * - régression post-promotion ;
 * - OU la route promue n'est plus structurellement admissible au probe
 *   courant.
 */
val automaticDemotionCandidate =
    automaticActivePromotion
        ?.takeIf { (candidate, review) ->
            review.demoteToBaseline ||
                !candidate.structurallyEligible
        }
        ?.first

val automaticDemotionReview =
    automaticActivePromotion
        ?.takeIf { (candidate, review) ->
            review.demoteToBaseline ||
                !candidate.structurallyEligible
        }
        ?.second

val automaticDemotionPending =
    automaticDemotionCandidate !=
        null

/*
 * Une nouvelle promotion n'est possible que lorsqu'aucune promotion n'est
 * déjà active.
 */
val automaticPromotionCandidate =
    if (automaticActivePromotion == null) {
        automaticEvidenceComparisons
            .firstOrNull { (candidate, comparison) ->
                val review =
                    automaticPromotionReviews
                        .firstOrNull { (reviewCandidate, _) ->
                            reviewCandidate.label ==
                                candidate.label
                        }
                        ?.second

                candidate.structurallyEligible &&
                    comparison.promoteChallenger &&
                    review?.repromotionBlocked !=
                    true
            }
            ?.first
    } else {
        null
    }

val automaticPromotionPending =
    automaticPromotionCandidate !=
        null

/*
 * Exploration contrôlée de plusieurs challengers.
 *
 * Le store possède maintenant un cooldown GLOBAL par baseline en plus du
 * quota propre à chaque paire. On ne peut donc jamais explorer deux routes
 * différentes sur des redémarrages consécutifs.
 */
val automaticExplorationDecisions =
    if (
        automaticActivePromotion ==
            null &&
        !automaticPromotionPending &&
        automaticBaselineEvidenceRouteKey !=
            null
    ) {
        automaticAlternativeCandidates
            .filter { candidate ->
                candidate.structurallyEligible
            }
            .map { candidate ->
                val review =
                    automaticPromotionReviews
                        .firstOrNull { (reviewCandidate, _) ->
                            reviewCandidate.label ==
                                candidate.label
                        }
                        ?.second

                val comparison =
                    automaticEvidenceComparisons
                        .firstOrNull { (comparisonCandidate, _) ->
                            comparisonCandidate.label ==
                                candidate.label
                        }
                        ?.second

                val decision =
                    if (
                        review?.promotionActive ==
                            true ||
                        review?.cooldownActive ==
                            true ||
                        (
                            comparison?.promoteChallenger ==
                                true &&
                                review?.repromotionBlocked !=
                                true
                            )
                    ) {
                        null
                    } else {
                        audioRouteEvidenceStore
                            .decideExploration(
                                baselineRouteKey =
                                    automaticBaselineEvidenceRouteKey,
                                challengerRouteKey =
                                    automaticAudioEvidenceRouteKey(
                                        candidate
                                    ),
                            )
                    }

                candidate to decision
            }
    } else {
        emptyList()
    }

automaticExplorationDecisions
    .forEach { (candidate, exploration) ->
        if (exploration != null) {
            Log.i(
                "GuardianAudioEvidence",
                "AUTO exploration : " +
                    "candidate=${candidate.label}, " +
                    "decision=${exploration.decision}, " +
                    "attempts=${exploration.explorationAttemptCount}, " +
                    "baselineSessionsSinceLast=${exploration.baselineSessionsSinceLastExploration}, " +
                    "elapsedMs=${exploration.elapsedSinceLastExplorationMs ?: -1L}, " +
                    "reason=${exploration.reason}"
            )
        }
    }

val automaticExplorationCandidate =
    automaticExplorationDecisions
        .firstOrNull { (_, decision) ->
            decision?.exploreChallenger ==
                true
        }
        ?.first

val automaticExplorationDecision =
    automaticExplorationDecisions
        .firstOrNull { (candidate, _) ->
            candidate.label ==
                automaticExplorationCandidate
                    ?.label
        }
        ?.second

val automaticPromotedCandidate =
    automaticActivePromotion
        ?.takeIf { (candidate, review) ->
            candidate.structurallyEligible &&
                review.keepPromotedChallenger &&
                !automaticDemotionPending
        }
        ?.first

val automaticSelectedRouteCandidate =
    automaticCaptureRoutePlan
        ?.let { plan ->
            when {
                automaticDemotionPending ->
                    plan.selected

                automaticPromotedCandidate !=
                    null ->
                    automaticPromotedCandidate

                automaticPromotionCandidate !=
                    null ->
                    automaticPromotionCandidate

                automaticExplorationCandidate !=
                    null ->
                    automaticExplorationCandidate

                else ->
                    plan.selected
            }
        }

val selectedMultiMic =
    when {

        /*
         * Override manuel multi-mic :
         * LEFT / RIGHT / MIX / ALIGNED_MIX
         *
         * On l'accepte uniquement si le téléphone
         * a réellement validé MIC + STEREO.
         */
        requestedMultiMic.explicitOverride &&
            requestedMultiMic.enabled &&
            adaptiveCaptureProfile.dualMicSpatialAvailable -> {

            Log.i(
                "GuardianAudioProfile",
                "Multi-mic manuel accepté : " +
                    "mode=${requestedMultiMic.name}"
            )

            requestedMultiMic
        }

        /*
         * Un mode multi-mic manuel a été demandé,
         * mais le téléphone n'a pas validé
         * la route stéréo nécessaire.
         */
        requestedMultiMic.explicitOverride &&
            requestedMultiMic.enabled -> {

            Log.w(
                "GuardianAudioProfile",
                "Multi-mic manuel demandé " +
                    "(${requestedMultiMic.name}) " +
                    "mais MIC + STEREO non validé. " +
                    "Fallback MONO."
            )

            DiagnosticMultiMic(
                enabled = false,
                channelSelection =
                    GuardianAudioEngine.STEREO_CHANNEL_LEFT,
                name = "OFF",
                explicitOverride = true,
            )
        }

        /*
         * guardian-multimic.txt contient OFF.
         *
         * On respecte donc le mono forcé.
         */
        requestedMultiMic.explicitOverride -> {

            Log.i(
                "GuardianAudioProfile",
                "Multi-mic désactivé explicitement : OFF"
            )

            requestedMultiMic
        }

        
        /*
         * AUTO réel : la route choisie provient maintenant du plan
         * GuardianAudioCapabilities.
         *
         * Une route multi-canal n'est activée ici que si le plan AUTO l'a
         * effectivement sélectionnée. Les challengers ne sont PAS promus
         * sans preuve de performance vocale.
         */
        else -> {
            val automaticSelected =
                automaticSelectedRouteCandidate

            val automaticMultiChannel =
                automaticSelected
                    ?.multiChannelEnabled ==
                    true

            Log.i(
                "GuardianAudioProfile",
                "Sélection AUTO : " +
                    "route=${automaticSelected?.label ?: "LEGACY_MONO"}, " +
                    "multiChannel=$automaticMultiChannel, " +
                    "alternatives=${automaticAlternativeCandidates.map { it.label }}, " +
                    "promotionCandidate=${automaticPromotionCandidate?.label}, " +
                    "explorationCandidate=${automaticExplorationCandidate?.label}, " +
                    "explorationDecision=${automaticExplorationDecision?.decision ?: "NONE"}, " +
                    "activePromotion=${automaticPromotedCandidate?.label}, " +
                    "promotionPending=$automaticPromotionPending, " +
                    "demotionPending=$automaticDemotionPending"
            )

            DiagnosticMultiMic(
                enabled =
                    automaticMultiChannel,
                channelSelection =
                    if (automaticMultiChannel) {
                        automaticSelected?.outputSelection
                            ?: GuardianAudioEngine.STEREO_CHANNEL_ALIGNED_MIX
                    } else {
                        GuardianAudioEngine
                            .STEREO_CHANNEL_LEFT
                    },
                name =
                    if (automaticMultiChannel) {
                        "AUTO_SPATIAL"
                    } else {
                        "AUTO_MONO"
                    },
                explicitOverride =
                    false,
            )
        }
    }

/*
 * Le probe matériel a montré que le vrai double-micro du S23 est exposé
 * par MIC + STEREO. Lorsqu'un mode LEFT/RIGHT/MIX est demandé, cette
 * session diagnostic force donc MIC. OFF conserve le comportement normal.
 */
val effectiveAudioSource =
    when {
        /*
         * Compatibilité historique : un override LEFT/RIGHT/MIX/ALIGNED_MIX
         * reste explicitement attaché à MIC + STEREO.
         */
        selectedMultiMic.enabled &&
            selectedMultiMic.explicitOverride ->
            MediaRecorder.AudioSource.MIC

        automaticSelectedRouteCandidate != null ->
            automaticSelectedRouteCandidate.source

        else ->
            selectedAudioSource.source
    }

if (
    selectedMultiMic.enabled &&
    selectedMultiMic.explicitOverride &&
    selectedAudioSource.source !=
    MediaRecorder.AudioSource.MIC
) {
    Log.i(
        TAG,
        "MULTIMIC DIAGNOSTIC : source forcée sur MIC pour capture STEREO"
    )
}

activeAudioEvidenceRouteKey =
    runtimeAudioEvidenceRouteKey(
        audioSource =
            effectiveAudioSource,
        multiMic =
            selectedMultiMic,
    )

Log.i(
    "GuardianAudioEvidence",
    "Route evidence active : " +
        "${activeAudioEvidenceRouteKey ?: "NONE"}"
)

/*
 * Guardian Spatial Trust.

 *
 * On qualifie la route réellement utilisée AVANT de construire
 * GuardianAudioEngine. Une route PROCESSED peut rester utile pour
 * sélectionner / pondérer les canaux, mais elle ne doit pas être traitée
 * comme un TDOA physique garanti.
 */
val spatialCaptureConstraints =
    GuardianAudioCapabilities
        .resolveSpatialCaptureConstraints(
            capabilities =
                audioCapabilities,
            probeResults =
                audioProbeResults,
            source =
                effectiveAudioSource,
            channelMask =
                if (selectedMultiMic.enabled) {
                    AudioFormat.CHANNEL_IN_STEREO
                } else {
                    AudioFormat.CHANNEL_IN_MONO
                },
            channelIndexMask =
                null,
        )

Log.i(
    "GuardianAudioProfile",
    "Contraintes spatiales : " +
        "classification=${spatialCaptureConstraints.routeClassification}, " +
        "delayTrust=${spatialCaptureConstraints.delayTrust}, " +
        "steeringAllowed=${spatialCaptureConstraints.steeringAllowed}, " +
        "geometryReliable=${spatialCaptureConstraints.physicalGeometryReliable}, " +
        "micDistanceM=${spatialCaptureConstraints.physicalMicrophoneDistanceMeters}, " +
        "maxPhysicalTdoaS=${spatialCaptureConstraints.maximumPhysicalTdoaSeconds}, " +
        "reason=${spatialCaptureConstraints.reason}"
)

val audio =
    GuardianAudioEngine(
        context = applicationContext,

        configuration =
            GuardianAudioEngine.Configuration(

                sampleRateHz =
                    16_000,
                frameDurationMs = 50,

                /*
                 * C5 :
                 * recommendedAudioSource correspond maintenant à la
                 * baseline Guardian VOICE_RECOGNITION, avec fallback
                 * uniquement si nécessaire.
                 */
                audioSource =
                    effectiveAudioSource,

                inputChannelMask =
                    if (selectedMultiMic.enabled) {
                        AudioFormat.CHANNEL_IN_STEREO
                    } else {
                        AudioFormat.CHANNEL_IN_MONO
                    },

                stereoChannelSelection =
                    selectedMultiMic.channelSelection,

                /*
 * Les WAV stéréo sont réservés aux overrides
 * diagnostiques explicites.
 *
 * Le futur mode AUTO peut utiliser le spatial
 * sans enregistrer continuellement des WAV.
 */
stereoDiagnosticCaptureEnabled =
    selectedMultiMic.enabled &&
        selectedMultiMic.explicitOverride,

                spatialSteeringAllowed =
                    selectedMultiMic.enabled &&
                        spatialCaptureConstraints.steeringAllowed,

                spatialMaximumTrustedTdoaSeconds =
                    if (selectedMultiMic.enabled) {
                        spatialCaptureConstraints.maximumPhysicalTdoaSeconds
                    } else {
                        null
                    },

                spatialInputTrustName =
                    spatialCaptureConstraints.delayTrust.name,

                voiceFocusMode = when {
                    selectedMultiMic.focusObserve ->
                        GuardianVoiceFocusMode.OBSERVE
                    selectedMultiMic.enabled &&
                        selectedMultiMic.channelSelection ==
                            GuardianAudioEngine.STEREO_CHANNEL_VOICE_FOCUS ->
                        GuardianVoiceFocusMode.ACTIVE
                    else -> GuardianVoiceFocusMode.OFF
                },

                /*
                 * Production : les diagnostics Far Voice frame-par-frame sont
                 * coupés. Les transitions structurantes restent journalisées.
                 */
                farVoiceDiagnosticEnabled =
                    false,

                preferredMicrophoneDirectionEnabled =
                    false,

                preferredMicrophoneFieldEnabled =
                    selectedMicrophoneField.enabled,

                preferredMicrophoneFieldDimension =
                    selectedMicrophoneField.dimension,

                /*
                 * v3.4 : aucun débruiteur ne remplace le PCM original.
                 * Le NoiseSuppressor Android reste désactivé afin
                 * d'éviter deux traitements de réduction de bruit.
                 */
                androidNoiseSuppressorEnabled =
                    false,

                /*
                 * Guardian conserve son propre Adaptive Gain.
                 * L'AGC Android reste désactivé.
                 */
                androidAgcEnabled =
                    false,

                /*
                 * C7 choisit ensuite dynamiquement BYPASS ou GTCRN.
                 * Si le matériel n'autorise pas GTCRN, ce flag reste false
                 * et GuardianAudioEngine fonctionne en pass-through.
                 */
                speechEnhancementEnabled =
                    gtcrnAllowedForSession,
                forceSpeechEnhancement = gtcrnAllowedForSession,
            )
    )

/*
 * Phase C6 - installation conditionnelle de GTCRN.
 *
 * GTCRN n'est créé et chargé QUE lorsque la session
 * l'autorise réellement.
 *
 * Avec gtcrnAllowedForSession=false :
 * - aucun modèle GTCRN n'est chargé ;
 * - aucun enhancer n'est installé ;
 * - GuardianAudioEngine reste en pass-through ;
 * - Adaptive Gain / Far Voice / Sherpa restent inchangés.
 */
var gtcrnInstalledForSession = false
if (gtcrnAllowedForSession) {
    val gtcrnEnhancer = try {
        GuardianGtcrnEnhancer(context = applicationContext)
    } catch (error: Throwable) {
        Log.e(TAG, "GTCRN indisponible : repli sur le VAD original", error)
        voiceDiagnostics?.event("GTCRN_UNAVAILABLE", "error" to error.javaClass.simpleName)
        null
    }
    if (gtcrnEnhancer != null) {
        gtcrnInstalledForSession = audio.setAudioEnhancer(gtcrnEnhancer)
        if (!gtcrnInstalledForSession) {
            gtcrnEnhancer.release()
            Log.w(TAG, "GTCRN non installé : repli sur le VAD original")
        }
    }
}
Log.i(TAG, "GTCRN capture : requested=$gtcrnAllowedForSession installed=$gtcrnInstalledForSession")

voiceDiagnostics?.event("CAPTURE_CONFIG",
    "audioSource" to effectiveAudioSource,
    "channels" to if (selectedMultiMic.enabled) 2 else 1,
    "gtcrnEnabled" to gtcrnInstalledForSession,
    "gtcrnRequested" to gtcrnAllowedForSession,
    "gtcrnRecommended" to audioCapabilities.neuralEnhancementAllowed,
    "androidAgc" to false, "androidNoiseSuppressor" to false)
val processorAdded = audio.addProcessor(qwenPrimary)

if (!processorAdded) {

    conversation.destroy()

    conversationEngine =
        null

    qwenPrimary?.close()
    if (qwenPrimaryProcessor === qwenPrimary) qwenPrimaryProcessor = null

    throw IllegalStateException(
        "Impossible de connecter l'ASR primaire à GuardianAudioEngine"
    )
}

audioEngine =
  audio

  // Préparer le récepteur de FINAL/RESCUE AVANT d'ouvrir le microphone.
  // Sinon les premières trames peuvent arriver quand VoiceEngine.running=false.
  engine.start()
  if (!engine.isRunning()) {
      audio.release()
      conversation.destroy()
      conversationEngine = null

    qwenPrimary?.close()
    if (qwenPrimaryProcessor === qwenPrimary) qwenPrimaryProcessor = null

      audioEngine = null
      voiceEngine = null
      activeVoiceSessionToken = null
      engine.destroy()
      throw IllegalStateException(
          "GuardianVoiceEngine indisponible avant démarrage AudioRecord"
      )
  }

  val audioStarted =
  audio.start()

if (!audioStarted) {

    audio.release()

    conversation.destroy()

    conversationEngine =
        null

    qwenPrimary?.close()
    if (qwenPrimaryProcessor === qwenPrimary) qwenPrimaryProcessor = null

    audioEngine =
        null

    voiceEngine = null
    activeVoiceSessionToken = null
    engine.destroy()

    throw IllegalStateException(
        "Impossible de démarrer GuardianAudioEngine"
    )
}

/*
 * start() lance les threads de capture. Pour toute décision AUTO persistante,
 * on exige maintenant que le vrai AudioRecord ait effectivement atteint
 * l'état RUNNING et que le monitor runtime ait eu le temps de s'installer.
 */
if (!audio.awaitRunning()) {

    val audioState =
        audio.getState()

    audio.stop()
    audio.release()

    conversation.destroy()

    conversationEngine =
        null

    qwenPrimary?.close()
    if (qwenPrimaryProcessor === qwenPrimary) qwenPrimaryProcessor = null

    audioEngine =
        null

    voiceEngine = null
    activeVoiceSessionToken = null
    engine.destroy()

    throw IllegalStateException(
        "GuardianAudioEngine n'a pas atteint RUNNING : state=$audioState"
    )
}

    // Le VoiceEngine a été démarré juste avant AudioRecord.
    lastVoiceStatus =
      engine.getStatus()

    lastVoiceAudioDiagnostics =
      audio.getLastStatistics()

    lastVoiceRuntimeAudioProfile =
    audio.getRuntimeAudioProfile()

    lastVoiceSherpaDiagnostics =
      null

    lastVoiceUnderstandingDiagnostics =
      engine.getUnderstandingDiagnostics()

    lastVoiceFusionDiagnostics =
      engine.getFusionDiagnostics()

if (serviceDestroyed) {

    audio.stop()
    audio.release()

    qwenPrimary?.close()
    if (qwenPrimaryProcessor === qwenPrimary) qwenPrimaryProcessor = null
    conversation.destroy()

    conversationEngine =
    null
    engine.destroy()

    audioEngine = null

    voiceEngine = null
    lastVoiceStatus = null

    return
}

    if (!engine.isRunning()) {

    val status =
        engine.getStatus()

    audio.stop()
    audio.release()

    qwenPrimary?.close()
    if (qwenPrimaryProcessor === qwenPrimary) qwenPrimaryProcessor = null
    conversation.destroy()

    conversationEngine =
    null
    engine.destroy()

    audioEngine =
        null

    voiceEngine =
        null

    throw IllegalStateException(
        when {
          !status.recognizerAvailable ->
            "Reconnaissance vocale Android indisponible"

          !status.microphonePermissionGranted ->
            "Permission microphone absente pour le moteur Voice"

          else ->
            "Le moteur Voice natif n'a pas démarré"
        }
      )
    }

/*
 * Les transitions AUTO ne deviennent persistantes qu'après validation des
 * DEUX moteurs : capture AudioRecord RUNNING + GuardianVoiceEngine RUNNING.
 *
 * Si le Runtime Audio Monitor n'est pas fiable, la protection vocale continue
 * normalement mais cette session n'alimente pas l'apprentissage AUTO.
 */
val routeEvidenceTrustedAtStart =
    audio.isRouteEvidenceValid()

if (routeEvidenceTrustedAtStart) {
    val plan =
        automaticCaptureRoutePlan

    val baselineKey =
        plan
            ?.selected
            ?.let { candidate ->
                automaticAudioEvidenceRouteKey(
                    candidate
                )
            }

    if (
        automaticPromotionPending &&
        plan != null &&
        baselineKey != null &&
        automaticPromotionCandidate !=
            null
    ) {
        audioRouteEvidenceStore
            .markPromotionStarted(
                baselineRouteKey =
                    baselineKey,
                challengerRouteKey =
                    automaticAudioEvidenceRouteKey(
                        automaticPromotionCandidate
                    ),
            )

        Log.i(
            "GuardianAudioEvidence",
            "Promotion challenger confirmée après démarrage complet : " +
                "baseline=${plan.selected.label}, " +
                "challenger=${automaticPromotionCandidate.label}"
        )
    }

    if (
        automaticDemotionPending &&
        plan != null &&
        baselineKey != null &&
        automaticDemotionCandidate !=
            null
    ) {
        audioRouteEvidenceStore
            .markDemotionCompleted(
                baselineRouteKey =
                    baselineKey,
                challengerRouteKey =
                    automaticAudioEvidenceRouteKey(
                        automaticDemotionCandidate
                    ),
            )

        Log.w(
            "GuardianAudioEvidence",
            "Démotion challenger confirmée après redémarrage baseline complet : " +
                "baseline=${plan.selected.label}, " +
                "challenger=${automaticDemotionCandidate.label}, " +
                "reason=${automaticDemotionReview?.reason}"
        )
    }

    if (
        automaticExplorationCandidate !=
            null &&
        automaticExplorationDecision
            ?.exploreChallenger ==
            true &&
        plan != null &&
        baselineKey != null
    ) {
        audioRouteEvidenceStore
            .markExplorationStarted(
                baselineRouteKey =
                    baselineKey,
                challengerRouteKey =
                    automaticAudioEvidenceRouteKey(
                        automaticExplorationCandidate
                    ),
            )

        Log.i(
            "GuardianAudioEvidence",
            "Exploration challenger confirmée après démarrage complet : " +
                "baseline=${plan.selected.label}, " +
                "challenger=${automaticExplorationCandidate.label}"
        )
    }

    activeAudioEvidenceRouteKey
        ?.let { routeKey ->
            val snapshot =
                audioRouteEvidenceStore
                    .markSessionStarted(
                        routeKey
                    )

            logAudioRouteEvidence(
                prefix = "Session audio démarrée",
                snapshot = snapshot,
            )
        }

    audioEvidenceCollectionArmed =
        true
} else {
    audioEvidenceCollectionArmed =
        false

    Log.w(
        "GuardianAudioEvidence",
        "Apprentissage AUTO désarmé pour cette session : " +
            "reason=${audio.getRouteEvidenceInvalidReason() ?: "RUNTIME_ROUTE_NOT_TRUSTED"}"
    )
}

    Log.d(
      TAG,
      "Moteur Voice natif démarré"
    )
  }

  /**
   * Met à jour les types Android du service foreground
   * selon les moteurs réellement activés.
   */
  private fun updateForegroundServiceTypes(
    config: GuardianNativeConfig,
    includeMicrophone: Boolean = config.voiceEnabled
  ) {
    if (
      Build.VERSION.SDK_INT <
      Build.VERSION_CODES.Q
    ) {
      return
    }

    var foregroundServiceTypes =
      0

    if (config.gpsEnabled) {
      foregroundServiceTypes =
        foregroundServiceTypes or
          ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
    }

    if (
      Build.VERSION.SDK_INT >=
      Build.VERSION_CODES.R &&
      includeMicrophone
    ) {
      foregroundServiceTypes =
        foregroundServiceTypes or
          ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
    }

    if (foregroundServiceTypes == 0) {
      return
    }

    startForeground(
      NOTIFICATION_ID,
      createNotification(),
      foregroundServiceTypes
    )
  }

  /**
   * Traite une demande de SOS manuel envoyée au service.
   */
  private fun handleManualSosCommand(
    intent: Intent
  ) {
    val requestedAt =
      intent.getLongExtra(
        GuardianNativeProtectionModule.EXTRA_SOS_REQUESTED_AT,
        System.currentTimeMillis()
      )
        .takeIf { value ->
          value > 0L
        }
        ?: System.currentTimeMillis()

    val source =
      intent.getStringExtra(
        GuardianNativeProtectionModule.EXTRA_SOS_SOURCE
      )
        ?.trim()
        ?.takeIf { value ->
          value.isNotBlank()
        }
        ?: "unknown"

    serviceScope.launch {
      try {
        val config =
          GuardianNativeConfigStore(
            applicationContext
          ).loadConfig()
            ?: throw IllegalStateException(
              "Configuration native Guardian absente pour le SOS"
            )

        if (serviceDestroyed) {
          return@launch
        }

        val store =
          locationStore
            ?: GuardianLocationStore(
              applicationContext
            ).also { createdStore ->
              locationStore =
                createdStore
            }

        triggerManualSos(
          config = config,
          store = store,
          requestedAt = requestedAt,
          source = source
        )
      } catch (
        cancellationError: CancellationException
      ) {
        throw cancellationError
      } catch (error: Exception) {
        lastServiceError =
          error.message
            ?: "Échec du déclenchement du SOS manuel"

        Log.e(
          TAG,
          "Échec du SOS manuel natif",
          error
        )
      }
    }
  }

  /**
 * Traite l'arrêt volontaire de la protection
 * demandé depuis React Native.
 *
 * L'événement est enregistré dans SQLite avant
 * l'arrêt réel du service.
 */
private fun handleStopProtectionCommand() {
  serviceScope.launch {
    try {
      val config =
        GuardianNativeConfigStore(
          applicationContext
        ).loadConfig()
          ?: throw IllegalStateException(
            "Configuration native Guardian absente pour l'arrêt du suivi"
          )

      val store =
        locationStore
          ?: GuardianLocationStore(
            applicationContext
          ).also { createdStore ->
            locationStore =
              createdStore
          }

      val now =
        System.currentTimeMillis()

      val event =
        GuardianServiceEvent(
          eventId =
            UUID.randomUUID()
              .toString(),

          priority =
            GuardianEventPriority.HIGH,

          createdAt =
            now,

          recordedAt =
            now,

          schemaVersion =
            GuardianNativeEvent.CURRENT_SCHEMA_VERSION,

          childId =
            config.childId,

          familyId =
            config.familyId,

          familyCode =
            config.familyCode,

          deviceId =
            config.deviceId,

          reason =
            "child_requested_stop"
        )

      store.saveServiceEvent(
        event
      )

      Log.w(
        TAG,
        "Arrêt volontaire du suivi sauvegardé : " +
          "id=${event.eventId}, " +
          "childId=${event.childId}, " +
          "reason=${event.reason}"
      )

      GuardianUploadScheduler.forceUpload(
        applicationContext
      )

      Log.d(
        TAG,
        "Upload immédiat TRACKING_STOPPED demandé : " +
          "id=${event.eventId}"
      )

      /*
       * Le service ne s'arrête qu'après la sauvegarde
       * durable de l'événement dans SQLite.
       */
      stopSelf()
    } catch (
      cancellationError: CancellationException
    ) {
      throw cancellationError
    } catch (error: Exception) {
      lastServiceError =
        error.message
          ?: "Échec de l'enregistrement de l'arrêt du suivi"

      Log.e(
        TAG,
        "Échec de l'arrêt volontaire du suivi",
        error
      )

      /*
       * En cas d'échec SQLite, on ne prétend pas que
       * l'arrêt a été enregistré. Le service reste actif.
       */
    }
  }
}

  /**
   * Produit et stocke un événement SOS manuel.
   */
  private fun triggerManualSos(
    config: GuardianNativeConfig,
    store: GuardianLocationStore,
    requestedAt: Long,
    source: String
  ) {
    require(config.childId.isNotBlank()) {
      "childId SOS vide"
    }

    require(config.familyId.isNotBlank()) {
      "familyId SOS vide"
    }

    require(config.familyCode.isNotBlank()) {
      "familyCode SOS vide"
    }

    require(config.deviceId.isNotBlank()) {
      "deviceId SOS vide"
    }

    val lastLatitude =
      GuardianLocationEngine.lastLatitude

    val lastLongitude =
      GuardianLocationEngine.lastLongitude

    val hasCompletePosition =
      lastLatitude != null &&
        lastLongitude != null

    val event =
      GuardianSosEvent(
        eventId =
          UUID.randomUUID()
            .toString(),

        createdAt =
          requestedAt,

        recordedAt =
          System.currentTimeMillis(),

        schemaVersion =
          GuardianNativeEvent.CURRENT_SCHEMA_VERSION,

        childId =
          config.childId,

        familyId =
          config.familyId,

        familyCode =
          config.familyCode,

        deviceId =
          config.deviceId,

        latitude =
          if (hasCompletePosition) {
            lastLatitude
          } else {
            null
          },

        longitude =
          if (hasCompletePosition) {
            lastLongitude
          } else {
            null
          },

        accuracy =
          if (hasCompletePosition) {
            GuardianLocationEngine.lastAccuracy
          } else {
            null
          },

        batteryPercent =
          getBatteryPercent()
      )

    store.saveSosEvent(
      event
    )

    Log.w(
      TAG,
      "SOS manuel sauvegardé : " +
        "id=${event.eventId}, " +
        "source=$source, " +
        "position=${event.latitude != null}, " +
        "battery=${event.batteryPercent}"
    )

    GuardianUploadScheduler.forceUpload(
      applicationContext
    )

    Log.d(
      TAG,
      "Upload SOS immédiat demandé : " +
        "id=${event.eventId}"
    )
  }

  private fun getBatteryPercent():
    Int? {

    val batteryManager =
      getSystemService(
        BATTERY_SERVICE
      ) as? BatteryManager
        ?: return null

    val capacity =
      batteryManager.getIntProperty(
        BatteryManager.BATTERY_PROPERTY_CAPACITY
      )

    return capacity.takeIf { value ->
      value in 0..100
    }
  }

  private fun stopServiceWithError(
    message: String,
    error: Throwable? = null
  ) {
    lastServiceError =
      message

    if (error != null) {
      Log.e(
        TAG,
        message,
        error
      )
    } else {
      Log.e(
        TAG,
        message
      )
    }

    stopSelf()
  }

  override fun onStartCommand(
  intent: Intent?,
  flags: Int,
  startId: Int
): Int {
  val action =
    intent?.action

  Log.d(
    TAG,
    "onStartCommand : " +
      "startId=$startId, " +
      "action=$action"
  )

  /*
   * Une commande d'arrêt ne doit jamais redémarrer
   * les moteurs GPS, PTI ou Voice.
   */
  if (
    action ==
    GuardianNativeProtectionModule
      .ACTION_STOP_PROTECTION
  ) {
    handleStopProtectionCommand()

    return START_NOT_STICKY
  }

  /*
   * Pour un démarrage normal ou une commande SOS,
   * les moteurs sont initialisés une seule fois.
   */
  if (startupJob == null) {
    loadConfigurationAndStartEngines()
  }

  when (action) {
    GuardianNativeProtectionModule
      .ACTION_TRIGGER_MANUAL_SOS -> {
      if (intent != null) {
        handleManualSosCommand(
          intent
        )
      }
    }
  }

  return START_STICKY
}

  override fun onTaskRemoved(rootIntent: Intent?) {

    Log.w(
        TAG,
        "Application fermée : arrêt du suivi demandé"
    )

    handleStopProtectionCommand()

    super.onTaskRemoved(rootIntent)
}

  override fun onDestroy() {
    activeVoiceSessionToken = null
    serviceDestroyed =
      true

    startupJob?.cancel()

    startupJob =
      null

    healthJob?.cancel()

healthJob =
  null

  lexicalTimelineJob?.cancel()
  lexicalTimelineJob = null

  try {
    wordTimelineEngine?.reset()
} catch (error: Exception) {
    Log.e(
        TAG,
        "Erreur pendant l'arrêt de GuardianWordTimelineEngine",
        error
    )
} finally {
    wordTimelineEngine = null
}

  try {
    conversationEngine?.destroy()
} catch (error: Exception) {
    Log.e(
        TAG,
        "Erreur pendant l'arrêt de GuardianConversationEngine",
        error
    )
} finally {
    conversationEngine =
        null
}

  try {
  audioEngine?.stop()

  audioEngine?.release()
} catch (error: Exception) {
  Log.e(
    TAG,
    "Erreur pendant l'arrêt du moteur Audio",
    error
  )
} finally {
  audioEngine =
    null
}

try {
  qwenPrimaryProcessor?.close()
  qwenPrimaryProcessor = null

} catch (error: Exception) {
  Log.e(
    TAG,
    "Erreur pendant la fermeture de Sherpa",
    error
  )
} finally {

}

    try {
      voiceEngine?.destroy()
    } catch (error: Exception) {
      Log.e(
        TAG,
        "Erreur pendant l'arrêt du moteur Voice",
        error
      )

      if (lastServiceError == null) {
        lastServiceError =
          error.message
            ?: "Erreur pendant l'arrêt du moteur Voice"
      }
    } finally {
      voiceEngine =
        null

      lastVoiceStatus =
        null

      lastVoiceAudioDiagnostics =
        null

      lastVoiceAudioCapabilities =
        null

      lastVoiceRuntimeAudioProfile =
        null

      lastVoiceConversationFinalText =
        null

      lastVoiceConversationSegmentCount =
        null

      lastVoiceConversationFinalizedAt =
        null

      lastVoiceSherpaDiagnostics =
        null

      lastVoiceUnderstandingDiagnostics =
        null

      lastVoiceFusionDiagnostics =
        null
    }

    try {
      ptiEngine?.destroy()
    } catch (error: Exception) {
      Log.e(
        TAG,
        "Erreur pendant l'arrêt du moteur PTI",
        error
      )

      if (lastServiceError == null) {
        lastServiceError =
          error.message
            ?: "Erreur pendant l'arrêt du moteur PTI"
      }
    } finally {
      ptiEngine =
        null

      lastPtiStatus =
        null
    }

    try {
      locationEngine?.stop()
    } catch (error: Exception) {
      Log.e(
        TAG,
        "Erreur pendant l'arrêt du moteur GPS",
        error
      )

      if (lastServiceError == null) {
        lastServiceError =
          error.message
            ?: "Erreur pendant l'arrêt du Native Core"
      }
    } finally {
      locationEngine =
        null
    }

    try {
      locationStore?.close()
    } catch (error: Exception) {
      Log.e(
        TAG,
        "Erreur pendant la fermeture du store SQLite",
        error
      )
    } finally {
      locationStore =
        null
    }

    serviceScope.cancel()

    isRunning =
      false

    startedAt =
      null

    stopForegroundCompat()

    Log.d(
      TAG,
      "Guardian Protection Service arrêté"
    )

    super.onDestroy()
  }

  private fun stopForegroundCompat() {
    if (
      Build.VERSION.SDK_INT >=
      Build.VERSION_CODES.N
    ) {
      stopForeground(
        STOP_FOREGROUND_REMOVE
      )
    } else {
      @Suppress("DEPRECATION")
      stopForeground(true)
    }
  }

  private fun createNotificationChannel() {
    if (
      Build.VERSION.SDK_INT <
      Build.VERSION_CODES.O
    ) {
      return
    }

    val channel =
      NotificationChannel(
        CHANNEL_ID,
        "Guardian Voice Protection",
        NotificationManager.IMPORTANCE_LOW
      ).apply {
        description =
          "Protection Guardian Voice active"

        setShowBadge(
          false
        )
      }

    val manager =
      getSystemService(
        NotificationManager::class.java
      )

    manager.createNotificationChannel(
      channel
    )
  }

  private fun createNotification():
    Notification {

    val launchIntent =
      packageManager
        .getLaunchIntentForPackage(
          packageName
        )

    val contentIntent =
      launchIntent?.let { intent ->
        PendingIntent.getActivity(
          this,
          0,
          intent,
          PendingIntent.FLAG_UPDATE_CURRENT or
            PendingIntent.FLAG_IMMUTABLE
        )
      }

    val builder =
      if (
        Build.VERSION.SDK_INT >=
        Build.VERSION_CODES.O
      ) {
        Notification.Builder(
          this,
          CHANNEL_ID
        )
      } else {
        @Suppress("DEPRECATION")
        Notification.Builder(
          this
        )
      }

    return builder
      .setContentTitle(
        "Guardian Voice actif"
      )
      .setContentText(
        "Protection GPS, PTI, Voice et SOS active en arrière-plan"
      )
      .setSmallIcon(
        android.R.drawable.ic_menu_mylocation
      )
      .setOngoing(
        true
      )
      .setOnlyAlertOnce(
        true
      )
      .setCategory(
        Notification.CATEGORY_SERVICE
      )
      .apply {
        if (contentIntent != null) {
          setContentIntent(
            contentIntent
          )
        }
      }
      .build()
  }

  override fun onBind(
    intent: Intent?
  ): IBinder? =
    null
}