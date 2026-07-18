package com.nblaisot.voxcrew.auth

import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.flow.StateFlow

data class AuthUser(
    val uid: String,
    val email: String? = null,
    val displayName: String? = null,
) {
    val label: String
        get() = displayName?.takeIf { it.isNotBlank() }
            ?: email?.takeIf { it.isNotBlank() }
            ?: uid
}

interface AuthRepository {
    val currentUser: StateFlow<AuthUser?>
    suspend fun signIn(email: String, password: String): Result<AuthUser>
    suspend fun signOut()
    suspend fun getIdToken(forceRefresh: Boolean = false): Result<String>
}

fun FirebaseUser.toAuthUser(): AuthUser = AuthUser(uid = uid, email = email, displayName = displayName)
