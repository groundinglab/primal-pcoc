package net.primal.android.pcoc.admit

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SignInWelcomeStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    fun skipWelcome(): Boolean = prefs.getBoolean(KEY_SKIP_WELCOME, false)

    fun setSkipWelcome(skip: Boolean) {
        prefs.edit(commit = true) {
            putBoolean(KEY_SKIP_WELCOME, skip)
        }
    }

    private companion object {
        const val PREFS_NAME = "pcoc_sign_in_welcome"
        const val KEY_SKIP_WELCOME = "skip_welcome"
    }
}
