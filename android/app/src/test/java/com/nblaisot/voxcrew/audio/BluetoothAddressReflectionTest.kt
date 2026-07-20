package com.nblaisot.voxcrew.audio

import android.os.ParcelUuid
import androidx.core.telecom.CallEndpointCompat
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * CI guard for the reflective access to Jetpack Telecom's internal `mMackAddress`.
 * A `core-telecom` upgrade that renames or removes the field must fail here instead
 * of silently degrading Bluetooth MAC deduplication in production.
 */
class BluetoothAddressReflectionTest {

    private fun endpoint(): CallEndpointCompat =
        CallEndpointCompat("Buds", CallEndpointCompat.TYPE_BLUETOOTH, mockk<ParcelUuid>())

    private fun setInternalMac(endpoint: CallEndpointCompat, mac: String) {
        val field = CallEndpointCompat::class.java.getDeclaredField("mMackAddress")
        field.isAccessible = true
        field.set(endpoint, mac)
    }

    @Test
    fun `internal mac field exists and is read normalized`() {
        val endpoint = endpoint()
        setInternalMac(endpoint, "aa:bb:cc:dd:ee:ff")

        assertEquals("AA:BB:CC:DD:EE:FF", readJetpackBluetoothAddress(endpoint))
    }

    @Test
    fun `unknown mac sentinel resolves to null`() {
        val endpoint = endpoint()
        setInternalMac(endpoint, "-1")

        assertNull(readJetpackBluetoothAddress(endpoint))
    }

    @Test
    fun `blank mac resolves to null`() {
        val endpoint = endpoint()
        setInternalMac(endpoint, "  ")

        assertNull(readJetpackBluetoothAddress(endpoint))
    }
}
