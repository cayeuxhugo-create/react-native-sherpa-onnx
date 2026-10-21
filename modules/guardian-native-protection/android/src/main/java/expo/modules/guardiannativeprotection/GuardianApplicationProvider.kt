package expo.modules.guardiannativeprotection

import android.content.Context

object GuardianApplicationProvider {

    lateinit var appContext: Context
        private set

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }
}