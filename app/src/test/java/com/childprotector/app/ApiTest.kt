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
