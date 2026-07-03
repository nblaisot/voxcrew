package com.nblaisot.voxcrew.ui.navigation

object Routes {
    const val LOGIN = "login"
    const val HOME = "home"
    const val SESSION = "session/{sessionId}?localHost={localHost}"
    fun session(sessionId: String, localHost: Boolean = false) =
        "session/$sessionId?localHost=$localHost"
}
