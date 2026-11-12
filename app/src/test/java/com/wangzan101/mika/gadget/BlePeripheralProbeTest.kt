package com.wangzan101.mika.gadget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlePeripheralProbeTest {
    @Test
    fun onlyAStartedAdvertisementSaysCan() {
        assertEquals(
            "can advertise (tested); gatt server=opened",
            BlePeripheralProbe.verdict(AdvertiseTrial.Started, GattTrial.OPENED),
        )
    }

    @Test
    fun onlyFeatureUnsupportedSaysCannot() {
        assertEquals(
            "cannot advertise (tested: FEATURE_UNSUPPORTED)",
            BlePeripheralProbe.verdict(AdvertiseTrial.Failed(BlePeripheralProbe.FAILED_FEATURE_UNSUPPORTED), GattTrial.NOT_RUN),
        )
    }

    @Test
    fun busyOrInternalFailuresAreInconclusiveNotCannot() {
        for (code in listOf(
            BlePeripheralProbe.FAILED_TOO_MANY_ADVERTISERS,
            BlePeripheralProbe.FAILED_INTERNAL_ERROR,
            BlePeripheralProbe.FAILED_DATA_TOO_LARGE,
            BlePeripheralProbe.FAILED_ALREADY_STARTED,
            42,
        )) {
            val v = BlePeripheralProbe.verdict(AdvertiseTrial.Failed(code), GattTrial.NOT_RUN)
            assertTrue(v, v.startsWith("inconclusive (tested: "))
            assertFalse(v, v.contains("cannot"))
        }
        assertEquals(
            "inconclusive (tested: TOO_MANY_ADVERTISERS)",
            BlePeripheralProbe.verdict(AdvertiseTrial.Failed(BlePeripheralProbe.FAILED_TOO_MANY_ADVERTISERS), GattTrial.NOT_RUN),
        )
    }

    @Test
    fun timeoutAndNotRunNeverClaimAVerdict() {
        assertTrue(BlePeripheralProbe.verdict(AdvertiseTrial.TimedOut, GattTrial.NOT_RUN).startsWith("inconclusive"))
        assertEquals(
            "not tested (bluetooth is off)",
            BlePeripheralProbe.verdict(AdvertiseTrial.NotRun("bluetooth is off"), GattTrial.NOT_RUN),
        )
    }

    @Test
    fun flagsAreReportedButNoLongerDecide() {
        // multiAdv=false era o que dava "cannot advertise" antes; agora é só informação.
        val cap = BlePeripheralCapability(
            bleSupported = true, advertiserPresent = true, multipleAdvertisementSupported = false,
            le2MPhySupported = true, extendedAdvertisingSupported = true,
        )
        assertEquals("ble=true advertiser=true multiAdv=false le2M=true extAdv=true", cap.summary())
    }
}
