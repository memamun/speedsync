package com.memamun.speedsync.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.memamun.speedsync.data.DataUsageRepository
import com.memamun.speedsync.model.LiveSpeedData
import com.memamun.speedsync.model.SpeedTestPhase
import com.memamun.speedsync.model.SpeedTestResult
import com.memamun.speedsync.model.SpeedUnit
import com.memamun.speedsync.ui.components.MetricCardsGrid
import com.memamun.speedsync.ui.components.SpeedGauge
import java.util.Locale

@Composable
fun SpeedMainScreen(
    isTesting: Boolean,
    speedTestResult: SpeedTestResult,
    liveSpeed: LiveSpeedData,
    speedUnit: SpeedUnit,
    onRunSpeedTest: () -> Unit,
    onCancelSpeedTest: () -> Unit = {}
) {
    // Gauge speed calculation
    val (displaySpeed, displayUnit, progress) = remember(isTesting, speedTestResult, liveSpeed, speedUnit) {
        if (speedTestResult.phase == SpeedTestPhase.ERROR) {
            Triple("—", "Test Failed", 0.05f)
        } else if (isTesting || speedTestResult.testFinished) {
            val speed = speedTestResult.currentSpeedMbps
            val formatted = String.format(Locale.US, "%.1f", speed)
            val unit = when (speedTestResult.phase) {
                SpeedTestPhase.PING -> "Ping Test"
                SpeedTestPhase.DOWNLOAD -> "Mbps Download"
                SpeedTestPhase.UPLOAD -> "Mbps Upload"
                SpeedTestPhase.COMPLETED -> "Mbps Download"
                else -> "Mbps"
            }
            val prog = (speed / 200.0).toFloat().coerceIn(0.05f, 1f)
            Triple(formatted, unit, prog)
        } else {
            val (valStr, unitStr) = DataUsageRepository.formatSpeed(liveSpeed.downloadSpeedBytes, speedUnit)
            val prog = (liveSpeed.downloadSpeedBytes / (5.0 * 1024 * 1024)).toFloat().coerceIn(0.04f, 1f)
            Triple(valStr, "$unitStr Live", prog)
        }
    }

    // Metric cards calculations
    val (uploadVal, uploadUnit) = remember(isTesting, speedTestResult, liveSpeed, speedUnit) {
        if (isTesting || speedTestResult.testFinished) {
            val up = speedTestResult.uploadSpeedMbps
            Pair(String.format(Locale.US, "%.1f", up), "Mbps")
        } else {
            DataUsageRepository.formatSpeed(liveSpeed.uploadSpeedBytes, speedUnit)
        }
    }

    val pingStr = if (speedTestResult.pingMs > 0) speedTestResult.pingMs.toString() else "—"
    val jitterStr = if (speedTestResult.jitterMs > 0) speedTestResult.jitterMs.toString() else "—"
    val lossStr = if (speedTestResult.testFinished || isTesting) String.format(Locale.US, "%.1f", speedTestResult.packetLossPercent) else "—"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Central Speedometer Gauge (fits cleanly with generous spacing)
        SpeedGauge(
            speedValue = displaySpeed,
            speedUnitLabel = displayUnit,
            progressFraction = progress
        )

        // Error message banner if test encountered an issue
        if (speedTestResult.phase == SpeedTestPhase.ERROR && !speedTestResult.errorMessage.isNullOrEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.85f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Error",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = speedTestResult.errorMessage,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // 2x2 Metric Cards Grid
        MetricCardsGrid(
            uploadSpeed = uploadVal,
            uploadUnit = uploadUnit,
            pingMs = pingStr,
            jitterMs = jitterStr,
            lossPercent = lossStr
        )

        // Primary Test Button
        Button(
            onClick = {
                if (isTesting) onCancelSpeedTest() else onRunSpeedTest()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .testTag("test_speed_button"),
            shape = RoundedCornerShape(27.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isTesting) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                contentColor = if (isTesting) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isTesting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.5.dp,
                        color = MaterialTheme.colorScheme.onError
                    )
                    Text(
                        text = "CANCEL TEST",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        letterSpacing = 0.8.sp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    val btnLabel = when {
                        speedTestResult.phase == SpeedTestPhase.ERROR -> "RETRY SPEED TEST"
                        speedTestResult.testFinished -> "TEST AGAIN"
                        else -> "START SPEED TEST"
                    }
                    Text(
                        text = btnLabel,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        letterSpacing = 0.8.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(2.dp))
    }
}
