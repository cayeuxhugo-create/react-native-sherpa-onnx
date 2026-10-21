package expo.modules.guardiannativeprotection

import android.content.Intent
import android.os.Build
import expo.modules.kotlin.functions.Coroutine
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

class GuardianNativeProtectionModule : Module() {

  companion object {
    /*
     * Ces valeurs constituent le contrat interne entre
     * le module Expo et GuardianProtectionService.
     *
     * Elles seront reprises à l'identique dans le service
     * lors de l'étape suivante.
     */
    const val ACTION_TRIGGER_MANUAL_SOS =
      "expo.modules.guardiannativeprotection.action.TRIGGER_MANUAL_SOS"

    const val ACTION_STOP_PROTECTION =
  "expo.modules.guardiannativeprotection.action.STOP_PROTECTION"

    const val EXTRA_SOS_REQUESTED_AT =
      "guardian_extra_sos_requested_at"

    const val EXTRA_SOS_SOURCE =
      "guardian_extra_sos_source"

    const val SOS_SOURCE_REACT_NATIVE =
      "react_native_manual_button"
  }

  override fun definition() =
    ModuleDefinition {

      Name(
        "GuardianNativeProtection"
      )

      OnCreate {
        val context =
          appContext.reactContext
            ?: return@OnCreate

        val applicationContext =
          context.applicationContext

        GuardianApplicationProvider.initialize(
          applicationContext
        )

        GuardianAppCheckInitializer.initialize(
          applicationContext
        )
      }

      AsyncFunction(
        "configureProtection"
      ) Coroutine {
          config: GuardianNativeConfigRecord ->

        val context =
          appContext.reactContext
            ?: throw Exception(
              "React context unavailable"
            )

        val childId =
          config.childId.trim()

        val childName =
          config.childName.trim()

        val familyId =
          config.familyId.trim()

        val familyCode =
          config.familyCode.trim()

        val deviceId =
          config.deviceId.trim()

        if (
          childId.isBlank() ||
          childName.isBlank() ||
          familyId.isBlank() ||
          familyCode.isBlank() ||
          deviceId.isBlank()
        ) {
          throw Exception(
            "Configuration native Guardian incomplète"
          )
        }

        val nativeConfig =
          GuardianNativeConfig(
            childId = childId,
            childName = childName,
            familyId = familyId,
            familyCode = familyCode,
            deviceId = deviceId,

            gpsEnabled =
              config.gpsEnabled,

            voiceEnabled =
              config.voiceEnabled,

            voiceProfile = GuardianVoiceProfile.parse(config.voiceProfile).name,
            voiceDiagnosticsEnabled = config.voiceDiagnosticsEnabled,

            ptiEnabled =
              config.ptiEnabled,

            uploadIntervalSeconds =
              config.uploadIntervalSeconds
                .coerceIn(
                  5,
                  3600
                ),

            version = 1,

            lastUpdated =
              System.currentTimeMillis()
          )

        GuardianNativeConfigStore(
          context.applicationContext
        ).saveConfig(
          nativeConfig
        )

        true
      }

      AsyncFunction(
        "getNativeConfig"
      ) Coroutine { ->

        val context =
          appContext.reactContext
            ?: throw Exception(
              "React context unavailable"
            )

        val config =
          GuardianNativeConfigStore(
            context.applicationContext
          ).loadConfig()

        if (config == null) {
          null
        } else {
          mapOf(
            "childId" to
              config.childId,

            "childName" to
              config.childName,

            "familyId" to
              config.familyId,

            "familyCode" to
              config.familyCode,

            "deviceId" to
              config.deviceId,

            "gpsEnabled" to
              config.gpsEnabled,

            "voiceEnabled" to
              config.voiceEnabled,

            "voiceProfile" to config.voiceProfile,
            "voiceDiagnosticsEnabled" to config.voiceDiagnosticsEnabled,

            "ptiEnabled" to
              config.ptiEnabled,

            "uploadIntervalSeconds" to
              config.uploadIntervalSeconds,

            "version" to
              config.version,

            "lastUpdated" to
              config.lastUpdated
          )
        }
      }

      AsyncFunction(
        "clearNativeConfig"
      ) Coroutine { ->

        val context =
          appContext.reactContext
            ?: throw Exception(
              "React context unavailable"
            )

        GuardianNativeConfigStore(
          context.applicationContext
        ).clearConfig()

        true
      }

      AsyncFunction(
        "startProtection"
      ) Coroutine { ->

        val context =
          appContext.reactContext
            ?: throw Exception(
              "React context unavailable"
            )

        val config =
          GuardianNativeConfigStore(
            context.applicationContext
          ).loadConfig()
            ?: throw Exception(
              "Guardian Native Core non configuré"
            )

        if (
          !config.gpsEnabled &&
          !config.voiceEnabled &&
          !config.ptiEnabled
        ) {
          throw Exception(
            "Aucun moteur Guardian activé"
          )
        }

       val intent =
  Intent(
    context.applicationContext,
    GuardianProtectionService::class.java
  )

startGuardianService(intent)

true
      }

      Function(
        "stopProtection"
      ) {
        val context =
          appContext.reactContext
            ?: throw Exception(
              "React context unavailable"
            )

        val intent =
  Intent(
    context.applicationContext,
    GuardianProtectionService::class.java
  ).apply {
    action = ACTION_STOP_PROTECTION
  }

startGuardianService(intent)

true
      }

      /**
       * Déclenche le SOS manuel depuis React Native.
       *
       * Le module ne crée pas lui-même l'événement et
       * n'accède pas directement à SQLite. Il transmet
       * uniquement une commande explicite au service natif.
       *
       * Le service sera responsable de :
       * - charger la configuration ;
       * - récupérer la dernière position disponible ;
       * - récupérer la batterie ;
       * - créer GuardianSosEvent ;
       * - sauvegarder l'événement dans SQLite ;
       * - demander son upload immédiat.
       */
      AsyncFunction(
        "triggerManualSos"
      ) Coroutine { ->

        val context =
          appContext.reactContext
            ?: throw Exception(
              "React context unavailable"
            )

        val applicationContext =
          context.applicationContext

        val config =
          GuardianNativeConfigStore(
            applicationContext
          ).loadConfig()
            ?: throw Exception(
              "Guardian Native Core non configuré"
            )

        requireGuardianIdentity(
          config
        )

        val requestedAt =
          System.currentTimeMillis()

        val intent =
          Intent(
            applicationContext,
            GuardianProtectionService::class.java
          ).apply {
            action =
              ACTION_TRIGGER_MANUAL_SOS

            putExtra(
              EXTRA_SOS_REQUESTED_AT,
              requestedAt
            )

            putExtra(
              EXTRA_SOS_SOURCE,
              SOS_SOURCE_REACT_NATIVE
            )
          }

        startGuardianService(
          intent
        )

        mapOf(
          "accepted" to true,
          "requestedAt" to requestedAt,
          "source" to SOS_SOURCE_REACT_NATIVE
        )
      }

      AsyncFunction(
        "triggerUpload"
      ) {
        val context =
          appContext.reactContext
            ?: throw Exception(
              "React context unavailable"
            )

        GuardianUploadScheduler.forceUpload(
          context.applicationContext
        )

        true
      }

      Function(
        "getNativeCoreVersion"
      ) {
        "Guardian Native Core V1"
      }

            AsyncFunction(
        "getNativeFirebaseUid"
      ) Coroutine { ->

        val uid =
          GuardianIdentityManager
            .ensureNativeUser()
            .trim()

        if (uid.isBlank()) {
          throw IllegalStateException(
            "UID Firebase natif indisponible"
          )
        }

        uid
      }

      AsyncFunction(
  "getProtectionStatus"
) {

  val statusReadAt =
    System.currentTimeMillis()

  val serviceRunning =
    GuardianProtectionService
      .isRunning

  val ptiStatus =
    GuardianProtectionService
      .lastPtiStatus

  val voiceStatus =
    GuardianProtectionService
      .lastVoiceStatus

  val voiceAudioDiagnostics =
    GuardianProtectionService
        .lastVoiceAudioDiagnostics

  val voiceAudioCapabilities =
    GuardianProtectionService
        .lastVoiceAudioCapabilities

val voiceRuntimeAudioProfile =
    GuardianProtectionService
        .lastVoiceRuntimeAudioProfile

  val voiceSherpaDiagnostics =
    GuardianProtectionService
        .lastVoiceSherpaDiagnostics

  val voiceUnderstandingDiagnostics =
    GuardianProtectionService
        .lastVoiceUnderstandingDiagnostics

  val voiceFusionDiagnostics =
    GuardianProtectionService
        .lastVoiceFusionDiagnostics

  val voiceConversationFinalText =
    GuardianProtectionService
        .lastVoiceConversationFinalText

val voiceConversationSegmentCount =
    GuardianProtectionService
        .lastVoiceConversationSegmentCount

val voiceConversationLastFinalizedAt =
    GuardianProtectionService
        .lastVoiceConversationFinalizedAt

  val lastLocationAt =
    GuardianLocationEngine
      .lastLocationAt

  val lastSensorAt =
    ptiStatus?.lastSensorAt

  val lastAudioAt =
    voiceStatus?.lastAudioAt

  mapOf(
    /*
     * Horodatage exact de la lecture du statut.
     *
     * Il permettra à l’Operation Center
     * de déterminer si les informations
     * sont encore fraîches.
     */
    "statusReadAt" to
      statusReadAt,

    /*
     * Guardian Native Core
     */
    "nativeCoreVersion" to
      "Guardian Native Core V1",

    "serviceStatusAvailable" to
      true,

    "serviceRunning" to
      serviceRunning,

    "startedAt" to
      GuardianProtectionService
        .startedAt,

    /*
     * GPS natif
     */
    "locationStatusAvailable" to
      true,

    "locationActive" to
      GuardianLocationEngine
        .locationActive,

    /*
     * Une position n’est considérée disponible
     * que lorsqu’au moins une mesure réelle
     * a été reçue.
     */
    "locationDataAvailable" to
      (
        lastLocationAt != null &&
        GuardianLocationEngine
          .lastLatitude != null &&
        GuardianLocationEngine
          .lastLongitude != null
      ),

    "lastLocationAt" to
      lastLocationAt,

    "lastLatitude" to
      GuardianLocationEngine
        .lastLatitude,

    "lastLongitude" to
      GuardianLocationEngine
        .lastLongitude,

    "lastAccuracy" to
      GuardianLocationEngine
        .lastAccuracy,

    "lastSpeedMps" to
      GuardianLocationEngine
        .lastSpeedMps,

    /*
     * PTI natif
     *
     * ptiStatusAvailable permet de distinguer :
     * - false réel : moteur arrêté ;
     * - statut absent : moteur pas encore initialisé.
     */
    "ptiStatusAvailable" to
      (ptiStatus != null),

    "ptiActive" to
      ptiStatus?.running,

    "ptiSensorAvailable" to
      ptiStatus?.sensorAvailable,

    "ptiPhase" to
      ptiStatus?.phase,

    "ptiCurrentForceG" to
      ptiStatus?.currentForceG,

    "ptiPeakImpactG" to
      ptiStatus?.peakImpactG,

    "ptiHorizontal" to
      ptiStatus?.horizontal,

    "ptiStillDurationSeconds" to
      ptiStatus?.stillDurationSeconds,

    "ptiLastScore" to
      ptiStatus?.lastScore,

    "lastSensorAt" to
      lastSensorAt,

    /*
     * Voice natif
     *
     * Même principe que pour le PTI :
     * aucune valeur par défaut ne doit faire
     * croire qu’un moteur est réellement arrêté.
     */
    "voiceStatusAvailable" to
      (voiceStatus != null),

    "voiceActive" to
      voiceStatus?.running,

    "voiceListening" to
      voiceStatus?.listening,

    "voicePhase" to
      voiceStatus?.phase?.name,

    "voiceRecognizerAvailable" to
      voiceStatus?.recognizerAvailable,

    "voiceMicrophonePermission" to
      voiceStatus
        ?.microphonePermissionGranted,

    "voiceWakePhraseArmed" to
      voiceStatus?.wakePhraseArmed,

    "voiceConfidence" to
      voiceStatus
        ?.lastConfidenceScore,

    "voiceWakePhrase" to
      voiceStatus?.lastWakePhrase,

    "voiceEmergencyPhrase" to
      voiceStatus
        ?.lastEmergencyPhrase,

    "voiceRestartCount" to
      voiceStatus?.restartCount,

    "lastAudioAt" to
      lastAudioAt,

    "voiceAudioDiagnosticsAvailable" to
    (voiceAudioDiagnostics != null),

"voiceAudioState" to
    voiceAudioDiagnostics
        ?.state
        ?.name,

/*
 * Guardian Audio Diagnostics C9.
 *
 * Chaîne observée :
 * RAW -> filtre mono -> analyse acoustique ->
 * route BYPASS/GTCRN -> Adaptive Gain ->
 * Soft Limiter -> Sherpa.
 *
 * Ces champs sont purement diagnostiques.
 * Ils ne modifient aucune décision native.
 */
"voiceAudioRawRms" to
    voiceAudioDiagnostics
        ?.rawRms,

"voiceAudioFilteredRms" to
    voiceAudioDiagnostics
        ?.filteredRms,

"voiceAudioRms" to
    voiceAudioDiagnostics
        ?.rms,

"voiceAudioOutputRms" to
    voiceAudioDiagnostics
        ?.outputRms,

"voiceAudioNoiseFloor" to
    voiceAudioDiagnostics
        ?.noiseFloor,

"voiceAudioSnrDb" to
    voiceAudioDiagnostics
        ?.snrDb,

"voiceAudioSpeechLevel" to
    voiceAudioDiagnostics
        ?.speechLevel,

"voiceAudioSpeechLikely" to
    voiceAudioDiagnostics
        ?.speechLikely,

"voiceAudioGainEligible" to
    voiceAudioDiagnostics
        ?.gainEligible,

"voiceAudioAdaptiveGain" to
    voiceAudioDiagnostics
        ?.adaptiveGain,

/*
 * C7 - état du routeur GTCRN.
 */
"voiceAudioEnhancementEnabled" to
    voiceAudioDiagnostics
        ?.enhancementEnabled,

"voiceAudioEnhancementRoute" to
    voiceAudioDiagnostics
        ?.enhancementRoute,

"voiceAudioEnhancementRouteReason" to
    voiceAudioDiagnostics
        ?.enhancementRouteReason,

"voiceAudioEnhancementApplied" to
    voiceAudioDiagnostics
        ?.enhancementApplied,

"voiceAudioEnhancementInputRms" to
    voiceAudioDiagnostics
        ?.enhancementInputRms,

"voiceAudioEnhancementOutputRms" to
    voiceAudioDiagnostics
        ?.enhancementOutputRms,

"voiceAudioEnhancementProcessingMs" to
    voiceAudioDiagnostics
        ?.enhancementProcessingMs,

"voiceAudioEnhancementAverageProcessingMs" to
    voiceAudioDiagnostics
        ?.enhancementAverageProcessingMs,

"voiceAudioEnhancementP95ProcessingMs" to
    voiceAudioDiagnostics
        ?.enhancementP95ProcessingMs,

"voiceAudioPeak" to
    voiceAudioDiagnostics
        ?.peak,

"voiceAudioClippedSampleCount" to
    voiceAudioDiagnostics
        ?.clippedSampleCount,

"voiceAudioLimiterEngagedSampleCount" to
    voiceAudioDiagnostics
        ?.limiterEngagedSampleCount,

"voiceAudioDroppedFrameCount" to
    voiceAudioDiagnostics
        ?.droppedFrameCount,

"voiceAudioCapturedFrameCount" to
    voiceAudioDiagnostics
        ?.capturedFrameCount,

"voiceAudioProcessedFrameCount" to
    voiceAudioDiagnostics
        ?.processedFrameCount,

"voiceAudioQueueDepth" to
    voiceAudioDiagnostics
        ?.queueDepth,

"voiceAudioLastCaptureGapMs" to
    voiceAudioDiagnostics
        ?.lastCaptureGapMs,

"voiceAudioMaximumCaptureGapMs" to
    voiceAudioDiagnostics
        ?.maximumCaptureGapMs,

  "voicePhoneAudioCapabilitiesAvailable" to
    (voiceAudioCapabilities != null),

"voicePhoneMicrophoneCount" to
    voiceAudioCapabilities
        ?.microphoneCount,

"voicePhoneBuiltInMicrophoneCount" to
    voiceAudioCapabilities
        ?.builtInMicrophoneCount,

"voicePhoneMultiMicCandidate" to
    voiceAudioCapabilities
        ?.multiMicHardwareCandidate,

"voicePhoneDirectionApiAvailable" to
    voiceAudioCapabilities
        ?.microphoneDirectionApiAvailable,

"voicePhoneFieldApiAvailable" to
    voiceAudioCapabilities
        ?.microphoneFieldDimensionApiAvailable,

"voicePhoneNoiseSuppressorAvailable" to
    voiceAudioCapabilities
        ?.noiseSuppressorAvailable,

"voicePhoneAgcAvailable" to
    voiceAudioCapabilities
        ?.automaticGainControlAvailable,

"voicePhoneUnprocessedAvailable" to
    voiceAudioCapabilities
        ?.unprocessedSupported,

"voicePhoneRecommendedAudioSource" to
    voiceAudioCapabilities
        ?.recommendedAudioSourceName,

"voicePhonePerformanceProfile" to
    voiceAudioCapabilities
        ?.performanceProfile
        ?.name,

"voicePhoneNeuralEnhancementAllowed" to
    voiceAudioCapabilities
        ?.neuralEnhancementAllowed,

"voiceGuardianAudioRuntimeAvailable" to
    (voiceRuntimeAudioProfile != null),

"voiceGuardianAudioSource" to
    voiceRuntimeAudioProfile
        ?.audioSourceName,

"voiceGuardianSampleRateHz" to
    voiceRuntimeAudioProfile
        ?.sampleRateHz,

"voiceGuardianChannelCount" to
    voiceRuntimeAudioProfile
        ?.channelCount,

"voiceGuardianActiveMicrophoneCount" to
    voiceRuntimeAudioProfile
        ?.activeMicrophoneCount,

"voiceGuardianActiveMicrophoneDescriptions" to
    voiceRuntimeAudioProfile
        ?.activeMicrophones
        ?.map { microphone ->
            microphone.description
        },

"voiceGuardianChannelMappings" to
    voiceRuntimeAudioProfile
        ?.activeMicrophones
        ?.flatMap { microphone ->
            microphone.channelMapping
        },

"voiceGuardianNoiseSuppressorActive" to
    voiceRuntimeAudioProfile
        ?.noiseSuppressorActive,

"voiceGuardianAgcActive" to
    voiceRuntimeAudioProfile
        ?.androidAgcActive,

"voiceGuardianDirectionRequested" to
    voiceRuntimeAudioProfile
        ?.directionRequested,

"voiceGuardianDirectionApplied" to
    voiceRuntimeAudioProfile
        ?.directionApplied,

"voiceGuardianFieldRequested" to
    voiceRuntimeAudioProfile
        ?.fieldDimensionRequested,

"voiceGuardianFieldApplied" to
    voiceRuntimeAudioProfile
        ?.fieldDimensionApplied,

"voiceGuardianMultiChannelActive" to
    voiceRuntimeAudioProfile
        ?.multiChannelActive,

    "voiceConversationAvailable" to
    (GuardianProtectionService.lastVoiceConversationFinalText != null),

"voiceConversationFinalText" to
    GuardianProtectionService.lastVoiceConversationFinalText,

"voiceConversationSegmentCount" to
    GuardianProtectionService.lastVoiceConversationSegmentCount,

"voiceConversationLastFinalizedAt" to
    GuardianProtectionService.lastVoiceConversationFinalizedAt,

    "voiceSherpaDiagnosticsAvailable" to
    (voiceSherpaDiagnostics != null),

"voiceSherpaInitialized" to
    voiceSherpaDiagnostics
        ?.initialized,

"voiceVadEnabled" to
    voiceSherpaDiagnostics
        ?.vadEnabled,

"voiceVadSpeechActive" to
    voiceSherpaDiagnostics
        ?.vadSpeechActive,

"voiceUtteranceHasAudio" to
    voiceSherpaDiagnostics
        ?.utteranceHasAudio,

"voiceVadThreshold" to
    voiceSherpaDiagnostics
        ?.vadThreshold,

"voiceVadMinSpeechDuration" to
    voiceSherpaDiagnostics
        ?.vadMinSpeechDuration,

"voiceVadMinSilenceDuration" to
    voiceSherpaDiagnostics
        ?.vadMinSilenceDuration,

"voiceVadPreRollFrames" to
    voiceSherpaDiagnostics
        ?.vadPreRollFrames,

"voiceSherpaLastPartialText" to
    voiceSherpaDiagnostics
        ?.lastPartialText,

"voiceSherpaLastFinalText" to
    voiceSherpaDiagnostics
        ?.lastFinalText,

"voiceSherpaLastConfidence" to
    voiceSherpaDiagnostics
        ?.lastConfidence,

"voiceSherpaFinalizedUtteranceCount" to
    voiceSherpaDiagnostics
        ?.finalizedUtteranceCount,

    "voiceConversationAvailable" to
    (voiceConversationFinalText != null),

"voiceConversationFinalText" to
    voiceConversationFinalText,

"voiceConversationSegmentCount" to
    voiceConversationSegmentCount,

"voiceConversationLastFinalizedAt" to
    voiceConversationLastFinalizedAt,

    "voiceUnderstandingAvailable" to
    (voiceUnderstandingDiagnostics != null),

"voiceUnderstandingText" to
    voiceUnderstandingDiagnostics
        ?.normalizedText,

"voiceUnderstandingPrimaryIntent" to
    voiceUnderstandingDiagnostics
        ?.primaryIntent
        ?.name,

"voiceUnderstandingScore" to
    voiceUnderstandingDiagnostics
        ?.confidenceScore,

"voiceUnderstandingEmergency" to
    voiceUnderstandingDiagnostics
        ?.emergencyDetected,

"voiceUnderstandingWake" to
    voiceUnderstandingDiagnostics
        ?.wakeDetected,

"voiceUnderstandingReassurance" to
    voiceUnderstandingDiagnostics
        ?.reassuranceDetected,

"voiceUnderstandingEvidence" to
    voiceUnderstandingDiagnostics
        ?.evidence,

      "voiceFusionAvailable" to
    (voiceFusionDiagnostics != null),

"voiceFusionLevel" to
    voiceFusionDiagnostics
        ?.level
        ?.name,

"voiceFusionScore" to
    voiceFusionDiagnostics
        ?.confidenceScore,

"voiceFusionEmergency" to
    voiceFusionDiagnostics
        ?.emergencyDetected,

"voiceFusionWake" to
    voiceFusionDiagnostics
        ?.wakeDetected,

"voiceFusionStrongestIntent" to
    voiceFusionDiagnostics
        ?.strongestIntent
        ?.name,

"voiceFusionSupportingIntentCount" to
    voiceFusionDiagnostics
        ?.supportingEmergencyIntentCount,

"voiceFusionReasons" to
    voiceFusionDiagnostics
        ?.reasons,

    /*
     * Événements de localisation en attente.
     *
     * Attention :
     * ce compteur ne représente actuellement
     * que les événements LOCATION.
     */
    "pendingLocationEventCount" to
      GuardianLocationEngine
        .pendingLocationCount,

    /*
     * Erreur native connue.
     */
    "lastError" to (
      GuardianProtectionService
        .lastServiceError
        ?: GuardianLocationEngine
          .lastError
      )
  )
}
}


  private fun startGuardianService(
    intent: Intent
  ) {
    val context =
      appContext.reactContext
        ?: throw Exception(
          "React context unavailable"
        )

    if (
      Build.VERSION.SDK_INT >=
      Build.VERSION_CODES.O
    ) {
      context.startForegroundService(
        intent
      )
    } else {
      context.startService(
        intent
      )
    }
  }

  private fun requireGuardianIdentity(
    config: GuardianNativeConfig
  ) {
    require(
      config.childId.isNotBlank()
    ) {
      "Configuration Guardian : childId absent"
    }

    require(
      config.childName.isNotBlank()
    ) {
      "Configuration Guardian : childName absent"
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
  }
}