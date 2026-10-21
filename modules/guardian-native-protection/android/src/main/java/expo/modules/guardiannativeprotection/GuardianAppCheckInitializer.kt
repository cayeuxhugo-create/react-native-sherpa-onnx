package expo.modules.guardiannativeprotection

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.AppCheckProviderFactory
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory

object GuardianAppCheckInitializer {

  private const val LOG_TAG = "GuardianAppCheck"

  @Volatile
  private var initialized = false

  @Synchronized
fun initialize(context: Context): Boolean {
  Log.e(
    LOG_TAG,
    "initialize() appelé, initialized=$initialized"
  )

  if (initialized) {
      Log.d(LOG_TAG, "App Check déjà initialisé")
      return true
    }

    val appContext = context.applicationContext

    val firebaseApp =
      FirebaseApp.initializeApp(appContext)
        ?: FirebaseApp.getInstance()

    val debugBuild = isDebuggable(appContext)

    Log.d(
      LOG_TAG,
      "Initialisation App Check : debug=$debugBuild, app=${firebaseApp.name}"
    )

    val providerFactory: AppCheckProviderFactory =
      if (debugBuild) {
        resolveDebugProviderFactory()
      } else {
        PlayIntegrityAppCheckProviderFactory.getInstance()
      }

    FirebaseAppCheck
      .getInstance(firebaseApp)
      .installAppCheckProviderFactory(providerFactory)

    initialized = true

    Log.d(
      LOG_TAG,
      if (debugBuild) {
        "App Check Debug Provider installé"
      } else {
        "App Check Play Integrity installé"
      }
    )

    return true
  }

  fun isInitialized(): Boolean {
    return initialized
  }

  private fun isDebuggable(context: Context): Boolean {
    return (
      context.applicationInfo.flags and
        ApplicationInfo.FLAG_DEBUGGABLE
      ) != 0
  }

  private fun resolveDebugProviderFactory():
    AppCheckProviderFactory {
    return try {
      val providerClass =
        Class.forName(
          "com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory"
        )

      val getInstanceMethod =
        providerClass.getMethod("getInstance")

      getInstanceMethod.invoke(null)
        as AppCheckProviderFactory
    } catch (error: Exception) {
      throw IllegalStateException(
        "Provider Firebase App Check Debug indisponible",
        error
      )
    }
  }
}