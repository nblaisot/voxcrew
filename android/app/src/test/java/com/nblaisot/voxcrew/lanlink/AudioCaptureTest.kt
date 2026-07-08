package com.nblaisot.voxcrew.lanlink

import com.nblaisot.voxcrew.audio.CaptureInputKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioCaptureTest {
    @Test
    fun bluetoothSilenceDetectorTriggersAfterConfiguredZeroFrames() {
        val detector = AudioCapture.BluetoothCaptureSilenceDetector(framesToObserve = 3)
        val zeroFrame = ByteArray(4)

        assertFalse(detector.observe(zeroFrame, CaptureInputKind.BLUETOOTH))
        assertFalse(detector.observe(zeroFrame, CaptureInputKind.BLUETOOTH))
        assertTrue(detector.observe(zeroFrame, CaptureInputKind.BLUETOOTH))
    }

    @Test
    fun bluetoothSilenceDetectorDoesNotTriggerAfterNonZeroFrame() {
        val detector = AudioCapture.BluetoothCaptureSilenceDetector(framesToObserve = 3)

        assertFalse(detector.observe(ByteArray(4), CaptureInputKind.BLUETOOTH))
        assertFalse(detector.observe(byteArrayOf(0, 0, 1, 0), CaptureInputKind.BLUETOOTH))
        assertFalse(detector.observe(ByteArray(4), CaptureInputKind.BLUETOOTH))
        assertFalse(detector.observe(ByteArray(4), CaptureInputKind.BLUETOOTH))
    }

    @Test
    fun bluetoothSilenceDetectorIgnoresNonBluetoothRoutes() {
        val detector = AudioCapture.BluetoothCaptureSilenceDetector(framesToObserve = 1)

        assertFalse(detector.observe(ByteArray(4), CaptureInputKind.BUILTIN))
        assertFalse(detector.observe(ByteArray(4), CaptureInputKind.USB))
    }
}
