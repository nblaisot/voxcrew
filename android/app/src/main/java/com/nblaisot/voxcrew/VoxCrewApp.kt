package com.nblaisot.voxcrew

import android.app.Application
import com.nblaisot.voxcrew.di.AppContainer

class VoxCrewApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
