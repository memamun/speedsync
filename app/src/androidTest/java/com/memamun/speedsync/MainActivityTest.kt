package com.memamun.speedsync

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun mainActivity_launchesAndDisplaysCoreUi() {
        // Verify Current Provider header
        composeTestRule.onNodeWithText("CURRENT PROVIDER").assertIsDisplayed()

        // Verify Test Speed button exists
        composeTestRule.onNodeWithTag("test_speed_button").assertIsDisplayed()

        // Verify Settings button exists
        composeTestRule.onNodeWithTag("settings_button").assertIsDisplayed()
    }

    @Test
    fun mainActivity_navigationTabsExist() {
        composeTestRule.onNodeWithText("Speed").assertIsDisplayed()
        composeTestRule.onNodeWithText("History").assertIsDisplayed()
        composeTestRule.onNodeWithText("Diagnostics").assertIsDisplayed()
    }
}
