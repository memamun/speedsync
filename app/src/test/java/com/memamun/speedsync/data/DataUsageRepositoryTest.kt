package com.memamun.speedsync.data

import com.memamun.speedsync.model.SpeedUnit
import org.junit.Assert.assertEquals
import org.junit.Test

class DataUsageRepositoryTest {

    @Test
    fun formatBytes_zeroAndNegative_returnsZeroBytes() {
        assertEquals("0 B", DataUsageRepository.formatBytes(0L))
        assertEquals("0 B", DataUsageRepository.formatBytes(-1L))
        assertEquals("0 B", DataUsageRepository.formatBytes(-1024L))
    }

    @Test
    fun formatBytes_underOneKilobyte_returnsBytes() {
        assertEquals("1 B", DataUsageRepository.formatBytes(1L))
        assertEquals("500 B", DataUsageRepository.formatBytes(500L))
        assertEquals("1023 B", DataUsageRepository.formatBytes(1023L))
    }

    @Test
    fun formatBytes_kilobytes_formatsCorrectly() {
        assertEquals("1.0 KB", DataUsageRepository.formatBytes(1024L))
        assertEquals("1.5 KB", DataUsageRepository.formatBytes(1536L))
        assertEquals("512.0 KB", DataUsageRepository.formatBytes(524288L))
    }

    @Test
    fun formatBytes_megabytes_formatsCorrectly() {
        assertEquals("1.0 MB", DataUsageRepository.formatBytes(1048576L))
        assertEquals("2.5 MB", DataUsageRepository.formatBytes((2.5 * 1024 * 1024).toLong()))
        assertEquals("99.9 MB", DataUsageRepository.formatBytes((99.9 * 1024 * 1024).toLong()))
    }

    @Test
    fun formatBytes_gigabytes_formatsWithTwoDecimals() {
        assertEquals("1.00 GB", DataUsageRepository.formatBytes(1073741824L))
        assertEquals("2.50 GB", DataUsageRepository.formatBytes((2.5 * 1024 * 1024 * 1024).toLong()))
        assertEquals("10.25 GB", DataUsageRepository.formatBytes((10.25 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun formatSpeed_auto_zeroOrNegative_returnsZeroKB() {
        val zeroResult = DataUsageRepository.formatSpeed(0L, SpeedUnit.AUTO)
        assertEquals("0", zeroResult.first)
        assertEquals("KB/s", zeroResult.second)

        val negativeResult = DataUsageRepository.formatSpeed(-500L, SpeedUnit.AUTO)
        assertEquals("0", negativeResult.first)
        assertEquals("KB/s", negativeResult.second)
    }

    @Test
    fun formatSpeed_auto_kilobytesRange() {
        val result1 = DataUsageRepository.formatSpeed(1024L, SpeedUnit.AUTO)
        assertEquals("1.0", result1.first)
        assertEquals("KB/s", result1.second)

        val result500 = DataUsageRepository.formatSpeed(512000L, SpeedUnit.AUTO)
        assertEquals("500.0", result500.first)
        assertEquals("KB/s", result500.second)
    }

    @Test
    fun formatSpeed_auto_megabytesRange() {
        val result1Mb = DataUsageRepository.formatSpeed(1048576L, SpeedUnit.AUTO)
        assertEquals("1.0", result1Mb.first)
        assertEquals("MB/s", result1Mb.second)

        val result10Mb = DataUsageRepository.formatSpeed(10485760L, SpeedUnit.AUTO)
        assertEquals("10.0", result10Mb.first)
        assertEquals("MB/s", result10Mb.second)
    }

    @Test
    fun formatSpeed_explicitKbps() {
        val result = DataUsageRepository.formatSpeed(2048L, SpeedUnit.KBPS)
        assertEquals("2.0", result.first)
        assertEquals("KB/s", result.second)
    }

    @Test
    fun formatSpeed_explicitMbps_calculatesMegabits() {
        // 128 KB/s = 1048576 bits/s = 1.0 Mbps
        val result = DataUsageRepository.formatSpeed(131072L, SpeedUnit.MBPS)
        assertEquals("1.0", result.first)
        assertEquals("Mbps", result.second)
    }
}
