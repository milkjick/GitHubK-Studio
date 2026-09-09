package com.example.myempty.githubk.core

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
 * SecureStore v2.5
 * 基于 AndroidKeyStore AES/GCM 加密。所有编解码显式使用 UTF-8，避免跨环境编码问题。
 */
class SecureStore(private val c: Context) {

    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val prefs = c.getSharedPreferences("secure_store", Context.MODE_PRIVATE)

    private fun getOrCreateKey(): SecretKey {
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    fun put(key: String, value: String) {
        if (value.isEmpty()) { prefs.edit().remove(key).apply(); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val bytes = value.toByteArray(Charsets.UTF_8)
        val encrypted = cipher.doFinal(bytes)
        val iv = cipher.iv
        val box = iv + encrypted
        prefs.edit().putString(key, Base64.encodeToString(box, Base64.NO_WRAP)).apply()
    }

    fun get(key: String): String? {
        val raw = prefs.getString(key, null) ?: return null
        return try {
            val box = Base64.decode(raw, Base64.NO_WRAP)
            val iv = box.copyOfRange(0, 12)
            val data = box.copyOfRange(12, box.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(data), Charsets.UTF_8)
        } catch (_: Throwable) { null }
    }

    fun remove(key: String) = prefs.edit().remove(key).apply()

    companion object {
        private const val ALIAS = "githubk_master_key"
    }
}
