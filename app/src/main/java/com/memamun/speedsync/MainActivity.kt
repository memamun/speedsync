package com.memamun.speedsync

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.core.net.toUri
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.memamun.speedsync.data.DataUsageRepository
import com.memamun.speedsync.model.SpeedTestPhase
import com.memamun.speedsync.service.SpeedMeterService
import com.memamun.speedsync.ui.SpeedMeterViewModel
import com.memamun.speedsync.ui.components.HistoryView
import com.memamun.speedsync.ui.components.MetricCardsGrid
import com.memamun.speedsync.ui.components.SettingsDialog
import com.memamun.speedsync.ui.components.SpeedGauge
import com.memamun.speedsync.ui.theme.MyApplicationTheme
import com.memamun.speedsync.ui.theme.StatusGreen
import com.memamun.speedsync.ui.theme.statusGreen
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val viewModel: SpeedMeterViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            MyApplicationTheme(themeMode = themeMode) {
                SpeedMeterApp(viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.checkBatteryOptimization()
    }
}

@Composable
fun SpeedMeterApp(viewModel: SpeedMeterViewModel) {
    val context = LocalContext.current
    val liveSpeed by viewModel.liveSpeed.collectAsStateWithLifecycle()
    val isServiceRunning by viewModel.isServiceRunning.collectAsStateWithLifecycle()
    val speedTestResult by viewModel.speedTestResult.collectAsStateWithLifecycle()
    val historyList by viewModel.historyList.collectAsStateWithLifecycle()
    val isStartOnBoot by viewModel.isStartOnBoot.collectAsStateWithLifecycle()
    val speedUnit by viewModel.speedUnit.collectAsStateWithLifecycle()
    val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
    val showSettingsDialog by viewModel.showSettingsDialog.collectAsStateWithLifecycle()
    val isTesting by viewModel.isTesting.collectAsStateWithLifecycle()
    val isBatteryOptimizationIgnored by viewModel.isBatteryOptimizationIgnored.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()

    var hasNotificationPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        )
    }

    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val notifGranted = perms[Manifest.permission.POST_NOTIFICATIONS] ?: hasNotificationPermission
        hasNotificationPermission = notifGranted
        hasLocationPermission = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (notifGranted) {
            SpeedMeterService.start(context)
        }
    }

    // Auto-start live status bar meter and request runtime permissions on launch
    LaunchedEffect(Unit) {
        val permissionsToRequest = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (!hasLocationPermission) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (permissionsToRequest.isNotEmpty()) {
            permissionsLauncher.launch(permissionsToRequest.toTypedArray())
        }

        val repo = DataUsageRepository(context)
        if (repo.isServiceEnabled() && hasNotificationPermission) {
            SpeedMeterService.start(context)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        bottomBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                SpeedMeterBottomNav(
                    selectedTab = selectedTab,
                    onTabSelected = { viewModel.setSelectedTab(it) }
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding(), bottom = innerPadding.calculateBottomPadding())
        ) {
            // Header: starts cleanly at safe area top
            SpeedMeterHeader(
                networkName = liveSpeed.networkName,
                isConnected = liveSpeed.isConnected,
                onSettingsClick = { viewModel.setShowSettingsDialog(true) }
            )

            // Content body based on tab
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                when (selectedTab) {
                    0 -> {
                        SpeedMainScreen(
                            isTesting = isTesting,
                            speedTestResult = speedTestResult,
                            liveSpeed = liveSpeed,
                            speedUnit = speedUnit,
                            onRunSpeedTest = {
                                viewModel.startSpeedTest()
                            },
                            onCancelSpeedTest = {
                                viewModel.cancelSpeedTest()
                            }
                        )
                    }
                    1 -> {
                        HistoryView(historyList = historyList)
                    }
                    2 -> {
                        NetworkInfoScreen(
                            liveSpeed = liveSpeed,
                            isServiceRunning = isServiceRunning
                        )
                    }
                }
            }
        }
    }

    if (showSettingsDialog) {
        SettingsDialog(
            isServiceRunning = isServiceRunning,
            isStartOnBoot = isStartOnBoot,
            isBatteryOptimizationIgnored = isBatteryOptimizationIgnored,
            currentUnit = speedUnit,
            currentThemeMode = themeMode,
            onToggleService = {
                if (!hasNotificationPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val permissions = mutableListOf(Manifest.permission.POST_NOTIFICATIONS)
                    if (!hasLocationPermission) {
                        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
                        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
                    }
                    permissionsLauncher.launch(permissions.toTypedArray())
                } else {
                    viewModel.toggleService()
                }
            },
            onToggleStartOnBoot = { viewModel.toggleStartOnBoot(it) },
            onRequestIgnoreBatteryOptimization = {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = "package:${context.packageName}".toUri()
                    }
                    context.startActivity(intent)
                } catch (e: Exception) {
                    try {
                        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        context.startActivity(intent)
                    } catch (_: Exception) {}
                }
            },
            onSelectUnit = { viewModel.setSpeedUnit(it) },
            onSelectThemeMode = { viewModel.setThemeMode(it) },
            onDismiss = { viewModel.setShowSettingsDialog(false) }
        )
    }
}

@Composable
fun SpeedMeterHeader(
    networkName: String,
    isConnected: Boolean,
    onSettingsClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "CURRENT PROVIDER",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.6.sp,
                    color = MaterialTheme.colorScheme.primary
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = networkName,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(
                                if (isConnected) MaterialTheme.statusGreen else Color.Gray,
                                CircleShape
                            )
                    )
                }
            }

            IconButton(
                onClick = onSettingsClick,
                modifier = Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                    .testTag("settings_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
fun SpeedMainScreen(
    isTesting: Boolean,
    speedTestResult: com.memamun.speedsync.model.SpeedTestResult,
    liveSpeed: com.memamun.speedsync.model.LiveSpeedData,
    speedUnit: com.memamun.speedsync.model.SpeedUnit,
    onRunSpeedTest: () -> Unit,
    onCancelSpeedTest: () -> Unit = {}
) {
    // Gauge speed calculation
    val (displaySpeed, displayUnit, progress) = remember(isTesting, speedTestResult, liveSpeed, speedUnit) {
        if (isTesting || speedTestResult.testFinished) {
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

    val pingStr = if (speedTestResult.pingMs > 0) speedTestResult.pingMs.toString() else "18"
    val jitterStr = if (speedTestResult.jitterMs > 0) speedTestResult.jitterMs.toString() else "4"
    val lossStr = String.format(Locale.US, "%.1f", speedTestResult.packetLossPercent)

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
                    Text(
                        text = if (speedTestResult.testFinished) "TEST AGAIN" else "START SPEED TEST",
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

@Composable
fun NetworkInfoScreen(
    liveSpeed: com.memamun.speedsync.model.LiveSpeedData,
    isServiceRunning: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
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

@Composable
fun SpeedMeterBottomNav(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .width(288.dp)
            .height(64.dp),
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            NavTabItem(
                icon = Icons.Default.Speed,
                label = "Speed",
                isSelected = selectedTab == 0,
                onClick = { onTabSelected(0) },
                modifier = Modifier.weight(1f)
            )
            NavTabItem(
                icon = Icons.Default.BarChart,
                label = "History",
                isSelected = selectedTab == 1,
                onClick = { onTabSelected(1) },
                modifier = Modifier.weight(1f)
            )
            NavTabItem(
                icon = Icons.Default.Info,
                label = "Diagnostics",
                isSelected = selectedTab == 2,
                onClick = { onTabSelected(2) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun NavTabItem(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()

    // M3 Expressive: Tactile spring press feedback
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.93f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "nav_press_scale"
    )

    // M3 Expressive: Active indicator pill horizontal expansion with spring physics
    val indicatorScaleX by animateFloatAsState(
        targetValue = when {
            isSelected -> 1.0f
            isFocused || isHovered -> 0.92f
            else -> 0.65f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "nav_indicator_scale_x"
    )

    // M3 Expressive: Indicator alpha transition
    val indicatorAlpha by animateFloatAsState(
        targetValue = when {
            isSelected -> 1.0f
            isFocused -> 0.7f
            isHovered -> 0.45f
            else -> 0.0f
        },
        animationSpec = spring(
            dampingRatio = 0.8f,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "nav_indicator_alpha"
    )

    // M3 Expressive: Icon spring pop & subtle vertical lift
    val iconScale by animateFloatAsState(
        targetValue = if (isSelected) 1.10f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "nav_icon_scale"
    )

    val iconOffsetY by animateFloatAsState(
        targetValue = if (isSelected) -1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "nav_icon_offset_y"
    )

    // Subtle, elegant Material 3 container tint (soft and non-striking)
    val indicatorColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f)

    // Content color: primary for selected, onSurfaceVariant for inactive
    val contentColor by animateColorAsState(
        targetValue = when {
            isSelected -> MaterialTheme.colorScheme.primary
            isFocused || isHovered -> MaterialTheme.colorScheme.onSurface
            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
        },
        animationSpec = tween(durationMillis = 180),
        label = "nav_content_color"
    )

    val pillShape = RoundedCornerShape(20.dp)

    Box(
        modifier = modifier
            .fillMaxHeight()
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(pillShape)
            .selectable(
                selected = isSelected,
                interactionSource = interactionSource,
                indication = ripple(
                    bounded = true,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                ),
                role = Role.Tab,
                onClick = onClick
            )
            .hoverable(interactionSource = interactionSource)
            .focusable(interactionSource = interactionSource),
        contentAlignment = Alignment.Center
    ) {
        // M3 Expressive animated subtle indicator pill
        if (indicatorAlpha > 0.01f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = indicatorAlpha
                        scaleX = indicatorScaleX
                        scaleY = 1.0f
                    }
                    .background(indicatorColor, pillShape)
                    .then(
                        if (isFocused && !isSelected) {
                            Modifier.border(
                                BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                                pillShape
                            )
                        } else {
                            Modifier
                        }
                    )
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier
                    .size(22.dp)
                    .graphicsLayer {
                        scaleX = iconScale
                        scaleY = iconScale
                        translationY = iconOffsetY
                    }
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                color = contentColor,
                maxLines = 1
            )
        }
    }
}
