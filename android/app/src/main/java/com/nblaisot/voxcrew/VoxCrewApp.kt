package com.nblaisot.voxcrew

import android.app.Application
import com.nblaisot.voxcrew.di.AppContainer
import com.nblaisot.voxcrew.diagnostics.AudioDiagnostics

class VoxCrewApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        AudioDiagnostics.initialize(this)
        container = AppContainer(this)
    }
}
