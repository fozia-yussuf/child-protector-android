package com.childprotector.app

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class AppVMTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var api: Api

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        api = mockk()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun refreshLoadsDriverCollectionsAndClearsLoadingState() = runTest(dispatcher) {
        val children = listOf(JSONObject("""{"id":"child-1"}"""))
        val trips = listOf(JSONObject("""{"id":"trip-1"}"""))
        val scans = listOf(JSONObject("""{"id":"scan-1"}"""))
        val emergencies = listOf(JSONObject("""{"id":"emergency-1"}"""))
        coEvery { api.children() } returns children
        coEvery { api.trips() } returns trips
        coEvery { api.scans(any()) } returns scans
        coEvery { api.emergencies() } returns emergencies
        val viewModel = AppVM(api).apply { role = "DRIVER" }

        viewModel.refresh()

        assertEquals(children, viewModel.children)
        assertEquals(trips, viewModel.trips)
        assertEquals(scans, viewModel.scans)
        assertEquals(emergencies, viewModel.emergencies)
        assertFalse(viewModel.loading)
        assertEquals("", viewModel.message)
        coVerify(exactly = 1) { api.children() }
    }

    @Test
    fun refreshReportsApiErrorsAndRestoresLoadingState() = runTest(dispatcher) {
        coEvery { api.children() } throws IOException("backend offline")
        val viewModel = AppVM(api).apply { role = "DRIVER" }

        viewModel.refresh()

        assertEquals("backend offline", viewModel.message)
        assertFalse(viewModel.loading)
    }

    @Test
    fun parentRefreshSelectsLinkedChildAndUsesParentEndpoints() = runTest(dispatcher) {
        val child = JSONObject("""{"id":"linked-child"}""")
        coEvery { api.parentChildren() } returns listOf(child)
        coEvery { api.parentTrips() } returns emptyList()
        coEvery { api.parentScans(any()) } returns emptyList()
        coEvery { api.parentEmergencies() } returns emptyList()
        val viewModel = AppVM(api).apply { role = "PARENT" }

        viewModel.refresh()

        assertTrue(viewModel.children.single() === child)
        assertTrue(viewModel.selectedChild === child)
        coVerify(exactly = 1) { api.parentChildren() }
    }
}
