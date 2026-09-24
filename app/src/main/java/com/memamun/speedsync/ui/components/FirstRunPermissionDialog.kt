package com.memamun.speedsync.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.memamun.speedsync.ui.theme.StatusGreen

@Composable
fun FirstRunPermissionDialog(
    hasNotificationPermission: Boolean,
    hasLocationPermission: Boolean,
    isBatteryOptimizationIgnored: Boolean,
    onGrantPermissions: () -> Unit,
    onDismiss: () -> Unit
) {
    val scrollState = rememberScrollState()
    val allPermissionsGranted = hasNotificationPermission && hasLocationPermission && isBatteryOptimizationIgnored

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
            tonalElevation = 8.dp,
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .padding(vertical = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth()
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header Icon
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .background(
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Speed,
                        contentDescription = "SpeedSync Icon",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                }

                // Title & Subtitle
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Welcome to SpeedSync",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "To monitor live internet speeds in your status bar and run reliably in the background, SpeedSync requires a few system permissions:",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp,
                        textAlign = TextAlign.Center
                    )
                }

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    thickness = 1.dp
                )

                // Permission Items
                PermissionItemRow(
                    icon = Icons.Rounded.NotificationsActive,
                    title = "Status Bar Speed Meter",
                    description = "Displays real-time download and upload speeds in the status bar and ongoing notification.",
                    isGranted = hasNotificationPermission,
                    badgeText = if (hasNotificationPermission) "Granted" else "Required"
                )

                PermissionItemRow(
                    icon = Icons.Rounded.Wifi,
                    title = "Network Diagnostics",
                    description = "Enables detecting connected Wi-Fi network names (SSID) and mobile carrier details.",
                    isGranted = hasLocationPermission,
                    badgeText = if (hasLocationPermission) "Granted" else "Recommended"
                )

                PermissionItemRow(
                    icon = Icons.Rounded.BatteryChargingFull,
                    title = "Disable Battery Optimization",
                    description = "Prevents Android OS from stopping the speed monitoring service when the screen is locked.",
                    isGranted = isBatteryOptimizationIgnored,
                    badgeText = if (isBatteryOptimizationIgnored) "Disabled" else "Recommended"
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Action Buttons
                Button(
                    onClick = onGrantPermissions,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(
                        imageVector = if (allPermissionsGranted) Icons.Rounded.Check else Icons.AutoMirrored.Rounded.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (allPermissionsGranted) "All Set • Get Started" else "Grant & Turn Off Optimization",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Skip for now",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionItemRow(
    icon: ImageVector,
    title: String,
    description: String,
    isGranted: Boolean,
    badgeText: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                RoundedCornerShape(14.dp)
            )
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .background(
                    if (isGranted) StatusGreen.copy(alpha = 0.18f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isGranted) Icons.Rounded.Check else icon,
                contentDescription = title,
                tint = if (isGranted) StatusGreen else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                // Status Badge
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (isGranted) StatusGreen.copy(alpha = 0.15f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                ) {
                    Text(
                        text = badgeText,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (isGranted) StatusGreen else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Text(
                text = description,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 15.sp
            )
        }
    }
}
