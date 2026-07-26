package com.nblaisot.voxcrew.service

import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionForegroundServicePolicyTest {
    @Test
    fun `clean install uses connected device without microphone`() {
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            sessionForegroundServiceTypes(microphoneGranted = false),
        )
    }

    @Test
    fun `granted microphone adds microphone foreground type`() {
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            sessionForegroundServiceTypes(microphoneGranted = true),
        )
    }

    @Test
    fun `rejected promotion is contained and reported`() {
        var rejected = false

        val promoted = tryForegroundPromotion(
            types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            promote = { throw SecurityException("missing prerequisite") },
            onRejected = { rejected = true },
        )

        assertFalse(promoted)
        assertTrue(rejected)
    }
}
