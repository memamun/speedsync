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

    private var cachedConnectionInfo: ConnectionInfo = ConnectionInfo(false, false, false, "Offline")
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var isCallbackRegistered: Boolean = false
    private var activeNetwork: android.net.Network? = null

    fun registerNetworkCallback() {
        if (isCallbackRegistered) return
        val cm = connectivityManager ?: return
        try {
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    activeNetwork = network
                    val caps = cm.getNetworkCapabilities(network)
                    updateConnectionMetadata(network, caps)
                }
                override fun onLost(network: android.net.Network) {
                    if (activeNetwork == null || activeNetwork == network) {
                        activeNetwork = null
                        lastActiveNetworkHash = 0
                        cachedWifiSsid = null
                        cachedCarrierName = null
                        cachedConnectionInfo = ConnectionInfo(false, false, false, "Offline")
                    }
                }
                override fun onCapabilitiesChanged(network: android.net.Network, capabilities: NetworkCapabilities) {
                    activeNetwork = network
                    updateConnectionMetadata(network, capabilities)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                cm.registerDefaultNetworkCallback(callback)
            } else {
                val request = android.net.NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                cm.registerNetworkCallback(request, callback)
            }
            networkCallback = callback
            isCallbackRegistered = true

            // Initial population
            refreshActiveConnection()
        } catch (_: Exception) {}
    }

    fun unregisterNetworkCallback() {
        if (!isCallbackRegistered) return
        try {
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        } catch (_: Exception) {}
        networkCallback = null
        isCallbackRegistered = false
        activeNetwork = null
    }

    internal fun refreshActiveConnection() {
        val cm = connectivityManager ?: run {
            cachedConnectionInfo = ConnectionInfo(false, false, false, "No Network")
            return
        }
        val current = cm.activeNetwork
        activeNetwork = current
        val capabilities = current?.let { cm.getNetworkCapabilities(it) }
        updateConnectionMetadata(current, capabilities)
    }

    internal fun updateConnectionMetadata(network: android.net.Network?, capabilities: NetworkCapabilities?) {
        if (network == null || capabilities == null) {
            cachedConnectionInfo = ConnectionInfo(false, false, false, "Offline")
            return
        }

        val currentNetworkHash = network.hashCode()
        val networkChanged = currentNetworkHash != lastActiveNetworkHash
        val now = System.currentTimeMillis()

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

        cachedConnectionInfo = ConnectionInfo(
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

    fun getLocalIpAddress(forceRefresh: Boolean = false): String {
        val now = System.currentTimeMillis()
        if (!forceRefresh && (now - lastIpCheckTime) < 30000L) {
            return cachedIpAddress
        }
        lastIpCheckTime = now
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return cachedIpAddress
            var fallbackIpv6: String? = null
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                val addrs = intf.inetAddresses ?: continue
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        if (addr is java.net.Inet4Address) {
                            cachedIpAddress = host
                            return host
                        } else if (addr is java.net.Inet6Address && fallbackIpv6 == null) {
                            fallbackIpv6 = host.split("%").firstOrNull() ?: host
                        }
                    }
                }
            }
            if (fallbackIpv6 != null) {
                cachedIpAddress = fallbackIpv6
                return fallbackIpv6
            }
        } catch (_: Exception) {}
        cachedIpAddress = "Unavailable"
        return cachedIpAddress
    }

    /**
     * Returns the cached connection information updated by network callbacks.
     * Avoids expensive per-second binder IPC queries to ConnectivityManager.
     */
    fun getConnectionInfo(): ConnectionInfo {
        if (!isCallbackRegistered) {
            refreshActiveConnection()
        }
        return cachedConnectionInfo
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
