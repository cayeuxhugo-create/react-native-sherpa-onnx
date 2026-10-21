package expo.modules.guardiannativeprotection

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Point d'entrée unique de planification des uploads du Guardian Native Core.
 *
 * Règles :
 * - scheduleUpload() évite d'empiler un Worker à chaque position GPS.
 * - forceUpload() garantit un passage supplémentaire après le travail courant,
 *   sans annuler un upload déjà en cours.
 * - GuardianUploadWorker reste l'unique composant autorisé à envoyer les
 *   événements SQLite vers la Cloud API.
 */
object GuardianUploadScheduler {

  private const val TAG =
    "GuardianUploadScheduler"

  private const val UNIQUE_UPLOAD_WORK =
    "guardian-native-event-upload"

  private const val WORK_TAG =
    "guardian-upload"

  private const val NORMAL_WORK_TAG =
    "guardian-upload-normal"

  private const val FORCED_WORK_TAG =
    "guardian-upload-forced"

  private const val INITIAL_BACKOFF_SECONDS =
    30L

  /**
   * Planification normale, appelée après la sauvegarde locale d'un événement.
   *
   * KEEP est volontaire : si un upload est déjà en attente ou en cours,
   * inutile d'ajouter un Worker pour chaque nouvelle position. Le Worker
   * existant videra les événements encore présents dans SQLite.
   */
  fun scheduleUpload(
    context: Context
  ): Boolean {
    val request =
      buildUploadRequest(
        forced = false
      )

    return enqueue(
      context = context,
      request = request,
      policy = ExistingWorkPolicy.KEEP,
      reason = "normal"
    )
  }

  /**
 * Planification prioritaire, utilisée pour SOS/PTI/Voice et les demandes
 * manuelles de synchronisation.
 *
 * REPLACE annule le travail unique précédent, y compris lorsqu'il attend
 * dans un backoff, puis programme immédiatement un nouveau passage.
 *
 * Aucun événement n'est perdu : les événements restent dans SQLite jusqu'à
 * leur confirmation explicite par la Cloud API.
 */
  fun forceUpload(
  context: Context
): Boolean {
  val request =
    buildUploadRequest(
      forced = true
    )

  return enqueue(
    context = context,
    request = request,
    policy = ExistingWorkPolicy.REPLACE,
    reason = "prioritaire"
  )
}

  /**
   * Annule la chaîne d'upload unique.
   * À utiliser uniquement lors d'un arrêt volontaire ou d'une réinitialisation
   * complète du Native Core, jamais après un simple échec réseau.
   */
  fun cancelUploads(
    context: Context
  ): Boolean {
    return try {
      WorkManager
        .getInstance(
          context.applicationContext
        )
        .cancelUniqueWork(
          UNIQUE_UPLOAD_WORK
        )

      Log.i(
        TAG,
        "Annulation de la chaîne d'upload demandée"
      )

      true
    } catch (error: Exception) {
      Log.e(
        TAG,
        "Impossible d'annuler la chaîne d'upload",
        error
      )

      false
    }
  }

  private fun buildUploadRequest(
    forced: Boolean
  ): OneTimeWorkRequest {
    val constraints =
      Constraints.Builder()
        .setRequiredNetworkType(
          NetworkType.CONNECTED
        )
        .build()

    val builder =
      OneTimeWorkRequestBuilder<GuardianUploadWorker>()
        .setConstraints(
          constraints
        )
        .setBackoffCriteria(
          BackoffPolicy.EXPONENTIAL,
          INITIAL_BACKOFF_SECONDS,
          TimeUnit.SECONDS
        )
        .addTag(
          WORK_TAG
        )
        .addTag(
          if (forced) {
            FORCED_WORK_TAG
          } else {
            NORMAL_WORK_TAG
          }
        )

    if (forced) {
      builder.setExpedited(
        OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST
      )
    }

    return builder.build()
  }

  private fun enqueue(
    context: Context,
    request: OneTimeWorkRequest,
    policy: ExistingWorkPolicy,
    reason: String
  ): Boolean {
    return try {
      WorkManager
        .getInstance(
          context.applicationContext
        )
        .enqueueUniqueWork(
          UNIQUE_UPLOAD_WORK,
          policy,
          request
        )

      Log.d(
        TAG,
        "Upload $reason enregistré : " +
          "id=${request.id}, " +
          "policy=$policy"
      )

      true
    } catch (error: Exception) {
      /*
       * Un échec de planification ne doit jamais faire croire que la sauvegarde
       * SQLite a échoué. L'événement reste durablement en attente et pourra être
       * repris lors d'une prochaine planification.
       */
      Log.e(
        TAG,
        "Échec de planification de l'upload $reason",
        error
      )

      false
    }
  }
}