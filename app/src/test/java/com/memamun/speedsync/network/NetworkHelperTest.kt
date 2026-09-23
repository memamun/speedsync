package com.memamun.speedsync.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun testIsValidCarrierName_validNames() {
        assertTrue(networkHelper.isValidCarrierName("Grameenphone"))
        assertTrue(networkHelper.isValidCarrierName("Robi"))
        assertTrue(networkHelper.isValidCarrierName("Airtel"))
        assertTrue(networkHelper.isValidCarrierName("Banglalink"))
        assertTrue(networkHelper.isValidCarrierName("Teletalk"))
        assertTrue(networkHelper.isValidCarrierName("T-Mobile"))
        assertTrue(networkHelper.isValidCarrierName("Verizon"))
        assertTrue(networkHelper.isValidCarrierName("AT&T"))
        assertTrue(networkHelper.isValidCarrierName("Mint Mobile"))
    }

    @Test
    fun testIsValidCarrierName_invalidPlaceholders() {
        assertFalse(networkHelper.isValidCarrierName(null))
        assertFalse(networkHelper.isValidCarrierName(""))
        assertFalse(networkHelper.isValidCarrierName("   "))
        assertFalse(networkHelper.isValidCarrierName("a"))
        assertFalse(networkHelper.isValidCarrierName("47001"))
        assertFalse(networkHelper.isValidCarrierName("47002"))
        assertFalse(networkHelper.isValidCarrierName("310260"))
        assertFalse(networkHelper.isValidCarrierName("unknown"))
        assertFalse(networkHelper.isValidCarrierName("UNKNOWN"))
        assertFalse(networkHelper.isValidCarrierName("null"))
        assertFalse(networkHelper.isValidCarrierName("android"))
        assertFalse(networkHelper.isValidCarrierName("carrier"))
        assertFalse(networkHelper.isValidCarrierName("no service"))
        assertFalse(networkHelper.isValidCarrierName("emergency calls only"))
        assertFalse(networkHelper.isValidCarrierName("sim"))
        assertFalse(networkHelper.isValidCarrierName("sim 1"))
        assertFalse(networkHelper.isValidCarrierName("sim 2"))
        assertFalse(networkHelper.isValidCarrierName("searching"))
    }

    @Test
    fun testResolvePlmn() {
        assertEquals("Grameenphone", networkHelper.resolvePlmn("47001"))
        assertEquals("Robi", networkHelper.resolvePlmn("47002"))
        assertEquals("Banglalink", networkHelper.resolvePlmn("47003"))
        assertEquals("Teletalk", networkHelper.resolvePlmn("47004"))
        assertEquals("Airtel", networkHelper.resolvePlmn("47007"))
        assertEquals("T-Mobile", networkHelper.resolvePlmn("310260"))
        assertEquals("AT&T", networkHelper.resolvePlmn("310410"))
        assertEquals("Verizon", networkHelper.resolvePlmn("311480"))
        assertNull(networkHelper.resolvePlmn("99999"))
        assertNull(networkHelper.resolvePlmn(null))
        assertNull(networkHelper.resolvePlmn(""))
    }
}
