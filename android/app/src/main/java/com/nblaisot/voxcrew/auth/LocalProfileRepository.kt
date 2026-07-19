package com.nblaisot.voxcrew.auth

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Device-local identity: stable random UID and user-chosen display name.
 */
class LocalProfileRepository(context: Context) : AuthRepository {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _currentUser = MutableStateFlow(loadUser())

    override val currentUser: StateFlow<AuthUser?> = _currentUser.asStateFlow()

    fun isConfigured(): Boolean = _currentUser.value != null

    suspend fun saveProfile(displayName: String): Result<AuthUser> {
        val trimmed = displayName.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("Le nom est requis"))
        }
        val uid = prefs.getString(KEY_UID, null) ?: UUID.randomUUID().toString()
        val user = AuthUser(uid = uid, email = null, displayName = trimmed)
        prefs.edit()
            .putString(KEY_UID, uid)
            .putString(KEY_DISPLAY_NAME, trimmed)
            .apply()
        _currentUser.value = user
        return Result.success(user)
    }

    override suspend fun signIn(email: String, password: String): Result<AuthUser> =
        Result.failure(UnsupportedOperationException("Local profile only"))

    override suspend fun signOut() {
        prefs.edit()
            .remove(KEY_DISPLAY_NAME)
            .apply()
        _currentUser.value = null
    }

    suspend fun resetIdentity() {
        prefs.edit().clear().apply()
        _currentUser.value = null
    }

    private fun loadUser(): AuthUser? {
        val uid = prefs.getString(KEY_UID, null) ?: return null
        val displayName = prefs.getString(KEY_DISPLAY_NAME, null)?.takeIf { it.isNotBlank() } ?: return null
        return AuthUser(uid = uid, email = null, displayName = displayName)
    }

    companion object {
        private const val PREFS = "voxcrew_local_profile"
        private const val KEY_UID = "uid"
        private const val KEY_DISPLAY_NAME = "display_name"
    }
}
