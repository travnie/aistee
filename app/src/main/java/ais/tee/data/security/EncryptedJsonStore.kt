package ais.tee.data.security

import android.content.Context
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
        preferences.edit()
            .putString(ivKey, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(ciphertextKey, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .commit()
    }.getOrDefault(false)

    fun clear(): Boolean = runCatching {
        preferences.edit()
            .remove(ivKey)
            .remove(ciphertextKey)
            .commit()
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
