package com.memamun.speedsync.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.memamun.speedsync.data.DataUsageRepository
import com.memamun.speedsync.model.DayUsageItem
import com.memamun.speedsync.model.LiveSpeedData
import com.memamun.speedsync.model.SpeedTestPhase
import com.memamun.speedsync.model.SpeedTestResult
import com.memamun.speedsync.model.SpeedUnit
import com.memamun.speedsync.model.ThemeMode
import com.memamun.speedsync.network.SpeedTestEngine
import com.memamun.speedsync.service.SpeedMeterService
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SpeedMeterViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = DataUsageRepository.getInstance(application)
    private val speedTestEngine = SpeedTestEngine()

    val liveSpeed: StateFlow<LiveSpeedData> = SpeedMeterService.liveSpeedData
    val isServiceRunning: StateFlow<Boolean> = SpeedMeterService.isServiceRunning
    val speedTestResult: StateFlow<SpeedTestResult> = speedTestEngine.testResult

    private val _historyList = MutableStateFlow<List<DayUsageItem>>(emptyList())
    val historyList: StateFlow<List<DayUsageItem>> = _historyList.asStateFlow()

    private val _isStartOnBoot = MutableStateFlow(repository.isStartOnBoot())
    val isStartOnBoot: StateFlow<Boolean> = _isStartOnBoot.asStateFlow()

    private val _speedUnit = MutableStateFlow(repository.getSpeedUnit())
    val speedUnit: StateFlow<SpeedUnit> = _speedUnit.asStateFlow()

    private val _themeMode = MutableStateFlow(repository.getThemeMode())
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _selectedTab = MutableStateFlow(0)
    val selectedTab: StateFlow<Int> = _selectedTab.asStateFlow()

    private val _showSettingsDialog = MutableStateFlow(false)
    val showSettingsDialog: StateFlow<Boolean> = _showSettingsDialog.asStateFlow()

    private val _isTesting = MutableStateFlow(false)
    val isTesting: StateFlow<Boolean> = _isTesting.asStateFlow()

    private val _isBatteryOptimizationIgnored = MutableStateFlow(false)
    val isBatteryOptimizationIgnored: StateFlow<Boolean> = _isBatteryOptimizationIgnored.asStateFlow()

    init {
        refreshHistory()
        checkBatteryOptimization()
    }

    fun checkBatteryOptimization() {
        val app = getApplication<Application>()
        val pm = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
        _isBatteryOptimizationIgnored.value = pm?.isIgnoringBatteryOptimizations(app.packageName) == true
    }

    fun setSelectedTab(tab: Int) {
        _selectedTab.value = tab
        if (tab == 1) {
            refreshHistory()
        }
    }

    fun setShowSettingsDialog(show: Boolean) {
        _showSettingsDialog.value = show
    }

    fun refreshHistory() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val list = repository.getUsageHistory()
            _historyList.value = list
        }
    }

    fun clearHistory() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            repository.clearHistory()
            val list = repository.getUsageHistory()
            _historyList.value = list
        }
    }

    fun toggleService() {
        val app = getApplication<Application>()
        if (isServiceRunning.value) {
            SpeedMeterService.stop(app)
            repository.setServiceEnabled(false)
        } else {
            repository.setServiceEnabled(true)
            SpeedMeterService.start(app)
        }
    }

    fun toggleStartOnBoot(enabled: Boolean) {
        repository.setStartOnBoot(enabled)
        _isStartOnBoot.value = enabled
    }

    fun setSpeedUnit(unit: SpeedUnit) {
        repository.setSpeedUnit(unit)
        _speedUnit.value = unit
    }

    fun setThemeMode(mode: ThemeMode) {
        repository.setThemeMode(mode)
        _themeMode.value = mode
    }

    private var speedTestJob: Job? = null

    fun startSpeedTest() {
        if (_isTesting.value) return
        _isTesting.value = true
        speedTestJob = viewModelScope.launch {
            try {
                speedTestEngine.runSpeedTest()
            } finally {
                _isTesting.value = false
                refreshHistory()
            }
        }
    }

    fun cancelSpeedTest() {
        speedTestJob?.cancel()
        speedTestEngine.cancel()
        _isTesting.value = false
    }
}
