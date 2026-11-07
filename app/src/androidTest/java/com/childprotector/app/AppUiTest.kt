package com.childprotector.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.provider.MediaStore
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.Role
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.Intents.intending
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.childprotector.app.ui.auth.AuthScreen
import com.childprotector.app.ui.common.ChildProtectorTheme
import com.childprotector.app.ui.driver.BoardingConfirmButton
import com.childprotector.app.ui.parent.ChildTab
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.json.JSONObject
import org.junit.Assert.assertTrue
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

        listOf("Home", "My Child", "Attendance", "Tracking", "Alerts")
            .forEach(::assertTextExists)
        assertTextAbsent("Board")
        composeRule.onNodeWithText("Tracking").performClick()
        composeRule.onNodeWithText("Select a linked child to see their route.").assertIsDisplayed()

        composeRule.runOnIdle { viewModel.role = "DRIVER" }
        composeRule.waitForIdle()
        listOf("Home", "Students", "Board", "Trips", "GPS", "SOS")
            .forEach(::assertTextExists)
        assertTextAbsent("Tracking")
        composeRule.onNodeWithText("Board").performClick()
        composeRule.onNodeWithText("Driver scans student face to verify against registered photo.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("SOS").performClick()
        composeRule.onNodeWithText("SEND TRANSPORT SOS").performClick()
        composeRule.onNodeWithText("Send emergency SOS?").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").performClick()
        assertTextAbsent("Send emergency SOS?")

        composeRule.runOnIdle { viewModel.role = "ADMIN" }
        composeRule.waitForIdle()
        listOf("Overview", "Students", "Trips", "More")
            .forEach(::assertTextExists)
        assertTextAbsent("Board")
        composeRule.onNodeWithText("More").performClick()
        composeRule.onNodeWithText("Parent Access").performClick()
        composeRule.onNodeWithText("Parent Access").assertIsDisplayed()
        composeRule.onAllNodesWithText("Students")[1].performClick()
        composeRule.onNodeWithText("Register").performClick()
        composeRule.onNodeWithText("Register student").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").performClick()
        assertTextAbsent("Register student")
    }

    @Test
    fun adminRoleIsNotSelectableOnSignupScreen() {
        val viewModel = AppVM(mockk(relaxed = true))
        composeRule.setContent {
            ChildProtectorTheme {
                AuthScreen(viewModel)
            }
        }

        @Test
        fun parentCanSubmitAChildAccessRequestFromMyChildrenTab() {
            val api = mockk<Api>(relaxed = true)
            stubEmptyData(api)
            coEvery { api.createParentLinkRequest("STUDENT-1") } returns JSONObject(
                """{"data":{"studentId":"STUDENT-1","status":"PENDING"}}"""
            )
            val viewModel = AppVM(api).apply { role = "PARENT" }
            composeRule.setContent {
                ChildProtectorTheme {
                    ChildTab(viewModel)
                }
            }

            composeRule.onNodeWithText("School student ID").performTextInput("STUDENT-1")
            composeRule.onNodeWithText("Request child access").performClick()

            composeRule.waitUntil(10_000) {
                composeRule.onAllNodesWithText(
                    "Request sent. The school administrator must approve it."
                ).fetchSemanticsNodes().isNotEmpty()
            }
            coVerify(exactly = 1) { api.createParentLinkRequest("STUDENT-1") }
        }

        composeRule.onNodeWithText("⚙️ Admin").assertIsDisplayed()
        composeRule.onNodeWithText("Create a new account").performClick()
        composeRule.onNodeWithText("Create Account").assertIsDisplayed()
        assertTextAbsent("⚙️ Admin")
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

    @Test
    fun registeringStudentHandlesCameraResultAndFaceValidationWithoutClosingApp() {
        val api = mockk<Api>(relaxed = true)
        stubEmptyData(api)
        val viewModel = AppVM(api).apply { role = "ADMIN" }
        val capturedBitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        Intents.init()
        try {
            intending(hasAction(MediaStore.ACTION_IMAGE_CAPTURE)).respondWith(
                Instrumentation.ActivityResult(
                    Activity.RESULT_OK,
                    Intent().putExtra("data", capturedBitmap)
                )
            )
            composeRule.setContent {
                ChildProtectorTheme {
                    MainScreen(viewModel)
                }
            }

            composeRule.onAllNodesWithText("Students")[1].performClick()
            composeRule.onNodeWithText("Register").performClick()
            composeRule.onNodeWithText("Name").performTextInput("UI Test Student")
            composeRule.onNodeWithText("Student ID").performTextInput("UI-TEST-1")
            composeRule.onNodeWithText("Take or replace photo").performClick()
            composeRule.onNodeWithText("Student photo").assertIsDisplayed()
            composeRule.onNode(
                SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)
            ).performClick()
            composeRule.onNodeWithText("Save").assertIsEnabled().performClick()

            composeRule.waitUntil(15_000) {
                composeRule.onAllNodesWithText("No face found. Retake the student photo.")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("Register student").assertIsDisplayed()
        } finally {
            Intents.release()
        }
    }

    private fun stubEmptyData(api: Api) {
        coEvery { api.parentChildren() } returns emptyList()
        coEvery { api.parentTrips() } returns emptyList()
        coEvery { api.parentScans(any()) } returns emptyList()
        coEvery { api.parentArrivals(any()) } returns emptyList()
        coEvery { api.parentEmergencies() } returns emptyList()
        coEvery { api.parentLinkRequests() } returns emptyList()
        coEvery { api.children() } returns emptyList()
        coEvery { api.trips() } returns emptyList()
        coEvery { api.scans(any()) } returns emptyList()
        coEvery { api.emergencies() } returns emptyList()
        coEvery { api.adminOverview() } returns JSONObject()
        coEvery { api.adminParents() } returns emptyList()
        coEvery { api.adminParentLinks() } returns emptyList()
        coEvery { api.adminParentLinkRequests() } returns emptyList()
    }

    private fun assertTextExists(text: String) {
        assertTrue(
            "Expected visible text: $text",
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        )
    }

    private fun assertTextAbsent(text: String) {
        assertTrue(
            "Expected no visible text: $text",
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()
        )
    }
}
