package com.memamun.speedsync.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import androidx.core.graphics.createBitmap
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.net.TrafficStats
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.DisplayMetrics
import android.util.TypedValue
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.graphics.drawable.IconCompat
import com.memamun.speedsync.MainActivity
import com.memamun.speedsync.R
import com.memamun.speedsync.data.DataUsageRepository
import com.memamun.speedsync.model.LiveSpeedData
import com.memamun.speedsync.network.NetworkHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

class SpeedMeterService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private var updateJob: Job? = null

    private lateinit var dataRepo: DataUsageRepository
    private lateinit var networkHelper: NetworkHelper
    private lateinit var notificationManager: NotificationManager

    private var lastRxBytes: Long = 0L
    private var lastTxBytes: Long = 0L
    private var lastMobileRxBytes: Long = 0L
    private var lastMobileTxBytes: Long = 0L
    private var lastTimestamp: Long = 0L

    private var lastNotifiedSpeedBytes: Long = -1L
    private var lastNotifiedTotalBytes: Long = -1L
    private var lastNotifiedNetworkName: String = ""
    private var lastNotificationTime: Long = 0L

    private var wakeLock: PowerManager.WakeLock? = null
    private var isScreenOn: Boolean = true

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenOn = false
                    try {
                        if (wakeLock?.isHeld == true) {
                            wakeLock?.release()
                        }
                    } catch (_: Exception) {}
                    dataRepo.flush()
                }
                Intent.ACTION_SCREEN_ON -> {
                    isScreenOn = true
                    serviceScope.launch {
                        performSpeedUpdate(forceNotify = true)
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        dataRepo = DataUsageRepository(this)
        networkHelper = NetworkHelper(this)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()

        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        isScreenOn = pm?.isInteractive ?: true
        try {
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SpeedSync::LiveMonitoring")?.apply {
                setReferenceCounted(false)
            }
        } catch (_: Exception) {}

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenReceiver, filter)

        _isServiceRunning.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            dataRepo.setServiceEnabled(false)
            dataRepo.flush()
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (_: Exception) {}
            stopSelf()
            return START_NOT_STICKY
        }

        // Initialize foreground with initial notification
        val initialNotification = buildNotification(0L, 0L, "Connecting...", "0 B", "0 B", "0 B")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    initialNotification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, initialNotification)
            }
        } catch (e: Exception) {
            try {
                startForeground(NOTIFICATION_ID, initialNotification)
            } catch (_: Exception) {}
        }

        startMonitoring()
        return START_STICKY
    }

    private fun startMonitoring() {
        updateJob?.cancel()

        // Prime the initial byte counts
        lastRxBytes = TrafficStats.getTotalRxBytes()
        lastTxBytes = TrafficStats.getTotalTxBytes()
        lastMobileRxBytes = TrafficStats.getMobileRxBytes()
        lastMobileTxBytes = TrafficStats.getMobileTxBytes()
        lastTimestamp = System.currentTimeMillis()

        updateJob = serviceScope.launch {
            while (isActive) {
                if (isScreenOn) {
                    delay(1000)
                } else {
                    // While screen is off, status bar is not visible.
                    // Throttle updates to 8 seconds without wake lock to preserve battery.
                    delay(8000)
                }
                performSpeedUpdate()
            }
        }
    }

    private fun performSpeedUpdate(forceNotify: Boolean = false) {
        val now = System.currentTimeMillis()
        val currentRx = TrafficStats.getTotalRxBytes()
        val currentTx = TrafficStats.getTotalTxBytes()
        val currentMobileRx = TrafficStats.getMobileRxBytes()
        val currentMobileTx = TrafficStats.getMobileTxBytes()

        // Guard against devices where TrafficStats is unsupported (-1)
        if (currentRx < 0L || currentTx < 0L) {
            return
        }

        val timeDeltaSec = ((now - lastTimestamp).coerceAtLeast(500)) / 1000.0

        // Calculate deltas safely (handle potential counter resets or negatives)
        val rxDelta = if (lastRxBytes > 0 && currentRx >= lastRxBytes) currentRx - lastRxBytes else 0L
        val txDelta = if (lastTxBytes > 0 && currentTx >= lastTxBytes) currentTx - lastTxBytes else 0L
        val mobileRxDelta = if (lastMobileRxBytes > 0 && currentMobileRx >= lastMobileRxBytes) currentMobileRx - lastMobileRxBytes else 0L
        val mobileTxDelta = if (lastMobileTxBytes > 0 && currentMobileTx >= lastMobileTxBytes) currentMobileTx - lastMobileTxBytes else 0L

        val totalMobileDelta = mobileRxDelta + mobileTxDelta
        val totalDelta = rxDelta + txDelta

        val connInfo = networkHelper.getConnectionInfo()

        // Attribute network traffic strictly according to the active transport interface
        val wifiDelta: Long
        val mobileDelta: Long
        if (connInfo.isWifi) {
            wifiDelta = totalDelta.coerceAtLeast(0L)
            mobileDelta = 0L
        } else if (connInfo.isMobile) {
            wifiDelta = 0L
            mobileDelta = totalMobileDelta.coerceAtLeast(0L)
        } else {
            mobileDelta = totalMobileDelta.coerceAtLeast(0L)
            wifiDelta = (totalDelta - totalMobileDelta).coerceAtLeast(0L)
        }

        // Update repository usage
        dataRepo.addUsage(wifiDelta, mobileDelta)

        val rxSpeedBytes = (rxDelta / timeDeltaSec).toLong()
        val txSpeedBytes = (txDelta / timeDeltaSec).toLong()
        val totalSpeedBytes = rxSpeedBytes + txSpeedBytes

        lastRxBytes = currentRx
        lastTxBytes = currentTx
        lastMobileRxBytes = currentMobileRx
        lastMobileTxBytes = currentMobileTx
        lastTimestamp = now

        val (todayWifi, todayMobile, todayTotal) = dataRepo.getTodayUsage()

        val speedData = LiveSpeedData(
            downloadSpeedBytes = rxSpeedBytes,
            uploadSpeedBytes = txSpeedBytes,
            totalSpeedBytes = totalSpeedBytes,
            isWifi = connInfo.isWifi,
            isMobile = connInfo.isMobile,
            networkName = connInfo.networkName,
            isConnected = connInfo.isConnected,
            todayWifiBytes = todayWifi,
            todayMobileBytes = todayMobile,
            todayTotalBytes = todayTotal
        )

        _liveSpeedData.value = speedData

        // Update notification when screen is on or explicitly requested
        if (isScreenOn || forceNotify) {
            val shouldNotify = forceNotify ||
                totalSpeedBytes != lastNotifiedSpeedBytes ||
                todayTotal != lastNotifiedTotalBytes ||
                connInfo.networkName != lastNotifiedNetworkName ||
                (now - lastNotificationTime) >= 5000L

            if (shouldNotify) {
                val notification = buildNotification(
                    rxSpeedBytes = rxSpeedBytes,
                    txSpeedBytes = txSpeedBytes,
                    networkName = connInfo.networkName,
                    todayTotal = DataUsageRepository.formatBytes(todayTotal),
                    todayWifi = DataUsageRepository.formatBytes(todayWifi),
                    todayMobile = DataUsageRepository.formatBytes(todayMobile)
                )

                try {
                    notificationManager.notify(NOTIFICATION_ID, notification)
                    lastNotifiedSpeedBytes = totalSpeedBytes
                    lastNotifiedTotalBytes = todayTotal
                    lastNotifiedNetworkName = connInfo.networkName
                    lastNotificationTime = now
                } catch (_: Exception) {}
            }
        }
    }

    data class StatusBarSpeed(
        val value: String,
        val unit: String
    )

    private fun formatStatusBarSpeed(bytesPerSec: Long): StatusBarSpeed {
        val bytes = bytesPerSec.coerceAtLeast(0L)
        val kb = bytes / 1024.0
        val mb = kb / 1024.0

        return when {
            mb >= 1.0 -> {
                val valueStr = if (mb >= 100.0) {
                    String.format(Locale.US, "%.0f", mb)
                } else {
                    String.format(Locale.US, "%.1f", mb)
                }
                StatusBarSpeed(valueStr, "MB/s")
            }
            kb >= 1.0 -> {
                val valueStr = String.format(Locale.US, "%.0f", kb)
                StatusBarSpeed(valueStr, "KB/s")
            }
            else -> {
                StatusBarSpeed("0", "KB/s")
            }
        }
    }

    private var lastSpeedForIcon: String? = null
    private var lastUnitForIcon: String? = null
    private var cachedIconCompat: IconCompat? = null

    private fun buildNotification(
        rxSpeedBytes: Long,
        txSpeedBytes: Long,
        networkName: String,
        todayTotal: String,
        todayWifi: String,
        todayMobile: String
    ): Notification {
        val speedUnit = dataRepo.getSpeedUnit()
        val (downSpeedStr, downUnit) = DataUsageRepository.formatSpeed(rxSpeedBytes, speedUnit)
        val (upSpeedStr, upUnit) = DataUsageRepository.formatSpeed(txSpeedBytes, speedUnit)

        // Calculate active speed (download + upload) for status bar icon
        val totalActiveBytes = rxSpeedBytes + txSpeedBytes
        val statusSpeed = formatStatusBarSpeed(totalActiveBytes)

        // Exact pre-rendered drawable icon matching Internet Speed Meter Lite (fallback to dynamic)
        val statusIconCompat = getExactSpeedIcon(totalActiveBytes) ?: getDynamicSpeedIcon(statusSpeed.value, statusSpeed.unit)

        val appIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingAppIntent = PendingIntent.getActivity(
            this,
            0,
            appIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = "↓ $downSpeedStr $downUnit   ↑ $upSpeedStr $upUnit"
        val content = "$networkName  •  Today: $todayTotal"
        val bigText = "Network: $networkName\nDownload: $downSpeedStr $downUnit   Upload: $upSpeedStr $upUnit\nToday: $todayTotal  (Wi-Fi: $todayWifi  •  Mobile: $todayMobile)"

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSubText(networkName)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setContentIntent(pendingAppIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setWhen(System.currentTimeMillis() * 3L) // Future timestamp puts our meter at the highest priority in status bar
            .setSortKey("!0000_speedsync") // Top alphabetical sort key keeps icon at first position
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW) // Silent priority, no heads-up popup
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setLocalOnly(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (statusIconCompat != null) {
            builder.setSmallIcon(statusIconCompat)
        } else {
            builder.setSmallIcon(R.drawable.ic_stat_speed)
        }

        return builder.build()
    }

    private fun sanitizeSpeed(speedStr: String): String {
        return when {
            speedStr == "0.0" || speedStr == "0" -> "0"
            speedStr.contains(".") -> {
                val parts = speedStr.split(".")
                if (parts.size >= 2 && (parts[1] == "0" || parts[0].length >= 2)) {
                    parts[0]
                } else {
                    speedStr
                }
            }
            else -> speedStr
        }
    }

    private fun cleanUnitForIcon(unitStr: String): String {
        return when {
            unitStr.equals("KB/s", ignoreCase = true) -> "KB/s"
            unitStr.equals("MB/s", ignoreCase = true) -> "MB/s"
            unitStr.equals("GB/s", ignoreCase = true) -> "GB/s"
            unitStr.equals("B/s", ignoreCase = true) -> "B/s"
            unitStr.equals("Kbps", ignoreCase = true) -> "Kb/s"
            unitStr.equals("Mbps", ignoreCase = true) -> "Mb/s"
            unitStr.contains("/s") -> unitStr
            else -> "$unitStr/s"
        }
    }

    private fun getDynamicSpeedIcon(speedStr: String, unitStr: String): IconCompat? {
        val cleanSpeed = sanitizeSpeed(speedStr)
        val displayUnit = cleanUnitForIcon(unitStr)
        if (cachedIconCompat != null && lastSpeedForIcon == cleanSpeed && lastUnitForIcon == displayUnit) {
            return cachedIconCompat
        }
        val icon = createDynamicSpeedIcon(cleanSpeed, displayUnit)
        if (icon != null) {
            cachedIconCompat = icon
            lastSpeedForIcon = cleanSpeed
            lastUnitForIcon = displayUnit
        }
        return icon ?: cachedIconCompat
    }

    private var customBoldTypeface: Typeface? = null
    private fun getCustomBoldTypeface(): Typeface {
        return customBoldTypeface ?: run {
            try {
                Typeface.createFromAsset(assets, "liberation_sans_bold.ttf").also { customBoldTypeface = it }
            } catch (_: Exception) {
                Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            }
        }
    }

    private val resIdCache = HashMap<String, Int>(1300)

    @SuppressLint("DiscouragedApi")
    private fun getDrawableResId(resName: String): Int {
        val cached = resIdCache[resName]
        if (cached != null) return cached
        val id = resources.getIdentifier(resName, "drawable", packageName)
        resIdCache[resName] = id
        return id
    }

    /**
     * Resolves the exact pre-rendered drawable icon from Internet Speed Meter Lite
     * (wkb000-wkb999 for KB/s, wmb010-wmb291 for MB/s).
     */
    private fun getExactSpeedIcon(bytesPerSec: Long): IconCompat? {
        val bytes = bytesPerSec.coerceAtLeast(0L)
        val kb = (bytes / 1024.0).toLong()
        val mb = bytes / (1024.0 * 1024.0)

        return try {
            if (mb >= 1.0) {
                val tenths = (mb * 10.0).toInt()
                if (tenths in 10..291) {
                    val resName = String.format(Locale.US, "wmb%03d", tenths)
                    val resId = getDrawableResId(resName)
                    if (resId != 0) {
                        return IconCompat.createWithResource(this, resId)
                    }
                }
            } else {
                val kbClamped = kb.toInt().coerceIn(0, 999)
                val resName = String.format(Locale.US, "wkb%03d", kbClamped)
                val resId = getDrawableResId(resName)
                if (resId != 0) {
                    return IconCompat.createWithResource(this, resId)
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Dynamically creates a monochrome status bar icon bitmap matching Internet Speed Meter Lite
     * (48x48 hdpi density, bold high-legibility font filling the status bar frame height).
     */
    private fun createDynamicSpeedIcon(cleanSpeed: String, displayUnit: String): IconCompat? {
        return try {
            val size = 48
            val bitmap = createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
                density = DisplayMetrics.DENSITY_HIGH // 240 dpi, matching drawable-hdpi
            }
            val canvas = Canvas(bitmap)

            val boldTypeface = getCustomBoldTypeface()

            // Digit Paint
            val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                isAntiAlias = true
                isSubpixelText = true
                isFakeBoldText = true
                typeface = boldTypeface
                textAlign = Paint.Align.CENTER
            }

            var numSize = 38f
            numPaint.textSize = numSize
            val numBounds = Rect()
            numPaint.getTextBounds(cleanSpeed, 0, cleanSpeed.length, numBounds)

            // Adjust size to fit target digit box (max height 28px, max width 46px)
            while ((numBounds.width() > 46 || numBounds.height() > 28) && numSize > 14f) {
                numSize -= 1f
                numPaint.textSize = numSize
                numPaint.getTextBounds(cleanSpeed, 0, cleanSpeed.length, numBounds)
            }

            // Unit Paint
            val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                isAntiAlias = true
                isSubpixelText = true
                isFakeBoldText = true
                typeface = boldTypeface
                textAlign = Paint.Align.CENTER
            }

            var unitSize = 20f
            unitPaint.textSize = unitSize
            val unitBounds = Rect()
            unitPaint.getTextBounds(displayUnit, 0, displayUnit.length, unitBounds)

            while ((unitBounds.width() > 46 || unitBounds.height() > 15) && unitSize > 9f) {
                unitSize -= 0.5f
                unitPaint.textSize = unitSize
                unitPaint.getTextBounds(displayUnit, 0, displayUnit.length, unitBounds)
            }

            // Digit top aligned at y = 1 (numBounds.top is negative relative to baseline)
            val textY = 1f - numBounds.top
            canvas.drawText(cleanSpeed, size / 2f, textY, numPaint)

            // Unit bottom aligned at y = 47 (unitBounds.bottom is relative to baseline)
            val unitY = 47f - unitBounds.bottom
            canvas.drawText(displayUnit, size / 2f, unitY, unitPaint)

            IconCompat.createWithBitmap(bitmap)
        } catch (e: Exception) {
            null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                notificationManager.deleteNotificationChannel("speed_meter_channel")
                notificationManager.deleteNotificationChannel("speed_meter_pinned_v3")
                notificationManager.deleteNotificationChannel("speed_meter_pinned_v4")
                notificationManager.deleteNotificationChannel("speed_meter_silent_v5")
            } catch (_: Exception) {}

            val channel = NotificationChannel(
                CHANNEL_ID,
                "SpeedSync Live Speed",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows live internet upload and download speed in status bar"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Ensure service stays running or restarts if the user closes/swipes the app from recents
        if (dataRepo.isServiceEnabled()) {
            try {
                val restartIntent = Intent(applicationContext, SpeedMeterService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    applicationContext.startForegroundService(restartIntent)
                } else {
                    applicationContext.startService(restartIntent)
                }
            } catch (_: Exception) {
                // Ignore ForegroundServiceStartNotAllowedException on API 31+ if background restricted.
                // The service is already running with stopWithTask="false".
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {}
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}
        try {
            dataRepo.flush()
        } catch (_: Exception) {}
        updateJob?.cancel()
        serviceScope.cancel()
        _isServiceRunning.value = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "speedsync_silent_v1"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.memamun.speedsync.STOP"

        private val _liveSpeedData = MutableStateFlow(LiveSpeedData())
        val liveSpeedData: StateFlow<LiveSpeedData> = _liveSpeedData.asStateFlow()

        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, SpeedMeterService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, SpeedMeterService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
