package com.memamun.speedsync.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NetworkHelperTest {

    private lateinit var networkHelper: NetworkHelper

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        networkHelper = NetworkHelper(context)
    }

    @Test
    fun testIsValidCarrierName_validDynamicNames() {
        assertTrue(networkHelper.isValidCarrierName("cirkle"))
        assertTrue(networkHelper.isValidCarrierName("Cirkle"))
        assertTrue(networkHelper.isValidCarrierName("Grameenphone"))
        assertTrue(networkHelper.isValidCarrierName("Robi"))
        assertTrue(networkHelper.isValidCarrierName("Banglalink"))
        assertTrue(networkHelper.isValidCarrierName("Teletalk"))
        assertTrue(networkHelper.isValidCarrierName("T-Mobile"))
        assertTrue(networkHelper.isValidCarrierName("Verizon"))
        assertTrue(networkHelper.isValidCarrierName("AT&T"))
        assertTrue(networkHelper.isValidCarrierName("Mint Mobile"))
        assertTrue(networkHelper.isValidCarrierName("Google Fi"))
        assertTrue(networkHelper.isValidCarrierName("Jio"))
        assertTrue(networkHelper.isValidCarrierName("Vodafone"))
    }

    @Test
    fun testIsValidCarrierName_invalidPlaceholders() {
        assertFalse(networkHelper.isValidCarrierName(null))
        assertFalse(networkHelper.isValidCarrierName(""))
        assertFalse(networkHelper.isValidCarrierName("   "))
        assertFalse(networkHelper.isValidCarrierName("a"))
        assertFalse(networkHelper.isValidCarrierName("47001"))
        assertFalse(networkHelper.isValidCarrierName("47002"))
        assertFalse(networkHelper.isValidCarrierName("47007"))
        assertFalse(networkHelper.isValidCarrierName("310260"))
        assertFalse(networkHelper.isValidCarrierName("unknown"))
        assertFalse(networkHelper.isValidCarrierName("UNKNOWN"))
        assertFalse(networkHelper.isValidCarrierName("null"))
        assertFalse(networkHelper.isValidCarrierName("android"))
        assertFalse(networkHelper.isValidCarrierName("carrier"))
        assertFalse(networkHelper.isValidCarrierName("cellular"))
        assertFalse(networkHelper.isValidCarrierName("mobile data"))
        assertFalse(networkHelper.isValidCarrierName("no service"))
        assertFalse(networkHelper.isValidCarrierName("emergency calls only"))
        assertFalse(networkHelper.isValidCarrierName("sim"))
        assertFalse(networkHelper.isValidCarrierName("sim 1"))
        assertFalse(networkHelper.isValidCarrierName("sim 2"))
        assertFalse(networkHelper.isValidCarrierName("searching"))
    }
}
