package com.memamun.speedsync.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.memamun.speedsync.data.DataUsageRepository
import com.memamun.speedsync.model.LiveSpeedData
import com.memamun.speedsync.ui.theme.statusGreen
import java.util.Locale

@Composable
fun NetworkInfoScreen(
    liveSpeed: LiveSpeedData,
    isServiceRunning: Boolean,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "NETWORK DIAGNOSTICS",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.6.sp,
            color = MaterialTheme.colorScheme.primary
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(22.dp))
                .border(
                    BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                    RoundedCornerShape(22.dp)
                )
                .padding(20.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                InfoRow(label = "Network Status", value = if (liveSpeed.isConnected) "Online" else "Offline", isHighlight = true)
                InfoRow(label = "Interface Type", value = if (liveSpeed.isWifi) "Wi-Fi (High Speed)" else if (liveSpeed.isMobile) "Cellular 5G/LTE" else "Local Adapter")
                InfoRow(label = "Access Point / Carrier", value = liveSpeed.networkName)
                InfoRow(label = "Local IP Address", value = liveSpeed.localIp)
                if (liveSpeed.isWifi && liveSpeed.linkSpeedMbps > 0) {
                    InfoRow(label = "Wi-Fi Link Speed", value = "${liveSpeed.linkSpeedMbps} Mbps")
                }
                if (liveSpeed.downstreamBandwidthKbps > 0) {
                    val downMbps = liveSpeed.downstreamBandwidthKbps / 1000.0
                    InfoRow(label = "Estimated Downlink", value = String.format(Locale.US, "%.1f Mbps", downMbps))
                }
                InfoRow(label = "Status Bar Live Service", value = if (isServiceRunning) "Running in Foreground" else "Stopped")
                InfoRow(label = "Today's Total Traffic", value = DataUsageRepository.formatBytes(liveSpeed.todayTotalBytes))
                InfoRow(label = "Wi-Fi Traffic Today", value = DataUsageRepository.formatBytes(liveSpeed.todayWifiBytes))
                InfoRow(label = "Mobile Traffic Today", value = DataUsageRepository.formatBytes(liveSpeed.todayMobileBytes))
            }
        }
    }
}

@Composable
fun InfoRow(
    label: String,
    value: String,
    isHighlight: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (isHighlight) MaterialTheme.statusGreen else MaterialTheme.colorScheme.onSurface
        )
    }
}
