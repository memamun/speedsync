package com.memamun.speedsync.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.TelephonyManager

class NetworkHelper(private val context: Context) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    companion object {
        private var cachedWifiSsid: String? = null
    }

    data class ConnectionInfo(
        val isConnected: Boolean,
        val isWifi: Boolean,
        val isMobile: Boolean,
        val networkName: String
    )

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
            isMobile -> {
                val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                val opName = tm?.networkOperatorName
                if (!opName.isNullOrEmpty()) {
                    opName
                } else {
                    "Mobile Data"
                }
            }
            isEthernet -> "Ethernet"
            else -> "Connected"
        }

        return ConnectionInfo(
            isConnected = true,
            isWifi = isWifi,
            isMobile = isMobile,
            networkName = name
        )
    }
}
