package com.memamun.speedsync.model

data class LiveSpeedData(
    val downloadSpeedBytes: Long = 0L,
    val uploadSpeedBytes: Long = 0L,
    val totalSpeedBytes: Long = 0L,
    val isWifi: Boolean = false,
    val isMobile: Boolean = false,
    val networkName: String = "Detecting...",
    val isConnected: Boolean = true,
    val todayWifiBytes: Long = 0L,
    val todayMobileBytes: Long = 0L,
    val todayTotalBytes: Long = 0L,
    val localIp: String = "Unavailable",
    val linkSpeedMbps: Int = 0,
    val downstreamBandwidthKbps: Int = 0,
    val upstreamBandwidthKbps: Int = 0
)

data class DayUsageItem(
    val date: String,
    val mobileBytes: Long,
    val wifiBytes: Long,
    val totalBytes: Long
)

enum class SpeedUnit(val label: String) {
    AUTO("Auto (KB/s - MB/s)"),
    MBPS("Mbps (Megabits)"),
    KBPS("KB/s (Kilobytes)")
}

enum class ThemeMode(val label: String) {
    SYSTEM("System Default"),
    DARK("Dark / Night Mode"),
    LIGHT("Light Mode")
}

enum class SpeedTestPhase {
    IDLE,
    PING,
    DOWNLOAD,
    UPLOAD,
    COMPLETED,
    ERROR
}

data class SpeedTestResult(
    val phase: SpeedTestPhase = SpeedTestPhase.IDLE,
    val progress: Float = 0f,
    val currentSpeedMbps: Double = 0.0,
    val downloadSpeedMbps: Double = 0.0,
    val uploadSpeedMbps: Double = 0.0,
    val pingMs: Long = 0L,
    val jitterMs: Long = 0L,
    val packetLossPercent: Double = 0.0,
    val testFinished: Boolean = false,
    val errorMessage: String? = null
)
