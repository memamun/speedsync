package com.memamun.speedsync.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.memamun.speedsync.model.DayUsageItem
import com.memamun.speedsync.model.SpeedUnit
import com.memamun.speedsync.model.ThemeMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DataUsageRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("speed_meter_prefs", Context.MODE_PRIVATE)
    private val dateFormat = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }
    private val displayDateFormat = ThreadLocal.withInitial {
        SimpleDateFormat("dd MMM, yyyy", Locale.US)
    }

    private fun getDateFormat(): SimpleDateFormat = dateFormat.get() ?: SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private fun getDisplayDateFormat(): SimpleDateFormat = displayDateFormat.get() ?: SimpleDateFormat("dd MMM, yyyy", Locale.US)

    private val lock = Any()
    private var cachedToday: String = ""
    private var cachedWifi: Long = 0L
    private var cachedMobile: Long = 0L
    private var lastFlushTime: Long = 0L

    init {
        // Initialize cache on startup
        synchronized(lock) {
            val today = getDateFormat().format(Date())
            cachedToday = today
            cachedWifi = prefs.getLong("${KEY_WIFI_PREFIX}_$today", 0L)
            cachedMobile = prefs.getLong("${KEY_MOBILE_PREFIX}_$today", 0L)
            lastFlushTime = System.currentTimeMillis()
            pruneHistoryIfNeeded()
        }
    }

    private fun getTodayDate(): String = getDateFormat().format(Date())

    fun isServiceEnabled(): Boolean = prefs.getBoolean(KEY_SERVICE_ENABLED, true)

    fun setServiceEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_SERVICE_ENABLED, enabled) }
    }

    fun isStartOnBoot(): Boolean = prefs.getBoolean(KEY_START_ON_BOOT, true)

    fun setStartOnBoot(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_START_ON_BOOT, enabled) }
    }

    fun getSpeedUnit(): SpeedUnit {
        val name = prefs.getString(KEY_SPEED_UNIT, SpeedUnit.AUTO.name) ?: SpeedUnit.AUTO.name
        return try {
            SpeedUnit.valueOf(name)
        } catch (e: Exception) {
            SpeedUnit.AUTO
        }
    }

    fun setSpeedUnit(unit: SpeedUnit) {
        prefs.edit { putString(KEY_SPEED_UNIT, unit.name) }
    }

    fun getThemeMode(): ThemeMode {
        val name = prefs.getString(KEY_THEME_MODE, ThemeMode.DARK.name) ?: ThemeMode.DARK.name
        return try {
            ThemeMode.valueOf(name)
        } catch (_: Exception) {
            ThemeMode.DARK
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit { putString(KEY_THEME_MODE, mode.name) }
    }

    /**
     * Accumulates Wi-Fi and Mobile usage in memory.
     * Flushes to SharedPreferences every 15 seconds or on day rollover to avoid disk I/O thrashing.
     */
    fun addUsage(wifiDelta: Long, mobileDelta: Long) {
        if (wifiDelta <= 0L && mobileDelta <= 0L) return

        val now = System.currentTimeMillis()
        val today = getTodayDate()
        var shouldFlush = false

        synchronized(lock) {
            if (cachedToday != today) {
                // Day rollover! Flush previous day data first
                flushInternal(now)
                cachedToday = today
                cachedWifi = prefs.getLong("${KEY_WIFI_PREFIX}_$today", 0L)
                cachedMobile = prefs.getLong("${KEY_MOBILE_PREFIX}_$today", 0L)
            }
            cachedWifi += wifiDelta
            cachedMobile += mobileDelta

            if (now - lastFlushTime >= FLUSH_INTERVAL_MS) {
                shouldFlush = true
            }
        }

        if (shouldFlush) {
            flush()
        }
    }

    /**
     * Persists cached in-memory usage metrics to SharedPreferences asynchronously.
     */
    fun flush() {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            flushInternal(now)
        }
    }

    private fun pruneHistoryIfNeeded() {
        val pastDays = getHistoryDates()
        if (pastDays.size > MAX_HISTORY_DAYS) {
            val sorted = pastDays.toList().sortedDescending()
            val toKeep = sorted.take(MAX_HISTORY_DAYS).toSet()
            val toRemove = sorted.drop(MAX_HISTORY_DAYS)
            val editor = prefs.edit()
            for (oldDate in toRemove) {
                editor.remove("${KEY_WIFI_PREFIX}_$oldDate")
                editor.remove("${KEY_MOBILE_PREFIX}_$oldDate")
            }
            editor.putStringSet(KEY_HISTORY_DATES, toKeep)
            editor.apply()
        }
    }

    private fun flushInternal(now: Long) {
        if (cachedToday.isEmpty()) return
        val today = cachedToday
        val currentDay = prefs.getString(KEY_CURRENT_DATE, "")

        val editor = prefs.edit()
        if (currentDay != today) {
            if (!currentDay.isNullOrEmpty()) {
                val pastDays = getHistoryDates().toMutableSet()
                pastDays.add(currentDay)
                if (pastDays.size > MAX_HISTORY_DAYS) {
                    val sorted = pastDays.toList().sortedDescending()
                    val toKeep = sorted.take(MAX_HISTORY_DAYS).toSet()
                    val toRemove = sorted.drop(MAX_HISTORY_DAYS)
                    for (oldDate in toRemove) {
                        editor.remove("${KEY_WIFI_PREFIX}_$oldDate")
                        editor.remove("${KEY_MOBILE_PREFIX}_$oldDate")
                    }
                    editor.putStringSet(KEY_HISTORY_DATES, toKeep)
                } else {
                    editor.putStringSet(KEY_HISTORY_DATES, pastDays)
                }
            }
            editor.putString(KEY_CURRENT_DATE, today)
        }

        editor.putLong("${KEY_WIFI_PREFIX}_$today", cachedWifi)
        editor.putLong("${KEY_MOBILE_PREFIX}_$today", cachedMobile)
        editor.apply()
        lastFlushTime = now
    }

    /**
     * Retrieves today's Wi-Fi, Mobile, and Total usage instantly from the in-memory cache.
     */
    fun getTodayUsage(): Triple<Long, Long, Long> {
        val today = getTodayDate()
        synchronized(lock) {
            if (cachedToday == today) {
                return Triple(cachedWifi, cachedMobile, cachedWifi + cachedMobile)
            } else {
                val wifi = prefs.getLong("${KEY_WIFI_PREFIX}_$today", 0L)
                val mobile = prefs.getLong("${KEY_MOBILE_PREFIX}_$today", 0L)
                cachedToday = today
                cachedWifi = wifi
                cachedMobile = mobile
                return Triple(wifi, mobile, wifi + mobile)
            }
        }
    }

    private fun getHistoryDates(): Set<String> {
        return prefs.getStringSet(KEY_HISTORY_DATES, emptySet()) ?: emptySet()
    }

    fun getUsageHistory(): List<DayUsageItem> {
        flush() // Ensure latest in-memory numbers are written before reading
        val today = getTodayDate()
        val dates = (getHistoryDates() + today).toList().sortedDescending()
        val fmt = getDateFormat()
        val dispFmt = getDisplayDateFormat()
        return dates.take(30).map { dateStr ->
            val wifi = prefs.getLong("${KEY_WIFI_PREFIX}_$dateStr", 0L)
            val mobile = prefs.getLong("${KEY_MOBILE_PREFIX}_$dateStr", 0L)
            val formattedDate = try {
                val d = fmt.parse(dateStr)
                if (dateStr == today) "Today" else if (d != null) dispFmt.format(d) else dateStr
            } catch (e: Exception) {
                dateStr
            }
            DayUsageItem(
                date = formattedDate,
                mobileBytes = mobile,
                wifiBytes = wifi,
                totalBytes = wifi + mobile
            )
        }
    }

    /**
     * Clears all recorded historical usage data and resets today's tracking.
     */
    fun clearHistory() {
        synchronized(lock) {
            val pastDays = getHistoryDates()
            val today = getTodayDate()
            val editor = prefs.edit()
            for (oldDate in pastDays) {
                editor.remove("${KEY_WIFI_PREFIX}_$oldDate")
                editor.remove("${KEY_MOBILE_PREFIX}_$oldDate")
            }
            editor.remove("${KEY_WIFI_PREFIX}_$today")
            editor.remove("${KEY_MOBILE_PREFIX}_$today")
            editor.remove(KEY_HISTORY_DATES)
            editor.remove(KEY_CURRENT_DATE)
            editor.apply()

            cachedToday = today
            cachedWifi = 0L
            cachedMobile = 0L
            lastFlushTime = System.currentTimeMillis()
        }
    }

    companion object {
        @Volatile
        private var instance: DataUsageRepository? = null

        fun getInstance(context: Context): DataUsageRepository {
            return instance ?: synchronized(this) {
                instance ?: DataUsageRepository(context.applicationContext).also { instance = it }
            }
        }

        private const val KEY_SERVICE_ENABLED = "key_service_enabled"
        private const val KEY_START_ON_BOOT = "key_start_on_boot"
        private const val KEY_SPEED_UNIT = "key_speed_unit"
        private const val KEY_THEME_MODE = "key_theme_mode"
        private const val KEY_CURRENT_DATE = "key_current_date"
        private const val KEY_WIFI_PREFIX = "usage_wifi"
        private const val KEY_MOBILE_PREFIX = "usage_mobile"
        private const val KEY_HISTORY_DATES = "history_dates"
        private const val FLUSH_INTERVAL_MS = 15_000L
        private const val MAX_HISTORY_DAYS = 60

        fun formatBytes(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            val kb = bytes / 1024.0
            val mb = kb / 1024.0
            val gb = mb / 1024.0
            return when {
                gb >= 1.0 -> String.format(Locale.US, "%.2f GB", gb)
                mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
                kb >= 1.0 -> String.format(Locale.US, "%.1f KB", kb)
                else -> "$bytes B"
            }
        }

        fun formatSpeed(bytesPerSec: Long, unit: SpeedUnit = SpeedUnit.AUTO): Pair<String, String> {
            val bytes = if (bytesPerSec < 0) 0L else bytesPerSec
            return when (unit) {
                SpeedUnit.MBPS -> {
                    val mbps = (bytes * 8.0) / (1024.0 * 1024.0)
                    Pair(String.format(Locale.US, "%.1f", mbps), "Mbps")
                }
                SpeedUnit.KBPS -> {
                    val kb = bytes / 1024.0
                    Pair(String.format(Locale.US, "%.1f", kb), "KB/s")
                }
                SpeedUnit.AUTO -> {
                    val kb = bytes / 1024.0
                    val mb = kb / 1024.0
                    when {
                        mb >= 1.0 -> Pair(String.format(Locale.US, "%.1f", mb), "MB/s")
                        kb >= 1.0 -> Pair(String.format(Locale.US, "%.1f", kb), "KB/s")
                        else -> Pair("0", "KB/s")
                    }
                }
            }
        }
    }
}
