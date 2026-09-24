package com.memamun.speedsync.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.memamun.speedsync.model.SpeedUnit
import com.memamun.speedsync.model.ThemeMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SpeedMeterViewModelTest {

    private lateinit var application: Application
    private lateinit var viewModel: SpeedMeterViewModel

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        viewModel = SpeedMeterViewModel(application)
    }

    @Test
    fun initialValues_areSensibleDefaults() {
        assertEquals(0, viewModel.selectedTab.value)
        assertFalse(viewModel.showSettingsDialog.value)
        assertFalse(viewModel.isTesting.value)
    }

    @Test
    fun setSelectedTab_updatesSelectedTab() {
        viewModel.setSelectedTab(2)
        assertEquals(2, viewModel.selectedTab.value)

        viewModel.setSelectedTab(0)
        assertEquals(0, viewModel.selectedTab.value)
    }

    @Test
    fun setShowSettingsDialog_updatesDialogState() {
        viewModel.setShowSettingsDialog(true)
        assertTrue(viewModel.showSettingsDialog.value)

        viewModel.setShowSettingsDialog(false)
        assertFalse(viewModel.showSettingsDialog.value)
    }

    @Test
    fun setSpeedUnit_updatesUnitState() {
        viewModel.setSpeedUnit(SpeedUnit.KBPS)
        assertEquals(SpeedUnit.KBPS, viewModel.speedUnit.value)

        viewModel.setSpeedUnit(SpeedUnit.MBPS)
        assertEquals(SpeedUnit.MBPS, viewModel.speedUnit.value)
    }

    @Test
    fun setThemeMode_updatesThemeModeState() {
        viewModel.setThemeMode(ThemeMode.DARK)
        assertEquals(ThemeMode.DARK, viewModel.themeMode.value)

        viewModel.setThemeMode(ThemeMode.LIGHT)
        assertEquals(ThemeMode.LIGHT, viewModel.themeMode.value)

        viewModel.setThemeMode(ThemeMode.SYSTEM)
        assertEquals(ThemeMode.SYSTEM, viewModel.themeMode.value)
    }

    @Test
    fun toggleStartOnBoot_updatesState() {
        viewModel.toggleStartOnBoot(true)
        assertTrue(viewModel.isStartOnBoot.value)

        viewModel.toggleStartOnBoot(false)
        assertFalse(viewModel.isStartOnBoot.value)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun setSelectedTabHistory_refreshesHistory() = runTest {
        viewModel.setSelectedTab(1)
        advanceUntilIdle()
        assertEquals(1, viewModel.selectedTab.value)
    }

    @Test
    fun firstRunDialog_updatesAndCompletesCorrectly() {
        viewModel.setShowFirstRunDialog(true)
        assertTrue(viewModel.showFirstRunDialog.value)

        viewModel.completeFirstRun()
        assertFalse(viewModel.showFirstRunDialog.value)

        viewModel.setShowFirstRunDialog(false)
        assertFalse(viewModel.showFirstRunDialog.value)
    }
}
