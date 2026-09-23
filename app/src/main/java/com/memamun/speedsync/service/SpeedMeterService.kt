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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

class SpeedMeterService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private var updateJob: Job? = null
    private val samplingMutex = Mutex()

    private sealed class LifecycleEvent {
        object ScreenOff : LifecycleEvent()
        object ScreenOn : LifecycleEvent()
        object Start : LifecycleEvent()
        object Stop : LifecycleEvent()
    }

    private val lifecycleChannel = Channel<LifecycleEvent>(Channel.UNLIMITED)

    private lateinit var dataRepo: DataUsageRepository
    private lateinit var networkHelper: NetworkHelper
    private lateinit var notificationManager: NotificationManager

    private var lastRxBytes: Long = 0L
    private var lastTxBytes: Long = 0L
    private var lastMobileRxBytes: Long = 0L
    private var lastMobileTxBytes: Long = 0L
    private var lastTimestamp: Long = 0L

    private var lastNotifiedTitle: String = ""
    private var lastNotifiedContent: String = ""
    private var lastNotifiedBigText: String = ""
    private var lastNotifiedIconIdentifier: String = ""

    private var isScreenOn: Boolean = true
    private lateinit var cachedPendingAppIntent: PendingIntent

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    lifecycleChannel.trySend(LifecycleEvent.ScreenOff)
                }
                Intent.ACTION_SCREEN_ON -> {
                    lifecycleChannel.trySend(LifecycleEvent.ScreenOn)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        dataRepo = DataUsageRepository.getInstance(this)
        networkHelper = NetworkHelper(this)
        networkHelper.registerNetworkCallback()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()

        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        isScreenOn = pm?.isInteractive ?: true

        val appIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        cachedPendingAppIntent = PendingIntent.getActivity(
            this,
            0,
            appIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Process all lifecycle and screen events strictly in FIFO order
        serviceScope.launch {
            for (event in lifecycleChannel) {
                when (event) {
                    is LifecycleEvent.ScreenOff -> handleScreenOff()
                    is LifecycleEvent.ScreenOn -> handleScreenOn()
                    is LifecycleEvent.Start -> handleStart()
                    is LifecycleEvent.Stop -> handleStop()
                }
            }
        }

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
            lifecycleChannel.trySend(LifecycleEvent.Stop)
            return START_NOT_STICKY
        }

        // Initialize foreground with initial notification
        val initialNotification = buildInitialNotification()
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

        lifecycleChannel.trySend(LifecycleEvent.Start)
        return START_STICKY
    }

    private suspend fun handleScreenOff() {
        samplingMutex.withLock {
            isScreenOn = false
            updateJob?.cancel()
            updateJob = null
            performSpeedUpdateLocked(forceNotify = false)
            dataRepo.flush()
        }
    }

    private suspend fun handleScreenOn() {
        samplingMutex.withLock {
            isScreenOn = true
            creditSleepTrafficAndResetBaselines()
            performSpeedUpdateLocked(forceNotify = true)
            startMonitoringLocked()
        }
    }

    private suspend fun handleStart() {
        samplingMutex.withLock {
            if (lastRxBytes == 0L && lastTxBytes == 0L) {
                lastRxBytes = TrafficStats.getTotalRxBytes()
                lastTxBytes = TrafficStats.getTotalTxBytes()
                lastMobileRxBytes = TrafficStats.getMobileRxBytes()
                lastMobileTxBytes = TrafficStats.getMobileTxBytes()
                lastTimestamp = System.currentTimeMillis()
            }
            // Immediately populate live readings so "Connecting..." is immediately replaced
            performSpeedUpdateLocked(forceNotify = true)
            startMonitoringLocked()
        }
    }

    private suspend fun handleStop() {
        samplingMutex.withLock {
            isScreenOn = false
            updateJob?.cancel()
            updateJob = null
            performSpeedUpdateLocked(forceNotify = false)
            dataRepo.flush()
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (_: Exception) {}
            stopSelf()
        }
    }

    private fun buildInitialNotification(): Notification {
        val title = "↓ 0.0 KB/s   ↑ 0.0 KB/s"
        val content = "Connecting...  •  Today: 0 B"
        val bigText = "Network: Connecting...\nDownload: 0.0 KB/s   Upload: 0.0 KB/s\nToday: 0 B"
        val statusSpeed = StatusBarSpeed("0", "KB/s")
        val statusIconCompat = getExactSpeedIcon(0L) ?: getDynamicSpeedIcon(statusSpeed.value, statusSpeed.unit)
        return buildNotification(title, content, bigText, "Connecting...", statusIconCompat)
    }

    private fun creditSleepTrafficAndResetBaselines() {
        val currentRx = TrafficStats.getTotalRxBytes()
        val currentTx = TrafficStats.getTotalTxBytes()
        val currentMobileRx = TrafficStats.getMobileRxBytes()
        val currentMobileTx = TrafficStats.getMobileTxBytes()
        val now = System.currentTimeMillis()

        if (currentRx >= 0L && currentTx >= 0L && lastRxBytes > 0L) {
            val rxDelta = if (currentRx >= lastRxBytes) currentRx - lastRxBytes else 0L
            val txDelta = if (currentTx >= lastTxBytes) currentTx - lastTxBytes else 0L
            val mobileRxDelta = if (lastMobileRxBytes > 0L && currentMobileRx >= lastMobileRxBytes) currentMobileRx - lastMobileRxBytes else 0L
            val mobileTxDelta = if (lastMobileTxBytes > 0L && currentMobileTx >= lastMobileTxBytes) currentMobileTx - lastMobileTxBytes else 0L

            val totalMobileDelta = mobileRxDelta + mobileTxDelta
            val totalDelta = rxDelta + txDelta

            if (totalDelta > 0L) {
                val connInfo = networkHelper.getConnectionInfo()
                val wifiDelta: Long
                val mobileDelta: Long
                if (connInfo.isWifi) {
                    wifiDelta = (totalDelta - totalMobileDelta).coerceAtLeast(0L)
                    mobileDelta = totalMobileDelta.coerceAtLeast(0L)
                } else if (connInfo.isMobile) {
                    wifiDelta = 0L
                    mobileDelta = totalMobileDelta.coerceAtLeast(0L)
                } else {
                    mobileDelta = totalMobileDelta.coerceAtLeast(0L)
                    wifiDelta = (totalDelta - totalMobileDelta).coerceAtLeast(0L)
                }

                attributeSleepTraffic(wifiDelta, mobileDelta, lastTimestamp, now)
            }
        }

        lastRxBytes = currentRx
        lastTxBytes = currentTx
        lastMobileRxBytes = currentMobileRx
        lastMobileTxBytes = currentMobileTx
        lastTimestamp = now
    }

    private fun attributeSleepTraffic(wifiDelta: Long, mobileDelta: Long, sleepStart: Long, wakeTime: Long) {
        if (sleepStart <= 0L || wakeTime <= sleepStart) {
            dataRepo.addUsage(wifiDelta, mobileDelta)
            return
        }

        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = wakeTime
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        val todayMidnight = cal.timeInMillis

        // If sleep started before today's midnight and woke up after midnight, split proportionally by sleep duration
        if (sleepStart < todayMidnight && wakeTime >= todayMidnight) {
            val totalSleepDuration = (wakeTime - sleepStart).toDouble()
            val preMidnightDuration = (todayMidnight - sleepStart).toDouble()
            val ratio = (preMidnightDuration / totalSleepDuration).coerceIn(0.0, 1.0)

            val preWifi = (wifiDelta * ratio).toLong()
            val preMobile = (mobileDelta * ratio).toLong()
            val postWifi = wifiDelta - preWifi
            val postMobile = mobileDelta - preMobile

            if (preWifi > 0L || preMobile > 0L) {
                dataRepo.addUsageAtTimestamp(preWifi, preMobile, sleepStart)
            }
            if (postWifi > 0L || postMobile > 0L) {
                dataRepo.addUsageAtTimestamp(postWifi, postMobile, wakeTime)
            }
        } else {
            dataRepo.addUsage(wifiDelta, mobileDelta)
        }
    }

    private fun startMonitoringLocked() {
        updateJob?.cancel()
        if (!isScreenOn) return

        updateJob = serviceScope.launch {
            while (isActive) {
                delay(1000)
                samplingMutex.withLock {
                    if (isScreenOn) {
                        performSpeedUpdateLocked()
                    }
                }
            }
        }
    }

    private fun performSpeedUpdateLocked(forceNotify: Boolean = false) {
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
            wifiDelta = (totalDelta - totalMobileDelta).coerceAtLeast(0L)
            mobileDelta = totalMobileDelta.coerceAtLeast(0L)
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
            todayTotalBytes = todayTotal,
            localIp = connInfo.localIp,
            linkSpeedMbps = connInfo.linkSpeedMbps,
            downstreamBandwidthKbps = connInfo.downstreamKbps,
            upstreamBandwidthKbps = connInfo.upstreamKbps
        )

        _liveSpeedData.value = speedData

        // Update notification when screen is on or explicitly requested
        if (isScreenOn || forceNotify) {
            val speedUnit = dataRepo.getSpeedUnit()
            val (downSpeedStr, downUnit) = DataUsageRepository.formatSpeed(rxSpeedBytes, speedUnit)
            val (upSpeedStr, upUnit) = DataUsageRepository.formatSpeed(txSpeedBytes, speedUnit)
            val title = "↓ $downSpeedStr $downUnit   ↑ $upSpeedStr $upUnit"

            val todayTotalStr = DataUsageRepository.formatBytes(todayTotal)
            val todayWifiStr = DataUsageRepository.formatBytes(todayWifi)
            val todayMobileStr = DataUsageRepository.formatBytes(todayMobile)
            val content = "${connInfo.networkName}  •  Today: $todayTotalStr"
            val bigText = "Network: ${connInfo.networkName}\nDownload: $downSpeedStr $downUnit   Upload: $upSpeedStr $upUnit\nToday: $todayTotalStr  (Wi-Fi: $todayWifiStr  •  Mobile: $todayMobileStr)"

            val totalActiveBytes = rxSpeedBytes + txSpeedBytes
            val statusSpeed = formatStatusBarSpeed(totalActiveBytes)
            val exactIcon = getExactSpeedIcon(totalActiveBytes)
            val iconIdentifier = if (exactIcon != null && lastExactResId != 0) {
                "exact_$lastExactResId"
            } else {
                "dyn_${statusSpeed.value}_${statusSpeed.unit}"
            }

            val contentChanged = title != lastNotifiedTitle ||
                content != lastNotifiedContent ||
                bigText != lastNotifiedBigText ||
                iconIdentifier != lastNotifiedIconIdentifier

            if (forceNotify || contentChanged) {
                val statusIconCompat = exactIcon ?: getDynamicSpeedIcon(statusSpeed.value, statusSpeed.unit)
                val notification = buildNotification(title, content, bigText, connInfo.networkName, statusIconCompat)

                try {
                    notificationManager.notify(NOTIFICATION_ID, notification)
                    lastNotifiedTitle = title
                    lastNotifiedContent = content
                    lastNotifiedBigText = bigText
                    lastNotifiedIconIdentifier = iconIdentifier
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
        title: String,
        content: String,
        bigText: String,
        networkName: String,
        statusIconCompat: IconCompat?
    ): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSubText(networkName)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setContentIntent(cachedPendingAppIntent)
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

    private var lastExactResId: Int = 0
    private var cachedExactIconCompat: IconCompat? = null

    /**
     * Resolves the exact pre-rendered drawable icon from Internet Speed Meter Lite
     * (wkb000-wkb999 for KB/s, wmb010-wmb291 for MB/s).
     */
    private fun getExactSpeedIcon(bytesPerSec: Long): IconCompat? {
        val bytes = bytesPerSec.coerceAtLeast(0L)
        val kb = (bytes / 1024.0).toLong()
        val mb = bytes / (1024.0 * 1024.0)

        return try {
            val resId = if (mb >= 1.0) {
                val tenths = (mb * 10.0).toInt()
                if (tenths in 10..291) {
                    val resName = String.format(Locale.US, "wmb%03d", tenths)
                    getDrawableResId(resName)
                } else 0
            } else {
                val kbClamped = kb.toInt().coerceIn(0, 999)
                val resName = String.format(Locale.US, "wkb%03d", kbClamped)
                getDrawableResId(resName)
            }

            if (resId != 0) {
                if (resId == lastExactResId && cachedExactIconCompat != null) {
                    cachedExactIconCompat
                } else {
                    val icon = IconCompat.createWithResource(this, resId)
                    cachedExactIconCompat = icon
                    lastExactResId = resId
                    icon
                }
            } else null
        } catch (_: Exception) {
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
        updateJob?.cancel()
        updateJob = null
        lifecycleChannel.close()
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {}
        try {
            networkHelper.unregisterNetworkCallback()
        } catch (_: Exception) {}
        try {
            dataRepo.flush()
        } catch (_: Exception) {}
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
