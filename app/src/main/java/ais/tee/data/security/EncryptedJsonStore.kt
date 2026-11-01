package ais.tee.data.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small AndroidKeyStore-backed encrypted text primitive for local secret records.
 *
 * Callers own the record schema and use a dedicated preferences name + key alias per secret domain.
 * Keeping those identifiers outside this class lets existing stores retain their on-device format.
 */
internal class EncryptedJsonStore(
    context: Context,
    preferencesName: String,
    private val keyAlias: String,
    private val ivKey: String = DEFAULT_IV_KEY,
    private val ciphertextKey: String = DEFAULT_CIPHERTEXT_KEY,
) {
    private val preferences =
        context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    fun read(): String? = runCatching {
        val iv = preferences.getString(ivKey, null) ?: return null
        val ciphertext = preferences.getString(ciphertextKey, null) ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))
        )
        cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)).decodeToString()
    }.getOrNull()

    fun write(plaintext: String): Boolean = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(plaintext.encodeToByteArray())
        commitEncryptedRecord(preferences, ivKey, ciphertextKey,
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            Base64.encodeToString(ciphertext, Base64.NO_WRAP))
    }.getOrDefault(false)

    /** Distinguish unreadable ciphertext from an empty secret record. */
    fun hasStoredData(): Boolean = preferences.contains(ivKey) || preferences.contains(ciphertextKey)

    fun clear(): Boolean = runCatching {
        commitEncryptedRecord(preferences, ivKey, ciphertextKey, null, null)
    }.getOrDefault(false)

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generateKey()
        }
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val DEFAULT_IV_KEY = "iv"
        const val DEFAULT_CIPHERTEXT_KEY = "ciphertext"
    }
}

/** SharedPreferences applies memory changes even when its disk commit fails. Restore both fields
 * before reporting failure; a second failed disk write still restores the in-process map. */
internal fun commitEncryptedRecord(
    preferences: SharedPreferences, ivKey: String, ciphertextKey: String,
    iv: String?, ciphertext: String?,
): Boolean = synchronized(encryptedRecordLock) {
    // A malformed entry may have any supported preference type; reset must still remove it.
    val previous = preferences.all
    val previousIv = previous[ivKey]
    val previousCiphertext = previous[ciphertextKey]
    fun commit(nextIv: Any?, nextCiphertext: Any?): Boolean {
        val editor = preferences.edit()
        putRecordValue(editor, ivKey, nextIv)
        putRecordValue(editor, ciphertextKey, nextCiphertext)
        return editor.commit()
    }
    if (runCatching { commit(iv, ciphertext) }.getOrDefault(false)) true
    else {
        runCatching { commit(previousIv, previousCiphertext) }
        false
    }
}

private fun putRecordValue(editor: SharedPreferences.Editor, key: String, value: Any?) {
    when (value) {
        null -> editor.remove(key)
        is String -> editor.putString(key, value)
        is Int -> editor.putInt(key, value)
        is Long -> editor.putLong(key, value)
        is Float -> editor.putFloat(key, value)
        is Boolean -> editor.putBoolean(key, value)
        is Set<*> -> {
            require(value.all { it is String })
            editor.putStringSet(key, value.filterIsInstance<String>().toSet())
        }
        else -> error("Unsupported preference type")
    }
}

private val encryptedRecordLock = Any()
