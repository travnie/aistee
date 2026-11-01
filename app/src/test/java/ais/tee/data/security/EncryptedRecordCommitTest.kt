package ais.tee.data.security

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EncryptedRecordCommitTest {
    @Test fun failedReplacementAndClearRestoreThePreviousPairEvenWhenRollbackCommitFails() {
        val real = RuntimeEnvironment.getApplication().getSharedPreferences("record-test", Context.MODE_PRIVATE)
        real.edit().putString(IV_KEY, OLD_IV).putString(CIPHERTEXT_KEY, OLD_CIPHERTEXT).commit()
        val failing = object : SharedPreferences by real {
            override fun edit(): SharedPreferences.Editor {
                val delegate = real.edit()
                return object : SharedPreferences.Editor by delegate {
                    override fun commit(): Boolean {
                        delegate.commit() // Android updates memory before reporting disk failure.
                        return false
                    }
                }
            }
        }
        assertFalse(commitEncryptedRecord(failing, IV_KEY, CIPHERTEXT_KEY, "new-iv", "new-ciphertext"))
        assertEquals(OLD_IV, real.getString(IV_KEY, null))
        assertEquals(OLD_CIPHERTEXT, real.getString(CIPHERTEXT_KEY, null))
        assertFalse(commitEncryptedRecord(failing, IV_KEY, CIPHERTEXT_KEY, null, null))
        assertEquals(OLD_IV, real.getString(IV_KEY, null))
        assertEquals(OLD_CIPHERTEXT, real.getString(CIPHERTEXT_KEY, null))
        assertTrue(commitEncryptedRecord(real, IV_KEY, CIPHERTEXT_KEY, null, null))
        assertFalse(real.contains(IV_KEY))
        assertFalse(real.contains(CIPHERTEXT_KEY))
    }
}

private const val IV_KEY = "iv"
private const val CIPHERTEXT_KEY = "ciphertext"
private const val OLD_IV = "old-iv"
private const val OLD_CIPHERTEXT = "old-ciphertext"
