package expo.modules.guardiannativeprotection

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

object GuardianIdentityManager {

  private const val LOG_TAG =
    "GuardianIdentityManager"

  /*
   * Empêche le Worker, le moteur GPS ou le bridge React
   * Native de lancer plusieurs connexions anonymes
   * simultanément.
   */
  private val identityMutex =
    Mutex()

  /**
   * Garantit qu'une identité Firebase est disponible.
   *
   * L'utilisateur Firebase déjà restauré par le SDK est
   * conservé. Aucune déconnexion ni recréation inutile
   * n'est effectuée.
   */
  suspend fun ensureNativeUser(): String =
    identityMutex.withLock {
      val auth =
        FirebaseAuth.getInstance()

      /*
       * Première vérification après acquisition du verrou.
       * Firebase Auth restaure normalement automatiquement
       * l'utilisateur enregistré sur l'appareil.
       */
      val existingUser =
        auth.currentUser

      if (existingUser != null) {
        val uid =
          validateUser(
            existingUser
          )

        Log.d(
  LOG_TAG,
  "UID NATIF = $uid"
)

        return@withLock uid
      }

      Log.d(
        LOG_TAG,
        "Création d'une identité Firebase anonyme"
      )

      val authResult =
        try {
          auth
            .signInAnonymously()
            .await()
        } catch (error: Exception) {
          throw IllegalStateException(
            "Connexion Firebase anonyme impossible",
            error
          )
        }

      /*
       * On privilégie currentUser après la connexion.
       * Cela confirme que Firebase Auth a réellement
       * enregistré cette identité comme utilisateur actif.
       */
      val createdUser =
        auth.currentUser
          ?: authResult.user
          ?: throw IllegalStateException(
            "Identité Firebase absente après connexion anonyme"
          )

      val uid =
        validateUser(
          createdUser
        )

      /*
       * Vérification finale : l'utilisateur actif ne doit
       * pas avoir changé pendant l'opération asynchrone.
       */
      val activeUid =
        auth.currentUser
          ?.uid
          ?.trim()
          .orEmpty()

      if (
        activeUid.isBlank() ||
        activeUid != uid
      ) {
        throw IllegalStateException(
          "Identité Firebase incohérente après connexion"
        )
      }

      Log.d(
  LOG_TAG,
  "UID NATIF CRÉÉ = $uid"
)

      return@withLock uid
    }

  private fun validateUser(
    user: FirebaseUser
  ): String {
    val uid =
      user.uid.trim()

    require(
      uid.isNotBlank()
    ) {
      "UID Firebase vide"
    }

    return uid
  }
}