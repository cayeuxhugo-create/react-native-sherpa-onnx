package expo.modules.guardiannativeprotection

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

class GuardianUploadWorker(
  context: Context,
  params: WorkerParameters
) : CoroutineWorker(
  context,
  params
) {

  companion object {
    private const val LOG_TAG =
      "GuardianUploadWorker"

    private const val BATCH_SIZE =
      50

    private const val MAX_BATCHES_PER_TYPE =
      20
  }

  override suspend fun doWork(): Result {
  val workerStartedAt =
    System.currentTimeMillis()

  Log.d(
    LOG_TAG,
    "Démarrage de la synchronisation Guardian, " +
      "tentative=$runAttemptCount"
  )

  return try {
    GuardianApplicationProvider.initialize(
      applicationContext
    )

    GuardianAppCheckInitializer.initialize(
      applicationContext
    )

    Log.d(
      LOG_TAG,
      "App Check prêt=" +
        GuardianAppCheckInitializer.isInitialized()
    )

    val config =
      GuardianNativeConfigStore(
        applicationContext
      ).loadConfig()

      if (config == null) {
        Log.e(
          LOG_TAG,
          "Upload impossible : configuration native absente"
        )

        Result.failure()
      } else {
        uploadPendingEvents(
          config
        )
      }
    } catch (error: IOException) {
      Log.e(
        LOG_TAG,
        "Erreur réseau pendant l'upload",
        error
      )

      Result.retry()
    } catch (error: IllegalArgumentException) {
      Log.e(
        LOG_TAG,
        "Données Guardian invalides",
        error
      )

      Result.failure()
    } catch (error: JSONException) {
      Log.e(
        LOG_TAG,
        "Réponse JSON Guardian invalide",
        error
      )

      Result.retry()
    } catch (error: Exception) {
      Log.e(
        LOG_TAG,
        "Échec inattendu de l'upload Guardian",
        error
      )

      Result.retry()
    } finally {
      val duration =
        System.currentTimeMillis() -
          workerStartedAt

      Log.i(
        LOG_TAG,
        "Synchronisation Guardian terminée en ${duration} ms"
      )
    }
  }

  private suspend fun uploadPendingEvents(
    config: GuardianNativeConfig
  ): Result {
    val locationStore =
      GuardianLocationStore(
        applicationContext
      )

    return try {
      /*
       * Priorité absolue aux événements critiques.
       * Un SOS manuel ne doit jamais attendre que les
       * positions GPS en retard soient vidées.
       *
       * Ordre de synchronisation :
       * SOS -> PTI -> Voice -> GPS
       */
      val sosResult =
        uploadPendingSosEvents(
          config = config,
          locationStore = locationStore
        )

      when (sosResult) {
        SyncResult.SUCCESS -> {
          // Continuer avec les événements PTI.
        }

        SyncResult.RETRY -> {
          refreshPendingCount()
          return Result.retry()
        }

        SyncResult.FAILURE -> {
          refreshPendingCount()
          return Result.failure()
        }
      }

      val ptiResult =
        uploadPendingPtiEvents(
          config = config,
          locationStore = locationStore
        )

      when (ptiResult) {
        SyncResult.SUCCESS -> {
          // Continuer avec les événements Voice.
        }

        SyncResult.RETRY -> {
          refreshPendingCount()
          return Result.retry()
        }

        SyncResult.FAILURE -> {
          refreshPendingCount()
          return Result.failure()
        }
      }

      val voiceResult =
  uploadPendingVoiceEvents(
    config = config,
    locationStore = locationStore
  )

when (voiceResult) {
  SyncResult.SUCCESS -> {
    // Continuer avec les événements SERVICE.
  }

  SyncResult.RETRY -> {
    refreshPendingCount()
    return Result.retry()
  }

  SyncResult.FAILURE -> {
    refreshPendingCount()
    return Result.failure()
  }
}

val serviceResult =
  uploadPendingServiceEvents(
    config = config,
    locationStore = locationStore
  )

when (serviceResult) {
  SyncResult.SUCCESS -> {
    // Continuer avec les événements GPS.
  }

  SyncResult.RETRY -> {
    refreshPendingCount()
    return Result.retry()
  }

  SyncResult.FAILURE -> {
    refreshPendingCount()
    return Result.failure()
  }
}

  val serviceHealthResult =
  uploadPendingServiceHealthEvents(
    config = config,
    locationStore = locationStore
  )

when (serviceHealthResult) {
  SyncResult.SUCCESS -> {
    // Continuer avec les événements GPS.
  }

  SyncResult.RETRY -> {
    refreshPendingCount()
    return Result.retry()
  }

  SyncResult.FAILURE -> {
    refreshPendingCount()
    return Result.failure()
  }
}

      val locationResult =
        uploadPendingLocationEvents(
          config = config,
          locationStore = locationStore
        )

      refreshPendingCount()

      when (locationResult) {
        SyncResult.SUCCESS ->
          Result.success()

        SyncResult.RETRY ->
          Result.retry()

        SyncResult.FAILURE ->
          Result.failure()
      }
    } finally {
      try {
        locationStore.close()
      } catch (error: Exception) {
        Log.e(
          LOG_TAG,
          "Erreur pendant la fermeture du store SQLite",
          error
        )
      }
    }
  }

  private suspend fun uploadPendingLocationEvents(
    config: GuardianNativeConfig,
    locationStore: GuardianLocationStore
  ): SyncResult {
    var processedBatchCount = 0
    var totalConfirmedCount = 0

    while (
      processedBatchCount <
      MAX_BATCHES_PER_TYPE
    ) {
      if (isStopped) {
        Log.w(
          LOG_TAG,
          "Worker arrêté pendant la synchronisation GPS"
        )

        refreshPendingCount()

        return SyncResult.RETRY
      }

      val events =
        locationStore
          .getPendingLocationEvents(
            BATCH_SIZE
          )

      if (events.isEmpty()) {
        Log.d(
          LOG_TAG,
          "Synchronisation GPS terminée : " +
            "lots=$processedBatchCount, " +
            "confirmés=$totalConfirmedCount"
        )

        return SyncResult.SUCCESS
      }

      processedBatchCount += 1

      Log.d(
        LOG_TAG,
        "Traitement GPS lot $processedBatchCount : " +
          "${events.size} événement(s)"
      )

      val encodedBatch =
        GuardianEventBatchEncoder
          .encodeLocationEvents(
            context = applicationContext,
            config = config,
            events = events
          )

      val batchResult =
        uploadEncodedBatch(
          encodedBatch = encodedBatch,
          markUploaded = { eventIds ->
            locationStore.markUploaded(
              eventIds
            )
          }
        )

      totalConfirmedCount +=
        batchResult.confirmedCount

      when (batchResult.resultType) {
        BatchResultType.SUCCESS -> {
          // Continuer avec le lot GPS suivant.
        }

        BatchResultType.RETRY,
        BatchResultType.AUTH_RETRY -> {
          refreshPendingCount()
          return SyncResult.RETRY
        }

        BatchResultType.FAILURE -> {
          refreshPendingCount()
          return SyncResult.FAILURE
        }
      }
    }

    refreshPendingCount()

    val remainingCount =
      locationStore.getPendingCount()

    Log.d(
      LOG_TAG,
      "Limite GPS atteinte : " +
        "lots=$processedBatchCount, " +
        "confirmés=$totalConfirmedCount, " +
        "restants=$remainingCount"
    )

    return if (remainingCount > 0) {
      SyncResult.RETRY
    } else {
      SyncResult.SUCCESS
    }
  }

  private suspend fun uploadPendingPtiEvents(
    config: GuardianNativeConfig,
    locationStore: GuardianLocationStore
  ): SyncResult {
    var processedBatchCount = 0
    var totalConfirmedCount = 0

    while (
      processedBatchCount <
      MAX_BATCHES_PER_TYPE
    ) {
      if (isStopped) {
        Log.w(
          LOG_TAG,
          "Worker arrêté pendant la synchronisation PTI"
        )

        return SyncResult.RETRY
      }

      val events =
        locationStore
          .getPendingPtiEvents(
            BATCH_SIZE
          )

      if (events.isEmpty()) {
        Log.d(
          LOG_TAG,
          "Synchronisation PTI terminée : " +
            "lots=$processedBatchCount, " +
            "confirmés=$totalConfirmedCount"
        )

        return SyncResult.SUCCESS
      }

      processedBatchCount += 1

      Log.d(
        LOG_TAG,
        "Traitement PTI lot $processedBatchCount : " +
          "${events.size} événement(s)"
      )

      val encodedBatch =
        GuardianEventBatchEncoder
          .encodePtiEvents(
            context = applicationContext,
            config = config,
            events = events
          )

      val batchResult =
        uploadEncodedBatch(
          encodedBatch = encodedBatch,
          markUploaded = { eventIds ->
            locationStore.markPtiUploaded(
              eventIds
            )
          }
        )

      totalConfirmedCount +=
        batchResult.confirmedCount

      when (batchResult.resultType) {
        BatchResultType.SUCCESS -> {
          // Continuer avec le lot PTI suivant.
        }

        BatchResultType.RETRY,
        BatchResultType.AUTH_RETRY -> {
          return SyncResult.RETRY
        }

        BatchResultType.FAILURE -> {
          return SyncResult.FAILURE
        }
      }
    }

    val remainingCount =
      locationStore.getPendingPtiCount()

    Log.d(
      LOG_TAG,
      "Limite PTI atteinte : " +
        "lots=$processedBatchCount, " +
        "confirmés=$totalConfirmedCount, " +
        "restants=$remainingCount"
    )

    return if (remainingCount > 0) {
      SyncResult.RETRY
    } else {
      SyncResult.SUCCESS
    }
  }

  private suspend fun uploadPendingVoiceEvents(
    config: GuardianNativeConfig,
    locationStore: GuardianLocationStore
  ): SyncResult {
    var processedBatchCount = 0
    var totalConfirmedCount = 0

    while (
      processedBatchCount <
      MAX_BATCHES_PER_TYPE
    ) {
      if (isStopped) {
        Log.w(
          LOG_TAG,
          "Worker arrêté pendant la synchronisation Voice"
        )

        return SyncResult.RETRY
      }

      val events =
        locationStore
          .getPendingVoiceEvents(
            BATCH_SIZE
          )

      if (events.isEmpty()) {
        Log.d(
          LOG_TAG,
          "Synchronisation Voice terminée : " +
            "lots=$processedBatchCount, " +
            "confirmés=$totalConfirmedCount"
        )

        return SyncResult.SUCCESS
      }

      processedBatchCount += 1

      Log.d(
        LOG_TAG,
        "Traitement Voice lot $processedBatchCount : " +
          "${events.size} événement(s)"
      )

      val encodedBatch =
        GuardianEventBatchEncoder
          .encodeVoiceEvents(
            context = applicationContext,
            config = config,
            events = events
          )

      val batchResult =
        uploadEncodedBatch(
          encodedBatch = encodedBatch,
          markUploaded = { eventIds ->
            locationStore.markVoiceUploaded(
              eventIds
            )
          }
        )

      totalConfirmedCount +=
        batchResult.confirmedCount

      when (batchResult.resultType) {
        BatchResultType.SUCCESS -> {
          // Continuer avec le lot Voice suivant.
        }

        BatchResultType.RETRY,
        BatchResultType.AUTH_RETRY -> {
          return SyncResult.RETRY
        }

        BatchResultType.FAILURE -> {
          return SyncResult.FAILURE
        }
      }
    }

    val remainingCount =
      locationStore.getPendingVoiceCount()

    Log.d(
      LOG_TAG,
      "Limite Voice atteinte : " +
        "lots=$processedBatchCount, " +
        "confirmés=$totalConfirmedCount, " +
        "restants=$remainingCount"
    )

    return if (remainingCount > 0) {
      SyncResult.RETRY
    } else {
      SyncResult.SUCCESS
    }
  }

  private suspend fun uploadPendingServiceEvents(
  config: GuardianNativeConfig,
  locationStore: GuardianLocationStore
): SyncResult {

  var processedBatchCount = 0
  var totalConfirmedCount = 0

  while (
    processedBatchCount <
    MAX_BATCHES_PER_TYPE
  ) {

    if (isStopped) {
      Log.w(
        LOG_TAG,
        "Worker arrêté pendant la synchronisation SERVICE"
      )

      return SyncResult.RETRY
    }

    val events =
      locationStore
        .getPendingServiceEvents(
          BATCH_SIZE
        )

    if (events.isEmpty()) {
      Log.d(
        LOG_TAG,
        "Synchronisation SERVICE terminée : " +
          "lots=$processedBatchCount, " +
          "confirmés=$totalConfirmedCount"
      )

      return SyncResult.SUCCESS
    }

    processedBatchCount += 1

    Log.d(
      LOG_TAG,
      "Traitement SERVICE lot $processedBatchCount : " +
        "${events.size} événement(s)"
    )

    val encodedBatch =
      GuardianEventBatchEncoder
        .encodeServiceEvents(
          context = applicationContext,
          config = config,
          events = events
        )

    val batchResult =
      uploadEncodedBatch(
        encodedBatch = encodedBatch,
        markUploaded = { eventIds ->
          locationStore.markServiceUploaded(
            eventIds
          )
        }
      )

    totalConfirmedCount +=
      batchResult.confirmedCount

    when (batchResult.resultType) {

      BatchResultType.SUCCESS -> {
        // Continuer
      }

      BatchResultType.RETRY,
      BatchResultType.AUTH_RETRY -> {
        return SyncResult.RETRY
      }

      BatchResultType.FAILURE -> {
        return SyncResult.FAILURE
      }
    }
  }

  val remainingCount =
    locationStore
      .getPendingServiceCount()

  Log.d(
    LOG_TAG,
    "Limite SERVICE atteinte : " +
      "lots=$processedBatchCount, " +
      "confirmés=$totalConfirmedCount, " +
      "restants=$remainingCount"
  )

  return if (remainingCount > 0) {
    SyncResult.RETRY
  } else {
    SyncResult.SUCCESS
  }
}

  private suspend fun uploadPendingServiceHealthEvents(
  config: GuardianNativeConfig,
  locationStore: GuardianLocationStore
): SyncResult {
  var processedBatchCount = 0
  var totalConfirmedCount = 0

  while (
    processedBatchCount <
      MAX_BATCHES_PER_TYPE
  ) {
    if (isStopped) {
      Log.w(
        LOG_TAG,
        "Worker arrêté pendant la synchronisation SERVICE_HEALTH"
      )

      return SyncResult.RETRY
    }

    val events =
      locationStore
        .getPendingServiceHealthEvents(
          BATCH_SIZE
        )

    if (events.isEmpty()) {
      Log.d(
        LOG_TAG,
        "Synchronisation SERVICE_HEALTH terminée : " +
          "lots=$processedBatchCount, " +
          "confirmés=$totalConfirmedCount"
      )

      return SyncResult.SUCCESS
    }

    processedBatchCount += 1

    Log.d(
      LOG_TAG,
      "Traitement SERVICE_HEALTH lot $processedBatchCount : " +
        "${events.size} événement(s)"
    )

    val encodedBatch =
      GuardianEventBatchEncoder
        .encodeServiceHealthEvents(
          context = applicationContext,
          config = config,
          events = events
        )

    val batchResult =
      uploadEncodedBatch(
        encodedBatch = encodedBatch,
        markUploaded = { eventIds ->
          locationStore
            .markServiceHealthUploaded(
              eventIds
            )
        }
      )

    totalConfirmedCount +=
      batchResult.confirmedCount

    when (batchResult.resultType) {
      BatchResultType.SUCCESS -> {
        // Continuer avec le lot suivant.
      }

      BatchResultType.RETRY,
      BatchResultType.AUTH_RETRY -> {
        return SyncResult.RETRY
      }

      BatchResultType.FAILURE -> {
        return SyncResult.FAILURE
      }
    }
  }

  val remainingCount =
    locationStore
      .getPendingServiceHealthCount()

  Log.d(
    LOG_TAG,
    "Limite SERVICE_HEALTH atteinte : " +
      "lots=$processedBatchCount, " +
      "confirmés=$totalConfirmedCount, " +
      "restants=$remainingCount"
  )

  return if (remainingCount > 0) {
    SyncResult.RETRY
  } else {
    SyncResult.SUCCESS
  }
}

  private suspend fun uploadPendingSosEvents(
    config: GuardianNativeConfig,
    locationStore: GuardianLocationStore
  ): SyncResult {
    var processedBatchCount = 0
    var totalConfirmedCount = 0

    while (
      processedBatchCount <
      MAX_BATCHES_PER_TYPE
    ) {
      if (isStopped) {
        Log.w(
          LOG_TAG,
          "Worker arrêté pendant la synchronisation SOS"
        )

        return SyncResult.RETRY
      }

      val events =
        locationStore
          .getPendingSosEvents(
            BATCH_SIZE
          )

      if (events.isEmpty()) {
        Log.d(
          LOG_TAG,
          "Synchronisation SOS terminée : " +
            "lots=$processedBatchCount, " +
            "confirmés=$totalConfirmedCount"
        )

        return SyncResult.SUCCESS
      }

      processedBatchCount += 1

      Log.d(
        LOG_TAG,
        "Traitement SOS lot $processedBatchCount : " +
          "${events.size} événement(s)"
      )

      val encodedBatch =
        GuardianEventBatchEncoder
          .encodeSosEvents(
            context = applicationContext,
            config = config,
            events = events
          )

      val batchResult =
        uploadEncodedBatch(
          encodedBatch = encodedBatch,
          markUploaded = { eventIds ->
            locationStore.markSosUploaded(
              eventIds
            )
          }
        )

      totalConfirmedCount +=
        batchResult.confirmedCount

      when (batchResult.resultType) {
        BatchResultType.SUCCESS -> {
          // Continuer avec le lot SOS suivant.
        }

        BatchResultType.RETRY,
        BatchResultType.AUTH_RETRY -> {
          return SyncResult.RETRY
        }

        BatchResultType.FAILURE -> {
          return SyncResult.FAILURE
        }
      }
    }

    val remainingCount =
      locationStore.getPendingSosCount()

    Log.d(
      LOG_TAG,
      "Limite SOS atteinte : " +
        "lots=$processedBatchCount, " +
        "confirmés=$totalConfirmedCount, " +
        "restants=$remainingCount"
    )

    return if (remainingCount > 0) {
      SyncResult.RETRY
    } else {
      SyncResult.SUCCESS
    }
  }

  private suspend fun uploadEncodedBatch(
    encodedBatch:
      GuardianEventBatchEncoder.EncodedBatch,
    markUploaded: (List<String>) -> Unit
  ): BatchResult {
    val tokens =
      GuardianNativeAuthProvider
        .getTokens(
          forceRefresh = false
        )

    Log.d(
      LOG_TAG,
      "Envoi batch ${encodedBatch.batchId} (${encodedBatch.eventIds.size} événements)"
    )

    val response =
      GuardianApiClient
        .uploadEventBatch(
          jsonBody =
            encodedBatch.json,

          authToken =
            tokens.authToken,

          appCheckToken =
            tokens.appCheckToken
        )

    val firstResult =
      handleApiResponse(
        response = response,
        expectedEventIds =
          encodedBatch.eventIds,
        markUploaded =
          markUploaded
      )

    if (
      firstResult.resultType !=
      BatchResultType.AUTH_RETRY
    ) {
      return firstResult
    }

    return retryBatchWithFreshTokens(
      expectedEventIds =
        encodedBatch.eventIds,
      jsonBody =
        encodedBatch.json,
      markUploaded =
        markUploaded
    )
  }

  private suspend fun retryBatchWithFreshTokens(
    expectedEventIds: List<String>,
    jsonBody: String,
    markUploaded: (List<String>) -> Unit
  ): BatchResult {
    Log.w(
      LOG_TAG,
      "Renouvellement des jetons Firebase demandé"
    )

    val refreshedTokens =
      GuardianNativeAuthProvider
        .getTokens(
          forceRefresh = true
        )

    val refreshedResponse =
      GuardianApiClient
        .uploadEventBatch(
          jsonBody = jsonBody,
          authToken =
            refreshedTokens.authToken,
          appCheckToken =
            refreshedTokens.appCheckToken
        )

    return handleApiResponse(
      response = refreshedResponse,
      expectedEventIds =
        expectedEventIds,
      markUploaded =
        markUploaded
    )
  }

  private fun handleApiResponse(
    response: GuardianApiClient.UploadResponse,
    expectedEventIds: List<String>,
    markUploaded: (List<String>) -> Unit
  ): BatchResult {
    Log.d(
      LOG_TAG,
      "Réponse API Guardian : " +
        "status=${response.statusCode}, " +
        "requestId=${response.requestId}"
    )

    if (!response.successful) {
      return handleHttpFailure(
        statusCode =
          response.statusCode,
        responseBody =
          response.body
      )
    }

    val responseBody =
      response.body
        ?.takeIf {
          it.isNotBlank()
        }
        ?: run {
          Log.e(
            LOG_TAG,
            "Réponse API vide malgré un statut réussi"
          )

          return BatchResult.retry()
        }

    val acceptedEventIds =
      parseAcceptedEventIds(
        responseBody
      )

    val expectedIdSet =
      expectedEventIds.toSet()

    val validAcceptedIds =
      acceptedEventIds
        .asSequence()
        .map {
          it.trim()
        }
        .filter {
          it.isNotBlank()
        }
        .filter {
          it in expectedIdSet
        }
        .distinct()
        .toList()

    if (validAcceptedIds.isNotEmpty()) {
      markUploaded(
        validAcceptedIds
      )
    }

    val unconfirmedEventIds =
      expectedEventIds.filterNot {
        it in validAcceptedIds
      }

    Log.d(
      LOG_TAG,
      "Lot traité : " +
        "attendus=${expectedEventIds.size}, " +
        "confirmés=${validAcceptedIds.size}, " +
        "nonConfirmés=${unconfirmedEventIds.size}"
    )

    if (unconfirmedEventIds.isNotEmpty()) {
      Log.w(
        LOG_TAG,
        "Événements non confirmés : " +
          unconfirmedEventIds.joinToString(
            limit = 10
          )
      )

      return BatchResult.retry(
        confirmedCount =
          validAcceptedIds.size
      )
    }

    return BatchResult.success(
      confirmedCount =
        validAcceptedIds.size
    )
  }

  private fun handleHttpFailure(
    statusCode: Int,
    responseBody: String?
  ): BatchResult {
    Log.e(
      LOG_TAG,
      "Échec HTTP Guardian : " +
        "status=$statusCode, " +
        "body=${responseBody.orEmpty()}"
    )

    return when {
      statusCode == 401 ||
        statusCode == 403 -> {
        BatchResult.authRetry()
      }

      GuardianApiClient
        .isRetryableStatus(
          statusCode
        ) -> {
        BatchResult.retry()
      }

      statusCode in 400..499 -> {
        BatchResult.failure()
      }

      else -> {
        BatchResult.retry()
      }
    }
  }

  private fun parseAcceptedEventIds(
    responseBody: String
  ): List<String> {
    val responseJson =
      JSONObject(
        responseBody
      )

    val acceptedArray =
      responseJson.optJSONArray(
        "acceptedEventIds"
      )
        ?: throw JSONException(
          "Le champ acceptedEventIds est absent"
        )

    val acceptedIds =
      mutableListOf<String>()

    for (
      index in 0 until acceptedArray.length()
    ) {
      val eventId =
        acceptedArray
          .optString(index)
          .trim()

      if (eventId.isNotBlank()) {
        acceptedIds.add(
          eventId
        )
      }
    }

    return acceptedIds
  }

  private fun refreshPendingCount() {
    GuardianLocationEngine
      .refreshPendingCount(
        applicationContext
      )
  }

  private enum class SyncResult {
    SUCCESS,
    RETRY,
    FAILURE
  }

  private enum class BatchResultType {
    SUCCESS,
    RETRY,
    AUTH_RETRY,
    FAILURE
  }

  private data class BatchResult(
    val resultType: BatchResultType,
    val confirmedCount: Int = 0
  ) {
    companion object {
      fun success(
        confirmedCount: Int = 0
      ) =
        BatchResult(
          resultType =
            BatchResultType.SUCCESS,
          confirmedCount =
            confirmedCount
        )

      fun retry(
        confirmedCount: Int = 0
      ) =
        BatchResult(
          resultType =
            BatchResultType.RETRY,
          confirmedCount =
            confirmedCount
        )

      fun authRetry() =
        BatchResult(
          resultType =
            BatchResultType.AUTH_RETRY
        )

      fun failure() =
        BatchResult(
          resultType =
            BatchResultType.FAILURE
        )
    }
  }
}