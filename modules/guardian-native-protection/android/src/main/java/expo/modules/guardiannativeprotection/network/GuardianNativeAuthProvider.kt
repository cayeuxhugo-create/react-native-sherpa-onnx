package expo.modules.guardiannativeprotection

import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await

object GuardianNativeAuthProvider {

  /**
   * Ne jamais ajouter les jetons dans toString()
   * ou dans les logs.
   */
  data class GuardianTokens(
    val authToken: String,
    val appCheckToken: String
  ) {
    override fun toString(): String {
      return "GuardianTokens(REDACTED)"
    }
  }

  /**
   * Récupère les deux preuves nécessaires à l'API Guardian :
   *
   * - Firebase Auth identifie l'utilisateur ;
   * - Firebase App Check atteste l'application/appareil.
   */
  suspend fun getTokens(
    forceRefresh: Boolean = false
  ): GuardianTokens {
    GuardianIdentityManager
      .ensureNativeUser()

    val firebaseAuth =
      FirebaseAuth.getInstance()

    val initialUser =
      firebaseAuth.currentUser
        ?: throw IllegalStateException(
          "Utilisateur Firebase absent après initialisation"
        )

    val initialUid =
      initialUser.uid
        .trim()

    require(
      initialUid.isNotBlank()
    ) {
      "UID Firebase absent"
    }

    val authToken =
      try {
        initialUser
          .getIdToken(
            forceRefresh
          )
          .await()
          .token
          ?.trim()
          .orEmpty()
      } catch (error: Exception) {
        throw IllegalStateException(
          "Impossible d'obtenir le jeton Firebase Auth",
          error
        )
      }

    if (authToken.isBlank()) {
      throw IllegalStateException(
        "Jeton Firebase Auth indisponible"
      )
    }

    /*
     * On vérifie que l'identité n'a pas changé pendant
     * la récupération asynchrone du jeton.
     */
    val currentUid =
      firebaseAuth.currentUser
        ?.uid
        ?.trim()
        .orEmpty()

    if (
      currentUid.isBlank() ||
      currentUid != initialUid
    ) {
      throw IllegalStateException(
        "Identité Firebase modifiée pendant la récupération du jeton"
      )
    }

    val appCheckToken =
      try {
        FirebaseAppCheck
          .getInstance()
          .getAppCheckToken(
            forceRefresh
          )
          .await()
          .token
          .trim()
      } catch (error: Exception) {
        throw IllegalStateException(
          "Impossible d'obtenir le jeton Firebase App Check",
          error
        )
      }

    if (appCheckToken.isBlank()) {
      throw IllegalStateException(
        "Jeton Firebase App Check indisponible"
      )
    }

    return GuardianTokens(
      authToken =
        authToken,
      appCheckToken =
        appCheckToken
    )
  }
}