package com.nblaisot.voxcrew.webrtc

data class IceServerConfig(
    val stunUrl: String,
    val turnUrl: String? = null,
    val turnUsername: String? = null,
    val turnCredential: String? = null,
) {
    fun toPeerIceServers(): List<org.webrtc.PeerConnection.IceServer> {
        val servers = mutableListOf(
            org.webrtc.PeerConnection.IceServer.builder(stunUrl).createIceServer(),
        )
        if (turnUrl != null) {
            val builder = org.webrtc.PeerConnection.IceServer.builder(turnUrl)
            turnUsername?.let { builder.setUsername(it) }
            turnCredential?.let { builder.setPassword(it) }
            servers.add(builder.createIceServer())
        }
        return servers
    }
}
