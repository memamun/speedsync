package com.memamun.speedsync.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import java.util.Locale

class NetworkHelper(private val context: Context) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    companion object {
        private var cachedWifiSsid: String? = null
        private var cachedCarrierName: String? = null
    }

    data class ConnectionInfo(
        val isConnected: Boolean,
        val isWifi: Boolean,
        val isMobile: Boolean,
        val networkName: String,
        val localIp: String = "Unavailable",
        val linkSpeedMbps: Int = 0,
        val downstreamKbps: Int = 0,
        val upstreamKbps: Int = 0
    )

    fun getLocalIpAddress(): String {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return "Unavailable"
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                val addrs = intf.inetAddresses ?: continue
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress ?: "Unavailable"
                    }
                }
            }
        } catch (_: Exception) {}
        return "Unavailable"
    }

    fun getConnectionInfo(): ConnectionInfo {
        val cm = connectivityManager ?: return ConnectionInfo(false, false, false, "No Network")
        val activeNetwork = cm.activeNetwork ?: return ConnectionInfo(false, false, false, "Offline")
        val capabilities = cm.getNetworkCapabilities(activeNetwork)
            ?: return ConnectionInfo(false, false, false, "Disconnected")

        val isWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val isMobile = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        val isEthernet = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)

        if (!isWifi) {
            cachedWifiSsid = null
        }

        val name = when {
            isWifi -> {
                var ssid: String? = null
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val wifiInfo = capabilities.transportInfo as? WifiInfo
                    val raw = wifiInfo?.ssid?.removeSurrounding("\"")
                    if (!raw.isNullOrEmpty() && raw != "<unknown ssid>" && raw != "0x") {
                        ssid = raw
                    }
                }
                if (ssid.isNullOrEmpty()) {
                    try {
                        @Suppress("DEPRECATION")
                        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                        @Suppress("DEPRECATION")
                        val raw = wifiManager?.connectionInfo?.ssid?.removeSurrounding("\"")
                        if (!raw.isNullOrEmpty() && raw != "<unknown ssid>" && raw != "0x") {
                            ssid = raw
                        }
                    } catch (_: Exception) {}
                }
                if (!ssid.isNullOrEmpty()) {
                    cachedWifiSsid = ssid
                    ssid
                } else if (!cachedWifiSsid.isNullOrEmpty()) {
                    cachedWifiSsid!!
                } else {
                    "Wi-Fi"
                }
            }
            isMobile -> getMobileCarrierName()
            isEthernet -> "Ethernet"
            else -> "Connected"
        }

        val linkSpeed = if (isWifi) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                (capabilities.transportInfo as? WifiInfo)?.linkSpeed ?: 0
            } else {
                try {
                    @Suppress("DEPRECATION")
                    val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    wifiManager?.connectionInfo?.linkSpeed ?: 0
                } catch (_: Exception) { 0 }
            }
        } else 0

        return ConnectionInfo(
            isConnected = true,
            isWifi = isWifi,
            isMobile = isMobile,
            networkName = name,
            localIp = getLocalIpAddress(),
            linkSpeedMbps = if (linkSpeed > 0) linkSpeed else 0,
            downstreamKbps = capabilities.linkDownstreamBandwidthKbps,
            upstreamKbps = capabilities.linkUpstreamBandwidthKbps
        )
    }

    private fun getMobileCarrierName(): String {
        try {
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            val subscriptionManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager

            val dataSubId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                SubscriptionManager.getDefaultDataSubscriptionId()
            } else {
                -1
            }

            // 1. Try SubscriptionManager for the active data SIM (prioritizing SIM card carrier / display name)
            if (subscriptionManager != null) {
                try {
                    val subInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && dataSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                        subscriptionManager.getActiveSubscriptionInfo(dataSubId)
                    } else {
                        subscriptionManager.activeSubscriptionInfoList?.firstOrNull()
                    }
                    val carrierName = subInfo?.carrierName?.toString()
                    if (isValidCarrierName(carrierName)) {
                        cachedCarrierName = carrierName!!.trim()
                        return cachedCarrierName!!
                    }
                    val displayName = subInfo?.displayName?.toString()
                    if (isValidCarrierName(displayName)) {
                        cachedCarrierName = displayName!!.trim()
                        return cachedCarrierName!!
                    }
                } catch (_: SecurityException) {
                    // Handled safely without requiring dangerous runtime permissions
                } catch (_: Exception) {}
            }

            // 2. Obtain TelephonyManager scoped specifically to the active data subscription (crucial for Dual SIM)
            val activeTm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                dataSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID &&
                telephonyManager != null) {
                try {
                    telephonyManager.createForSubscriptionId(dataSubId)
                } catch (_: Exception) {
                    telephonyManager
                }
            } else {
                telephonyManager
            }

            val managers = listOfNotNull(activeTm, telephonyManager).distinct()

            // 3. Inspect SIM-specific identity:
            //    simCarrierIdName (from Android CarrierConfig DB) & simOperatorName (SPN from SIM card).
            //    These correctly report the SIM brand for MVNOs and RAN sharing (e.g. Airtel using Robi towers, Mint on T-Mobile, etc.)
            for (tm in managers) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val carrierIdName = tm.simCarrierIdName?.toString()
                    if (isValidCarrierName(carrierIdName)) {
                        cachedCarrierName = carrierIdName!!.trim()
                        return cachedCarrierName!!
                    }
                }

                val simOpName = tm.simOperatorName
                if (isValidCarrierName(simOpName)) {
                    cachedCarrierName = simOpName!!.trim()
                    return cachedCarrierName!!
                }
            }

            // 4. Fallback to registered network operator name (cell tower operator)
            for (tm in managers) {
                val netOpName = tm.networkOperatorName
                if (isValidCarrierName(netOpName)) {
                    cachedCarrierName = netOpName!!.trim()
                    return cachedCarrierName!!
                }
            }

            // 5. Fallback to numeric PLMN mapping (if operator returns MCC+MNC code)
            for (tm in managers) {
                val plmn = tm.simOperator ?: tm.networkOperator
                val resolved = resolvePlmn(plmn)
                if (resolved != null) {
                    cachedCarrierName = resolved
                    return resolved
                }
            }
        } catch (_: Exception) {}

        return cachedCarrierName ?: "Mobile Data"
    }

    internal fun isValidCarrierName(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val trimmed = name.trim()
        if (trimmed.length < 2) return false
        if (trimmed.all { it.isDigit() }) return false

        val lower = trimmed.lowercase(Locale.ROOT)
        val genericPlaceholders = setOf(
            "unknown",
            "null",
            "android",
            "carrier",
            "no service",
            "emergency calls only",
            "sim",
            "sim 1",
            "sim 2",
            "searching",
            "searching...",
            "default",
            "none"
        )
        return !genericPlaceholders.contains(lower)
    }

    internal fun resolvePlmn(plmn: String?): String? {
        if (plmn.isNullOrBlank()) return null
        return when (plmn.trim()) {
            "47001" -> "Grameenphone"
            "47002" -> "Robi"
            "47003" -> "Banglalink"
            "47004" -> "Teletalk"
            "47007" -> "Airtel"
            "40445", "405854", "405855" -> "Airtel"
            "405840", "405857", "405861", "405872" -> "Jio"
            "40401", "40411", "40420" -> "Vodafone Idea"
            "310260", "310160", "310200" -> "T-Mobile"
            "310410", "310280", "310030" -> "AT&T"
            "311480", "310012" -> "Verizon"
            "23410" -> "O2"
            "23415" -> "Vodafone"
            "23420" -> "Three"
            "23430" -> "EE"
            else -> null
        }
    }
}
