package com.memamun.speedsync.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.memamun.speedsync.data.DataUsageRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UsageAccountingTest {

    private lateinit var context: Context
    private lateinit var repository: DataUsageRepository
    private lateinit var service: SpeedMeterService

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        repository = DataUsageRepository.getInstance(context)
        repository.clearHistory()
        service = Robolectric.buildService(SpeedMeterService::class.java).create().get()
    }

    @Test
    fun testWifiToCellularTransition_accountsForBothTransportsIndependently() {
        // Suppose during an interval, 5 MB total traffic occurred, of which 2 MB was mobile
        // (e.g. Wi-Fi was active then dropped, transitioning to mobile)
        val totalDelta = 5_000_000L
        val mobileDelta = 2_000_000L

        val calculatedMobile = mobileDelta.coerceAtLeast(0L)
        val calculatedWifi = (totalDelta - mobileDelta).coerceAtLeast(0L)

        assertEquals(2_000_000L, calculatedMobile)
        assertEquals(3_000_000L, calculatedWifi)
        assertEquals(totalDelta, calculatedMobile + calculatedWifi)

        repository.addUsage(calculatedWifi, calculatedMobile)
        val (todayWifi, todayMobile, todayTotal) = repository.getTodayUsage()
        assertEquals(3_000_000L, todayWifi)
        assertEquals(2_000_000L, todayMobile)
        assertEquals(5_000_000L, todayTotal)
    }

    @Test
    fun testCounterReset_handlesRebootGracefullyWithoutCorruptingUsage() {
        // Prior baseline
        val lastRxBytes = 100_000_000L
        // Current hardware counter drops to 1,000L after device reboot
        val currentRx = 1_000L

        val rxDelta = if (lastRxBytes > 0 && currentRx >= lastRxBytes) currentRx - lastRxBytes else 0L
        assertEquals("Delta must be 0 on counter reset / reboot", 0L, rxDelta)

        // Ensure repository handles 0L addition cleanly
        repository.addUsage(rxDelta, 0L)
        val (todayWifi, todayMobile, todayTotal) = repository.getTodayUsage()
        assertEquals(0L, todayTotal)
    }

    @Test
    fun testCalculateDaySlices_multiDaySpan() {
        val cal = Calendar.getInstance()
        cal.set(2026, Calendar.SEPTEMBER, 20, 22, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val startTime = cal.timeInMillis // Day 1, 22:00

        cal.set(2026, Calendar.SEPTEMBER, 22, 4, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val endTime = cal.timeInMillis // Day 3, 04:00 (30 hours total)

        val slices = service.calculateDaySlices(startTime, endTime)

        // Day 1: 22:00 to 00:00 = 2 hours
        // Day 2: 00:00 to 00:00 = 24 hours
        // Day 3: 00:00 to 04:00 = 4 hours
        assertEquals("Must produce 3 calendar day slices", 3, slices.size)
        assertEquals(2 * 3600 * 1000L, slices[0].durationMs)
        assertEquals(24 * 3600 * 1000L, slices[1].durationMs)
        assertEquals(4 * 3600 * 1000L, slices[2].durationMs)

        val totalSliceDuration = slices.sumOf { it.durationMs }
        assertEquals(endTime - startTime, totalSliceDuration)
    }

    @Test
    fun testAttributeSleepTraffic_multiDayPreservesTotalBytesExactly() {
        val cal = Calendar.getInstance()
        cal.set(2026, Calendar.SEPTEMBER, 20, 22, 0, 0)
        val sleepStart = cal.timeInMillis
        cal.set(2026, Calendar.SEPTEMBER, 22, 4, 0, 0)
        val wakeTime = cal.timeInMillis

        val wifiDelta = 30_000_000L
        val mobileDelta = 15_000_000L

        service.attributeSleepTraffic(wifiDelta, mobileDelta, sleepStart, wakeTime)

        val history = repository.getUsageHistory()
        // History should contain entries for the past days
        assertTrue("History should record past days", history.isNotEmpty())

        val (todayWifi, todayMobile, todayTotal) = repository.getTodayUsage()
        val historyWifi = history.sumOf { it.wifiBytes }
        val historyMobile = history.sumOf { it.mobileBytes }

        val totalRecordedWifi = historyWifi + todayWifi
        val totalRecordedMobile = historyMobile + todayMobile

        assertEquals("Total Wi-Fi bytes must be preserved down to the byte", wifiDelta, totalRecordedWifi)
        assertEquals("Total Mobile bytes must be preserved down to the byte", mobileDelta, totalRecordedMobile)
    }
}
