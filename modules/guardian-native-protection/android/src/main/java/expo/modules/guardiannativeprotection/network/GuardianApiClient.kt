package expo.modules.guardiannativeprotection

import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

object GuardianApiClient {

  private const val LOG_TAG =
    "GuardianApiClient"

  private const val API_PATH =
    "/api/v1/events/ingest"

  private const val GUARDIAN_CORE_VERSION =
    "1.0.0"

  private const val MAX_RESPONSE_BODY_LENGTH =
    100_000

  private const val MAX_REQUEST_BODY_LENGTH =
    2_000_000

  private const val CONNECT_TIMEOUT_SECONDS =
    15L

  private const val READ_TIMEOUT_SECONDS =
    20L

  private const val WRITE_TIMEOUT_SECONDS =
    20L

  private const val CALL_TIMEOUT_SECONDS =
    30L

  private val jsonMediaType =
    "application/json; charset=utf-8"
      .toMediaType()

  private val httpClient =
    OkHttpClient.Builder()
      .connectTimeout(
        CONNECT_TIMEOUT_SECONDS,
        TimeUnit.SECONDS
      )
      .readTimeout(
        READ_TIMEOUT_SECONDS,
        TimeUnit.SECONDS
      )
      .writeTimeout(
        WRITE_TIMEOUT_SECONDS,
        TimeUnit.SECONDS
      )
      .callTimeout(
        CALL_TIMEOUT_SECONDS,
        TimeUnit.SECONDS
      )
      .retryOnConnectionFailure(
        true
      )
      .build()

  data class UploadResponse(
    val successful: Boolean,
    val statusCode: Int,
    val body: String?,
    val requestId: String
  )

  /**
   * Envoie un lot d'événements Guardian vers l'API.
   *
   * La suppression des événements SQLite reste sous la
   * responsabilité exclusive de GuardianUploadWorker.
   */
  fun uploadEventBatch(
    jsonBody: String,
    authToken: String,
    appCheckToken: String
  ): UploadResponse {
    val baseUrl =
      getValidatedBaseUrl()

    val requestJson =
      validateRequestData(
        jsonBody = jsonBody,
        authToken = authToken,
        appCheckToken = appCheckToken
      )

    val batchId =
      extractBatchId(
        requestJson
      )

    val requestId =
      UUID.randomUUID()
        .toString()

    val compressedBody =
      gzip(
        jsonBody
      )

    val request =
      Request.Builder()
        .url(
          "$baseUrl$API_PATH"
        )
        .post(
          compressedBody.toRequestBody(
            jsonMediaType
          )
        )
        .header(
          "Accept",
          "application/json"
        )
        .header(
          "Content-Encoding",
          "gzip"
        )
        .header(
          "Authorization",
          "Bearer $authToken"
        )
        .header(
          "X-Firebase-AppCheck",
          appCheckToken
        )
        .header(
          "X-Guardian-Core-Version",
          GUARDIAN_CORE_VERSION
        )
        .header(
          "X-Guardian-Schema-Version",
          GuardianEventBatchEncoder
            .SCHEMA_VERSION
            .toString()
        )
        .header(
          "X-Guardian-Batch-Id",
          batchId
        )
        .header(
          "X-Guardian-Request-Id",
          requestId
        )
        .header(
          "User-Agent",
          "GuardianVoice/$GUARDIAN_CORE_VERSION"
        )
        .build()

    httpClient
      .newCall(
        request
      )
      .execute()
      .use { response ->
        val responseContentType =
          response.body
            ?.contentType()
            ?.toString()

        val rawResponseBody =
          response.body
            ?.string()

        val safeResponseBody =
          rawResponseBody
            ?.take(
              MAX_RESPONSE_BODY_LENGTH
            )

        if (!response.isSuccessful) {
          Log.w(
            LOG_TAG,
            "Échec HTTP Guardian : " +
              "status=${response.code}, " +
              "requestId=$requestId, " +
              "batchId=$batchId, " +
              "contentType=${responseContentType.orEmpty()}"
          )
        }

        if (response.isSuccessful) {
          require(
            responseContentType
              ?.contains(
                "application/json",
                ignoreCase = true
              ) == true
          ) {
            "Réponse Guardian réussie mais non JSON : " +
              responseContentType.orEmpty()
          }

          require(
            !safeResponseBody.isNullOrBlank()
          ) {
            "Réponse Guardian réussie mais vide"
          }

          runCatching {
            JSONObject(
              safeResponseBody
            )
          }.getOrElse { error ->
            throw IllegalStateException(
              "Réponse Guardian JSON invalide",
              error
            )
          }
        }

        val responseRequestId =
          response.header(
            "X-Guardian-Request-Id"
          )
            ?.trim()
            ?.takeIf {
              it.isNotBlank()
            }
            ?: requestId

        return UploadResponse(
          successful =
            response.isSuccessful,

          statusCode =
            response.code,

          body =
            safeResponseBody,

          requestId =
            responseRequestId
        )
      }
  }

  /**
   * Indique si l'erreur HTTP est probablement temporaire.
   */
  fun isRetryableStatus(
    statusCode: Int
  ): Boolean {
    return statusCode == 408 ||
      statusCode == 425 ||
      statusCode == 429 ||
      statusCode in 500..599
  }

  private fun getValidatedBaseUrl(): String {
    val baseUrl =
      BuildConfig
        .GUARDIAN_API_BASE_URL
        .trim()
        .trimEnd('/')

    require(
      baseUrl.isNotBlank()
    ) {
      "Guardian API base URL absente"
    }

    require(
      baseUrl.startsWith(
        "https://"
      )
    ) {
      "Guardian API doit utiliser HTTPS"
    }

    require(
      !baseUrl.contains(
        ' '
      )
    ) {
      "URL Guardian invalide"
    }

    return baseUrl
  }

  /**
   * Valide le lot et retourne son objet JSON déjà parsé.
   */
  private fun validateRequestData(
    jsonBody: String,
    authToken: String,
    appCheckToken: String
  ): JSONObject {
    require(
      jsonBody.isNotBlank()
    ) {
      "Corps JSON Guardian vide"
    }

    require(
      jsonBody.toByteArray(
        Charsets.UTF_8
      ).size <=
        MAX_REQUEST_BODY_LENGTH
    ) {
      "Lot Guardian trop volumineux"
    }

    require(
      authToken.isNotBlank()
    ) {
      "Jeton Firebase Auth absent"
    }

    require(
      appCheckToken.isNotBlank()
    ) {
      "Jeton Firebase App Check absent"
    }

    return runCatching {
      JSONObject(
        jsonBody
      )
    }.getOrElse { error ->
      throw IllegalArgumentException(
        "Corps JSON Guardian invalide",
        error
      )
    }
  }

  private fun extractBatchId(
    requestJson: JSONObject
  ): String {
    val batchId =
      requestJson
        .optString(
          "batchId"
        )
        .trim()

    require(
      batchId.isNotBlank()
    ) {
      "batchId Guardian absent"
    }

    return batchId
  }

  private fun gzip(
    value: String
  ): ByteArray {
    val output =
      ByteArrayOutputStream()

    GZIPOutputStream(
      output
    ).use { gzipStream ->
      gzipStream.write(
        value.toByteArray(
          Charsets.UTF_8
        )
      )
    }

    return output.toByteArray()
  }
}