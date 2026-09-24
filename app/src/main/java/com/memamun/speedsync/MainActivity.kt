package com.memamun.speedsync

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.memamun.speedsync.data.DataUsageRepository
import com.memamun.speedsync.service.SpeedMeterService
import com.memamun.speedsync.ui.SpeedMeterViewModel
import com.memamun.speedsync.ui.components.HistoryView
import com.memamun.speedsync.ui.components.SettingsDialog
import com.memamun.speedsync.ui.components.SpeedMeterBottomNav
import com.memamun.speedsync.ui.components.SpeedMeterHeader
import com.memamun.speedsync.ui.screens.NetworkInfoScreen
import com.memamun.speedsync.ui.screens.SpeedMainScreen
import com.memamun.speedsync.ui.theme.MyApplicationTheme

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
        val notifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms[Manifest.permission.POST_NOTIFICATIONS] ?: hasNotificationPermission
        } else {
            true
        }
        hasNotificationPermission = notifGranted
        hasLocationPermission = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        val repo = DataUsageRepository.getInstance(context)
        if (notifGranted && repo.isServiceEnabled()) {
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

        val repo = DataUsageRepository.getInstance(context)
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
                Crossfade(
                    targetState = selectedTab,
                    animationSpec = tween(durationMillis = 200),
                    label = "tab_crossfade"
                ) { tab ->
                    when (tab) {
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
                            HistoryView(
                                historyList = historyList,
                                onClearHistory = { viewModel.clearHistory() }
                            )
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
