package com.raju.aiassistant.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.raju.aiassistant.domain.model.SecretId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores user API keys encrypted with AES-256-GCM. The AES key lives inside the
 * Android Keystore (hardware-backed where the device supports it) and is never
 * exportable. Only ciphertext + IV are written to app-private SharedPreferences.
 *
 * Secrets are never logged.
 */
class SecureKeyStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _configured = MutableStateFlow(readConfiguredIds())

    /** Which secrets currently have a successfully saved value. */
    val configured: StateFlow<Set<SecretId>> = _configured.asStateFlow()

    /** Encrypts and saves [value]. Returns true only if the write was committed. */
    suspend fun save(id: SecretId, value: String): Boolean = withContext(Dispatchers.IO) {
        val trimmed = value.trim()
        require(trimmed.isNotEmpty()) { "Secret must not be empty" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8))
        val stored = encode(cipher.iv) + SEPARATOR + encode(encrypted)
        val ok = prefs.edit().putString(id.storageKey, stored).commit()
        _configured.value = readConfiguredIds()
        ok && read(id) == trimmed
    }

    /** Decrypts the secret, or returns null if absent or unreadable (e.g. key invalidated). */
    suspend fun get(id: SecretId): String? = withContext(Dispatchers.IO) { read(id) }

    suspend fun remove(id: SecretId): Boolean = withContext(Dispatchers.IO) {
        val ok = prefs.edit().remove(id.storageKey).commit()
        _configured.value = readConfiguredIds()
        ok
    }

    private fun read(id: SecretId): String? {
        val stored = prefs.getString(id.storageKey, null) ?: return null
        val parts = stored.split(SEPARATOR)
        if (parts.size != 2) return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, decode(parts[0])))
            String(cipher.doFinal(decode(parts[1])), Charsets.UTF_8)
        } catch (e: Exception) {
            // Keystore key lost or data corrupted: treat as not configured; user must re-enter.
            null
        }
    }

    private fun readConfiguredIds(): Set<SecretId> =
        SecretId.entries.filter { prefs.contains(it.storageKey) }.toSet()

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(text: String) = Base64.decode(text, Base64.NO_WRAP)

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "raju_ai_secret_master_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val PREFS_NAME = "raju_secure_store"
        const val SEPARATOR = ":"
    }
}
