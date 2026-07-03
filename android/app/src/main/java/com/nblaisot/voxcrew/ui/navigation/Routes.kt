package com.nblaisot.voxcrew.ui.navigation

object Routes {
    const val LOGIN = "login"
    const val HOME = "home"
    const val SESSION = "session/{sessionId}"
    fun session(sessionId: String) = "session/$sessionId"
}
