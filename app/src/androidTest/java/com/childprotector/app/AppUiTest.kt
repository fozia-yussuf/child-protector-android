package com.childprotector.app

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.childprotector.app.ui.auth.AuthScreen
import com.childprotector.app.ui.common.ChildProtectorTheme
import com.childprotector.app.ui.driver.BoardingConfirmButton
import io.mockk.coEvery
import io.mockk.mockk
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun roleNavigationShowsOnlyTabsForTheCurrentRole() {
        val api = mockk<Api>()
        stubEmptyData(api)
        val viewModel = AppVM(api).apply { role = "PARENT" }
        composeRule.setContent {
            ChildProtectorTheme {
                MainScreen(viewModel)
            }
        }

        listOf("Home", "My Child", "Attendance", "Tracking", "SOS")
            .forEach { composeRule.onNodeWithText(it).assertIsDisplayed() }
        composeRule.onAllNodesWithText("Board").assertCountEquals(0)

        composeRule.runOnIdle { viewModel.role = "DRIVER" }
        listOf("Home", "Students", "Board", "Trips", "GPS", "SOS")
            .forEach { composeRule.onNodeWithText(it).assertIsDisplayed() }
        composeRule.onAllNodesWithText("Tracking").assertCountEquals(0)

        composeRule.runOnIdle { viewModel.role = "ADMIN" }
        listOf("Overview", "Students", "Trips", "More")
            .forEach { composeRule.onNodeWithText(it).assertIsDisplayed() }
        composeRule.onAllNodesWithText("Board").assertCountEquals(0)
    }

    @Test
    fun adminRoleIsNotSelectableOnSignupScreen() {
        val viewModel = AppVM(mockk(relaxed = true))
        composeRule.setContent {
            ChildProtectorTheme {
                AuthScreen(viewModel)
            }
        }

        composeRule.onNodeWithText("⚙️ Admin").assertIsDisplayed()
        composeRule.onNodeWithText("Create a new account").performClick()
        composeRule.onNodeWithText("Create Account").assertIsDisplayed()
        composeRule.onAllNodesWithText("⚙️ Admin").assertCountEquals(0)
        composeRule.onNodeWithText("👨‍👩‍👧 Parent").assertIsDisplayed()
        composeRule.onNodeWithText("🚌 Driver").assertIsDisplayed()
    }

    @Test
    fun boardingConfirmationRemainsDisabledUntilMatchIsConfirmed() {
        composeRule.setContent {
            ChildProtectorTheme {
                var matchConfirmed = androidx.compose.runtime.remember {
                    androidx.compose.runtime.mutableStateOf(false)
                }
                Column {
                    BoardingConfirmButton(
                        studentSelected = true,
                        tripSelected = true,
                        cameraGranted = true,
                        frameAvailable = true,
                        faceMatchConfirmed = matchConfirmed.value,
                        manualOverride = false,
                        busy = false,
                        scanSubmitted = false,
                        onClick = {}
                    )
                    Button(onClick = { matchConfirmed.value = true }) {
                        Text("Simulate confirmed match")
                    }
                }
            }
        }

        composeRule.onNodeWithText("Confirm Student Boarding").assertIsNotEnabled()
        composeRule.onNodeWithText("Simulate confirmed match").performClick()
        composeRule.onNodeWithText("Confirm Student Boarding").assertIsEnabled()
    }

    private fun stubEmptyData(api: Api) {
        coEvery { api.parentChildren() } returns emptyList()
        coEvery { api.parentTrips() } returns emptyList()
        coEvery { api.parentScans(any()) } returns emptyList()
        coEvery { api.parentEmergencies() } returns emptyList()
        coEvery { api.children() } returns emptyList()
        coEvery { api.trips() } returns emptyList()
        coEvery { api.scans(any()) } returns emptyList()
        coEvery { api.emergencies() } returns emptyList()
        coEvery { api.adminOverview() } returns JSONObject()
    }
}
