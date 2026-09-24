package com.memamun.speedsync

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.memamun.speedsync.ui.components.SpeedGauge
import com.memamun.speedsync.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class SpeedGaugeScreenshotTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun speedGauge_screenshot() {
    composeTestRule.setContent {
      MyApplicationTheme {
        SpeedGauge(
          speedValue = "142.8",
          speedUnitLabel = "Mbps Download",
          progressFraction = 0.65f
        )
      }
    }

    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/speed_gauge.png")
  }
}
