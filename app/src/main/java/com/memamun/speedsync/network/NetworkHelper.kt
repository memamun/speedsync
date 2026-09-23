package com.memamun.speedsync.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.CarrierConfigManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import java.util.Locale

class NetworkHelper(private val context: Context) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    companion object {
        private var cachedWifiSsid: String? = null
    }

    private var cachedIpAddress: String = "Unavailable"
    private var lastIpCheckTime: Long = 0L
    private var cachedCarrierName: String? = null
    private var lastCarrierCheckTime: Long = 0L
    private var lastActiveNetworkHash: Int = 0

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

    fun getLocalIpAddress(forceRefresh: Boolean = false): String {
        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedIpAddress != "Unavailable" && (now - lastIpCheckTime) < 30000L) {
            return cachedIpAddress
        }
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return cachedIpAddress
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                val addrs = intf.inetAddresses ?: continue
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        val host = addr.hostAddress ?: continue
                        cachedIpAddress = host
                        lastIpCheckTime = now
                        return host
                    }
                }
            }
        } catch (_: Exception) {}
        return cachedIpAddress
    }

    fun getConnectionInfo(): ConnectionInfo {
        val cm = connectivityManager ?: return ConnectionInfo(false, false, false, "No Network")
        val activeNetwork = cm.activeNetwork ?: return ConnectionInfo(false, false, false, "Offline")
        val capabilities = cm.getNetworkCapabilities(activeNetwork)
            ?: return ConnectionInfo(false, false, false, "Disconnected")

        val currentNetworkHash = activeNetwork.hashCode()
        val networkChanged = currentNetworkHash != lastActiveNetworkHash
        if (networkChanged) {
            lastActiveNetworkHash = currentNetworkHash
            cachedCarrierName = null
            lastCarrierCheckTime = 0L
            lastIpCheckTime = 0L
        }

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
            isMobile -> {
                val now = System.currentTimeMillis()
                if (cachedCarrierName != null && !networkChanged && (now - lastCarrierCheckTime) < 15000L) {
                    cachedCarrierName!!
                } else {
                    val resolved = getMobileCarrierName()
                    cachedCarrierName = resolved
                    lastCarrierCheckTime = now
                    resolved
                }
            }
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
            localIp = getLocalIpAddress(forceRefresh = networkChanged),
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

            // 1. Dynamic check via SubscriptionManager for the active data SIM
            //    (queries the live user/SIM display name and carrier name from OS settings)
            if (subscriptionManager != null) {
                try {
                    val subInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && dataSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                        subscriptionManager.getActiveSubscriptionInfo(dataSubId)
                    } else {
                        subscriptionManager.activeSubscriptionInfoList?.firstOrNull()
                    }
                    val displayName = subInfo?.displayName?.toString()
                    if (isValidCarrierName(displayName)) {
                        return displayName!!.trim()
                    }
                    val carrierName = subInfo?.carrierName?.toString()
                    if (isValidCarrierName(carrierName)) {
                        return carrierName!!.trim()
                    }
                } catch (_: SecurityException) {
                    // Safe fallback if runtime phone permission is not granted
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

            // 3. Dynamic Service Provider Name (SPN) directly from the SIM card (EF_SPN).
            //    This is broadcast/written dynamically on the SIM card by the operator (e.g. cirkle).
            for (tm in managers) {
                val simOpName = tm.simOperatorName
                if (isValidCarrierName(simOpName)) {
                    return simOpName!!.trim()
                }
            }

            // 4. Dynamic Registered Network Operator Name from the connected cell tower (EONS / NITZ).
            //    This is received live over the air from the active cellular tower.
            for (tm in managers) {
                val netOpName = tm.networkOperatorName
                if (isValidCarrierName(netOpName)) {
                    return netOpName!!.trim()
                }
            }

            // 5. Dynamic CarrierConfigManager lookup for the active subscription
            try {
                val carrierConfig = context.getSystemService(Context.CARRIER_CONFIG_SERVICE) as? CarrierConfigManager
                val config = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && dataSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    carrierConfig?.getConfigForSubId(dataSubId)
                } else {
                    carrierConfig?.config
                }
                val configCarrierName = config?.getString(CarrierConfigManager.KEY_CARRIER_NAME_STRING)
                if (isValidCarrierName(configCarrierName)) {
                    return configCarrierName!!.trim()
                }
            } catch (_: Exception) {}

            // 6. Dynamic ServiceState operator alpha name (if accessible over radio layer)
            for (tm in managers) {
                try {
                    val serviceState = tm.serviceState
                    val opName = serviceState?.operatorAlphaLong ?: serviceState?.operatorAlphaShort
                    if (isValidCarrierName(opName)) {
                        return opName!!.trim()
                    }
                } catch (_: SecurityException) {
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

        // Fallback to clean, non-misleading generic cellular without any static hardcoded assumptions
        return "Cellular"
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
            "cellular",
            "mobile data",
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
}
