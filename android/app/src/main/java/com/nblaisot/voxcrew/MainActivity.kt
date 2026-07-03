package com.nblaisot.voxcrew

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.nblaisot.voxcrew.ui.VoxCrewNavHost
import com.nblaisot.voxcrew.ui.theme.VoxCrewTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as VoxCrewApp).container
        setContent {
            VoxCrewTheme {
                VoxCrewNavHost(container = container)
            }
        }
    }
}
