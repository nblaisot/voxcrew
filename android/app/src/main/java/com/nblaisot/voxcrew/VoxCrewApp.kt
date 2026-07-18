package com.nblaisot.voxcrew

import android.app.Application
import com.google.firebase.FirebaseApp
import com.nblaisot.voxcrew.di.AppContainer

class VoxCrewApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        if (!BuildConfig.NO_BACKEND && FirebaseApp.getApps(this).isEmpty()) {
            FirebaseApp.initializeApp(this)
        }
        container = AppContainer(this)
    }
}
