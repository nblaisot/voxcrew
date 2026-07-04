package com.nblaisot.voxcrew.ui.navigation

object Routes {
    const val LOGIN = "login"
    const val MAIN = "main"
    const val DEBUG = "debug"
    const val SESSION = "session/{sessionId}?localHost={localHost}"

    @Deprecated("Use MAIN")
    const val HOME = "home"

    fun session(sessionId: String, localHost: Boolean = false) =
        "session/$sessionId?localHost=$localHost"
}
