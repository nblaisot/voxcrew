package com.nblaisot.voxcrew.auth

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class FirebaseAuthRepository(
    private val firebaseAuth: FirebaseAuth = FirebaseAuth.getInstance(),
) : AuthRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override val currentUser: StateFlow<AuthUser?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { auth ->
            trySend(auth.currentUser?.toAuthUser())
        }
        firebaseAuth.addAuthStateListener(listener)
        awaitClose { firebaseAuth.removeAuthStateListener(listener) }
    }.stateIn(scope, started = kotlinx.coroutines.flow.SharingStarted.Eagerly, initialValue = firebaseAuth.currentUser?.toAuthUser())

    override suspend fun signIn(email: String, password: String): Result<AuthUser> = runCatching {
        firebaseAuth.signInWithEmailAndPassword(email, password).await()
        firebaseAuth.currentUser?.toAuthUser() ?: error("Utilisateur introuvable après connexion")
    }

    override suspend fun signOut() {
        firebaseAuth.signOut()
    }

    override suspend fun getIdToken(forceRefresh: Boolean): Result<String> = runCatching {
        val user = firebaseAuth.currentUser ?: error("Non connecté")
        user.getIdToken(forceRefresh).await().token ?: error("Jeton indisponible")
    }
}
