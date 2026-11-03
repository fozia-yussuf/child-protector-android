package com.childprotector.app

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SessionStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun clearPreferences() {
        context.getSharedPreferences("child_protector_secure", Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("child_protector", Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("test_secure_session", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun persistsSessionFieldsInEncryptedPreferencesAndCanClearThem() {
        val securePreferences = context.getSharedPreferences("test_secure_session", Context.MODE_PRIVATE)
        val store = SessionStore(context, securePreferences)
        store.username = "test-parent"
        store.role = "PARENT"
        store.session = "sensitive-session-token"

        val restored = SessionStore(context, securePreferences)
        assertEquals("test-parent", restored.username)
        assertEquals("PARENT", restored.role)
        assertEquals("sensitive-session-token", restored.session)

        restored.clear()
        assertNull(restored.username)
        assertNull(restored.role)
        assertNull(restored.session)
    }

    @Test
    fun migratesLegacySessionValuesAndClearsLegacyPreferences() {
        context.getSharedPreferences("child_protector", Context.MODE_PRIVATE)
            .edit()
            .putString("username", "legacy-parent")
            .putString("role", "PARENT")
            .putString("session", "legacy-token")
            .commit()

        val store = SessionStore(
            context,
            context.getSharedPreferences("test_secure_session", Context.MODE_PRIVATE)
        )

        assertEquals("legacy-parent", store.username)
        assertEquals("PARENT", store.role)
        assertEquals("legacy-token", store.session)
        assertEquals(
            0,
            context.getSharedPreferences("child_protector", Context.MODE_PRIVATE).all.size
        )
    }
}
