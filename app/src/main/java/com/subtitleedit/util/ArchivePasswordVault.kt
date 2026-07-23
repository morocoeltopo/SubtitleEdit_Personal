package com.subtitleedit.util

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray

class ArchivePasswordVault(context: Context) {

    private val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getPasswords(): List<String> {
        val encrypted = preferences.getString(KEY_PASSWORDS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(decrypt(encrypted))
            buildList {
                for (index in 0 until array.length()) {
                    array.optString(index).takeIf { it.isNotEmpty() }?.let(::add)
                }
            }.distinct().take(MAX_PASSWORDS)
        }.getOrElse {
            preferences.edit().remove(KEY_PASSWORDS).apply()
            emptyList()
        }
    }

    fun savePassword(password: String) {
        if (password.isEmpty()) return
        val updated = (listOf(password) + getPasswords().filterNot { it == password }).take(MAX_PASSWORDS)
        val array = JSONArray()
        updated.forEach(array::put)
        preferences.edit().putString(KEY_PASSWORDS, encrypt(array.toString())).apply()
    }

    fun clear() {
        preferences.edit().remove(KEY_PASSWORDS).apply()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val payload = ByteBuffer.allocate(1 + cipher.iv.size + encrypted.size)
            .put(cipher.iv.size.toByte())
            .put(cipher.iv)
            .put(encrypted)
            .array()
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val payload = ByteBuffer.wrap(Base64.decode(value, Base64.NO_WRAP))
        val ivSize = payload.get().toInt() and 0xff
        require(ivSize in 12..16 && payload.remaining() > ivSize) { "密码本数据无效" }
        val iv = ByteArray(ivSize).also(payload::get)
        val encrypted = ByteArray(payload.remaining()).also(payload::get)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted).toString(Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS_NAME = "archive_password_vault"
        const val KEY_PASSWORDS = "passwords"
        const val KEY_ALIAS = "subtitleedit_archive_passwords"
        const val KEY_STORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val MAX_PASSWORDS = 20
    }
}
