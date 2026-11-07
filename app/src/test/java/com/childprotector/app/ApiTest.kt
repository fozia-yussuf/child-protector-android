package com.childprotector.app

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import okhttp3.tls.HeldCertificate
import okhttp3.tls.HandshakeCertificates
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
class ApiTest {
    private lateinit var server: MockWebServer
    private lateinit var store: SessionStore
    private lateinit var api: Api
    private val token = "test-session-token"

    @Before
    fun setUp() {
        server = MockWebServer()
        val certificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName("127.0.0.1")
            .build()
        val certificates = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .addTrustedCertificate(certificate.certificate)
            .build()
        server.useHttps(certificates.sslSocketFactory(), false)
        server.start()

        store = mockk(relaxed = true)
        every { store.session } returns token
        api = Api(
            store,
            server.url("/").toString().trimEnd('/'),
            clientConfigurer = { builder ->
                builder.sslSocketFactory(
                    certificates.sslSocketFactory(),
                    certificates.trustManager
                )
            }
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun sendsBearerTokenAndParsesSuccessfulArrayEnvelope() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"child-1"}]}"""))

        val result = api.children()

        assertEquals("child-1", result.single().getString("id"))
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("Bearer $token", request.getHeader("Authorization"))
    }

    @Test
    fun returnsServerMessageForHttpErrors() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(403).setBody("""{"message":"Forbidden"}""")
        )

        val error = runCatching { api.children() }.exceptionOrNull()

        assertEquals("Forbidden", error?.message)
    }

    @Test
    fun exposesFieldDetailsForSignupValidationFailures() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(400).setBody(
                """{"error":"Validation failed","details":[{"path":"password","message":"Too small: expected string to have >=8 characters"}]}"""
            )
        )

        val error = runCatching { api.signup("parent-user", "short", "PARENT") }.exceptionOrNull()

        assertEquals(
            "Password: Too small: expected string to have >=8 characters",
            error?.message
        )
    }

    @Test
    fun explainsUsernameConflictDuringSignup() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(409).setBody(
                """{"message":"A record with these values already exists"}"""
            )
        )

        val error = runCatching { api.signup("existing-user", "password-123", "DRIVER") }
            .exceptionOrNull()

        assertEquals(
            "That username is already taken. Choose another or sign in.",
            error?.message
        )
    }

    @Test
    fun parentCanSubmitLinkRequestUsingSchoolStudentId() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(201).setBody(
                """{"data":{"studentId":"STUDENT-1","status":"PENDING"}}"""
            )
        )

        val result = api.createParentLinkRequest("STUDENT-1")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/parent/link-requests", request.path)
        assertEquals("STUDENT-1", JSONObject(request.body.readUtf8()).getString("studentId"))
        assertEquals("PENDING", result.getJSONObject("data").getString("status"))
    }

    @Test
    fun gpsUploadUsesTheTimestampOfTheLocationFix() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":{}}"""))
        val fixTime = 123_456_789L

        api.location("trip-1", 1.25, 103.8, 5f, fixTime)

        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        assertEquals("/api/trips/trip-1/location", request.path)
        assertEquals(fixTime, body.getLong("timestamp"))
    }

    @Test
    fun createsStudentWithPhotoAndOptionalParentLink() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"data":{"id":"child-1"}}"""))

        api.createChild(
            name = "Student",
            studentId = "student-1",
            className = "Class 1",
            route = "Route A",
            photoBase64 = "validated-student-photo",
            parentUsername = "parent-one",
            parentConsentObtained = true
        )

        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        assertEquals("/api/children", request.path)
        assertEquals("validated-student-photo", body.getString("photo"))
        assertEquals("parent-one", body.getString("parentUsername"))
        assertTrue(body.getBoolean("parentConsentObtained"))
    }

    @Test
    fun confirmsStudentArrivalForSelectedTrip() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"data":{"id":"arrival-1","studentId":"child-1","destination":"SCHOOL"}}"""
            )
        )

        val result = api.confirmArrival("trip-1", "child-1", "SCHOOL")

        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        assertEquals("arrival-1", result.getString("id"))
        assertEquals("/api/trips/trip-1/arrivals", request.path)
        assertEquals("child-1", body.getString("studentId"))
        assertEquals("SCHOOL", body.getString("destination"))
    }

    @Test
    fun reportsInvalidJsonOnSuccessfulResponse() = runTest {
        server.enqueue(MockResponse().setBody("not-json"))

        val error = runCatching { api.children() }.exceptionOrNull()

        assertEquals("Invalid response format from server", error?.message)
    }

    @Test
    fun unauthorizedResponseClearsSessionAndNotifiesHandler() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(401).setBody("""{"message":"Session expired"}""")
        )
        val handler = mockk<() -> Unit>()
        every { handler.invoke() } just runs
        api.setUnauthorizedHandler(handler)

        val error = runCatching { api.children() }.exceptionOrNull()

        assertEquals("Session expired", error?.message)
        verify(exactly = 1) { store.clear() }
        verify(exactly = 1) { handler.invoke() }
    }

    @Test
    fun rejectsNonHttpsApiBaseUrl() {
        val error = runCatching { Api(store, "http://example.test") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertFalse(error?.message.isNullOrBlank())
    }
}
