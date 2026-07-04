package com.nblaisot.voxcrew.connectivity.webrtc

import android.content.Context
import org.webrtc.PeerConnectionFactory

class PeerConnectionFactoryFacade(
    private val appContext: Context,
) {
    private var factory: PeerConnectionFactory? = null

    fun getOrCreate(): PeerConnectionFactory {
        if (factory != null) return factory!!
        val initOpts = PeerConnectionFactory.InitializationOptions.builder(appContext)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initOpts)
        factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
        return factory!!
    }

    fun dispose() {
        factory?.dispose()
        factory = null
        PeerConnectionFactory.stopInternalTracingCapture()
        PeerConnectionFactory.shutdownInternalTracer()
    }
}
