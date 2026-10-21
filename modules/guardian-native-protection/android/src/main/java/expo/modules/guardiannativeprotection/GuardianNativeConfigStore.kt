package expo.modules.guardiannativeprotection

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first

/*
 * Une seule instance DataStore pour tout le processus Android.
 * Cette déclaration doit rester au niveau supérieur du fichier.
 */
private val Context.guardianNativeConfigDataStore:
  DataStore<Preferences> by preferencesDataStore(
    name = "guardian_native_config"
  )

class GuardianNativeConfigStore(
  context: Context
) {

  private companion object {
    const val MIN_UPLOAD_INTERVAL_SECONDS =
      5

    const val MAX_UPLOAD_INTERVAL_SECONDS =
      3600

    const val DEFAULT_UPLOAD_INTERVAL_SECONDS =
      30

    const val CURRENT_CONFIG_VERSION =
      1
  }

  private val appContext =
    context.applicationContext

  private val dataStore =
    appContext.guardianNativeConfigDataStore

  private object Keys {
    val childId =
      stringPreferencesKey(
        "child_id"
      )

    val childName =
      stringPreferencesKey(
        "child_name"
      )

    val familyId =
      stringPreferencesKey(
        "family_id"
      )

    val familyCode =
      stringPreferencesKey(
        "family_code"
      )

    val deviceId =
      stringPreferencesKey(
        "device_id"
      )

    val gpsEnabled =
      booleanPreferencesKey(
        "gps_enabled"
      )

    val voiceProfile = stringPreferencesKey("voice_profile")
    val voiceDiagnosticsEnabled = booleanPreferencesKey("voice_diagnostics_enabled")

    val voiceEnabled =
      booleanPreferencesKey(
        "voice_enabled"
      )

    val ptiEnabled =
      booleanPreferencesKey(
        "pti_enabled"
      )

    val uploadIntervalSeconds =
      intPreferencesKey(
        "upload_interval_seconds"
      )

    val version =
      intPreferencesKey(
        "config_version"
      )

    val lastUpdated =
      longPreferencesKey(
        "last_updated"
      )
  }

  /**
   * Sauvegarde une configuration complète du cœur natif.
   *
   * Une configuration invalide n'est jamais écrite dans
   * DataStore.
   */
  suspend fun saveConfig(
    config: GuardianNativeConfig
  ) {
    val normalizedConfig =
      normalizeAndValidateConfig(
        config
      )

    val currentConfig =
      loadConfig()

    /*
     * Évite une écriture disque lorsque la configuration
     * persistée est déjà identique.
     */
    if (
      currentConfig ==
      normalizedConfig
    ) {
      return
    }

    dataStore.edit { preferences ->
      preferences[Keys.childId] =
        normalizedConfig.childId

      preferences[Keys.childName] =
        normalizedConfig.childName

      preferences[Keys.familyId] =
        normalizedConfig.familyId

      preferences[Keys.familyCode] =
        normalizedConfig.familyCode

      preferences[Keys.deviceId] =
        normalizedConfig.deviceId

      preferences[Keys.gpsEnabled] =
        normalizedConfig.gpsEnabled

      preferences[Keys.voiceProfile] = normalizedConfig.voiceProfile
      preferences[Keys.voiceDiagnosticsEnabled] = normalizedConfig.voiceDiagnosticsEnabled

      preferences[Keys.voiceEnabled] =
        normalizedConfig.voiceEnabled

      preferences[Keys.ptiEnabled] =
        normalizedConfig.ptiEnabled

      preferences[
        Keys.uploadIntervalSeconds
      ] =
        normalizedConfig
          .uploadIntervalSeconds

      preferences[Keys.version] =
        normalizedConfig.version

      preferences[Keys.lastUpdated] =
        normalizedConfig.lastUpdated
    }
  }

  /**
   * Charge la configuration actuellement persistée.
   *
   * Une configuration partielle ou inutilisable retourne
   * null. Le cœur natif ne doit jamais fonctionner avec
   * une identité incomplète.
   */
  suspend fun loadConfig():
    GuardianNativeConfig? {
    val preferences =
      dataStore.data
        .catch { error ->
          if (
            error is IOException
          ) {
            emit(
              emptyPreferences()
            )
          } else {
            throw error
          }
        }
        .first()

    val childId =
      preferences[Keys.childId]
        ?.trim()
        .orEmpty()

    val childName =
      preferences[Keys.childName]
        ?.trim()
        .orEmpty()

    val familyId =
      preferences[Keys.familyId]
        ?.trim()
        .orEmpty()

    val familyCode =
      preferences[Keys.familyCode]
        ?.trim()
        .orEmpty()

    val deviceId =
      preferences[Keys.deviceId]
        ?.trim()
        .orEmpty()

    /*
     * Sans identité complète, la configuration
     * n'est pas exploitable par le Native Core.
     */
    if (
      childId.isBlank() ||
      childName.isBlank() ||
      familyId.isBlank() ||
      familyCode.isBlank() ||
      deviceId.isBlank()
    ) {
      return null
    }

    val storedVersion =
      preferences[Keys.version]
        ?: CURRENT_CONFIG_VERSION

    val loadedConfig =
      GuardianNativeConfig(
        childId =
          childId,

        childName =
          childName,

        familyId =
          familyId,

        familyCode =
          familyCode,

        deviceId =
          deviceId,

        gpsEnabled =
          preferences[Keys.gpsEnabled]
            ?: true,

        voiceProfile = preferences[Keys.voiceProfile] ?: "DISTANT",
        voiceDiagnosticsEnabled = preferences[Keys.voiceDiagnosticsEnabled] ?: false,

        voiceEnabled =
          preferences[Keys.voiceEnabled]
            ?: false,

        ptiEnabled =
          preferences[Keys.ptiEnabled]
            ?: false,

        uploadIntervalSeconds =
          (
            preferences[
              Keys.uploadIntervalSeconds
            ]
              ?: DEFAULT_UPLOAD_INTERVAL_SECONDS
            ).coerceIn(
            MIN_UPLOAD_INTERVAL_SECONDS,
            MAX_UPLOAD_INTERVAL_SECONDS
          ),

        version =
          storedVersion
            .coerceAtLeast(
              1
            ),

        lastUpdated =
          (
            preferences[
              Keys.lastUpdated
            ] ?: 0L
            ).coerceAtLeast(
            0L
          )
      )

    return migrateConfigIfNeeded(
      loadedConfig
    )
  }

  suspend fun hasConfig(): Boolean {
    return loadConfig() != null
  }

  /**
   * Supprime complètement la configuration native.
   *
   * Cette action doit uniquement être utilisée lors d'une
   * déconnexion, d'une réinitialisation ou d'un changement
   * volontaire de profil enfant.
   */
  suspend fun clearConfig() {
    dataStore.edit { preferences ->
      preferences.clear()
    }
  }

  /**
   * Nettoie et valide la configuration avant écriture.
   */
  private fun normalizeAndValidateConfig(
    config: GuardianNativeConfig
  ): GuardianNativeConfig {
    val normalizedConfig =
      config.copy(
        voiceProfile = GuardianVoiceProfile.parse(config.voiceProfile).name,
        childId =
          config.childId.trim(),

        childName =
          config.childName.trim(),

        familyId =
          config.familyId.trim(),

        familyCode =
          config.familyCode.trim(),

        deviceId =
          config.deviceId.trim(),

        uploadIntervalSeconds =
          config.uploadIntervalSeconds
            .coerceIn(
              MIN_UPLOAD_INTERVAL_SECONDS,
              MAX_UPLOAD_INTERVAL_SECONDS
            ),

        version =
          config.version
            .coerceAtLeast(
              CURRENT_CONFIG_VERSION
            ),

        lastUpdated =
          config.lastUpdated
            .coerceAtLeast(
              0L
            )
      )

    require(
      normalizedConfig.childId
        .isNotBlank()
    ) {
      "Configuration Guardian : childId absent"
    }

    require(
      normalizedConfig.childName
        .isNotBlank()
    ) {
      "Configuration Guardian : childName absent"
    }

    require(
      normalizedConfig.familyId
        .isNotBlank()
    ) {
      "Configuration Guardian : familyId absent"
    }

    require(
      normalizedConfig.familyCode
        .isNotBlank()
    ) {
      "Configuration Guardian : familyCode absent"
    }

    require(
      normalizedConfig.deviceId
        .isNotBlank()
    ) {
      "Configuration Guardian : deviceId absent"
    }

    require(
      normalizedConfig
        .uploadIntervalSeconds in
        MIN_UPLOAD_INTERVAL_SECONDS..
          MAX_UPLOAD_INTERVAL_SECONDS
    ) {
      "Intervalle d'upload Guardian invalide"
    }

    require(
      normalizedConfig.version >=
        CURRENT_CONFIG_VERSION
    ) {
      "Version de configuration Guardian invalide"
    }

    require(
      normalizedConfig.lastUpdated >=
        0L
    ) {
      "Date de mise à jour Guardian invalide"
    }

    return normalizedConfig
  }

  /**
   * Point central des futures migrations DataStore.
   *
   * Pour la version 1, aucune transformation n'est encore
   * nécessaire.
   */
  private fun migrateConfigIfNeeded(
    config: GuardianNativeConfig
  ): GuardianNativeConfig {
    if (
      config.version >=
      CURRENT_CONFIG_VERSION
    ) {
      return config
    }

    return when (
      config.version
    ) {
      else ->
        config.copy(
          version =
            CURRENT_CONFIG_VERSION
        )
    }
  }
}