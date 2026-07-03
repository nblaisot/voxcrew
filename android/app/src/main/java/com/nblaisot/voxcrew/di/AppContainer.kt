package com.nblaisot.voxcrew.di

import android.content.Context
import com.nblaisot.voxcrew.BuildConfig
import com.nblaisot.voxcrew.auth.AuthRepository
import com.nblaisot.voxcrew.auth.FirebaseAuthRepository
import com.nblaisot.voxcrew.signaling.SignalingClient
import com.nblaisot.voxcrew.webrtc.IceServerConfig
import com.nblaisot.voxcrew.webrtc.WebRtcSessionManager

class AppContainer(context: Context) {
    val authRepository: AuthRepository = FirebaseAuthRepository()
    val signalingClient: SignalingClient = SignalingClient(
        baseUrl = BuildConfig.SIGNALING_BASE_URL,
        authRepository = authRepository,
    )
    val iceServerConfig = IceServerConfig(stunUrl = BuildConfig.STUN_SERVER_URL)
    val webRtcSessionManager = WebRtcSessionManager(context.applicationContext, iceServerConfig)
}
