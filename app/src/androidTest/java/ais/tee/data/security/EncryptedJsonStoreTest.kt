package ais.tee.data.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.KeyStore
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EncryptedJsonStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val suffix = UUID.randomUUID().toString()
    private val preferencesName = "encrypted-json-test-$suffix"
    private val keyAlias = "encrypted-json-test-key-$suffix"
    private val store = EncryptedJsonStore(context, preferencesName, keyAlias)

    @After
    fun cleanUp() {
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).edit().clear().commit()
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                if (containsAlias(keyAlias)) deleteEntry(keyAlias)
            }
        }
    }

    @Test
    fun roundTripsReplacesAndClearsEncryptedRecord() {
        assertNull(store.read())

        val first = """{"secret":"one"}"""
        assertTrue(store.write(first))
        assertEquals(first, store.read())
        assertFalse(
            context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
                .all.values.any { value -> value?.toString()?.contains("secret") == true }
        )

        val second = """{"secret":"two","refresh":"rotated"}"""
        assertTrue(store.write(second))
        assertEquals(second, store.read())

        assertTrue(store.clear())
        assertNull(store.read())
    }
}
