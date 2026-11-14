package com.mediahub.mobile.mail

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class MailAccount(val email: String, val password: String)

/** Password and email stored as AES-GCM ciphertext. Key stays in Android Keystore. */
class SecureMailAccountStore(context: Context) {
    private val prefs = context.getSharedPreferences("mediahub_mail_secured", Context.MODE_PRIVATE)
    private val alias = "mediahub.mail.webdav.aes.v1"

    private fun getKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build()
        )
        return generator.generateKey()
    }

    fun save(email: String, password: String) {
        require(email.isNotBlank() && !email.contains('\n') && password.isNotBlank())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getKey())
        val encrypted = cipher.doFinal("$email\n$password".toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString("payload", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    fun load(): MailAccount? {
        val data = prefs.getString("payload", null) ?: return null
        val iv = prefs.getString("iv", null) ?: return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getKey(), GCMParameterSpec(
                128, Base64.decode(iv, Base64.NO_WRAP)
            ))
            val raw = String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8)
            val index = raw.indexOf('\n')
            if (index <= 0) null else MailAccount(raw.substring(0, index), raw.substring(index + 1))
        } catch (_: Exception) {
            // Keystore keys may be invalidated on device reset / restored preferences.
            clear()
            null
        }
    }

    fun clear() {
        prefs.edit().remove("payload").remove("iv").apply()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
            deleteEntry(alias)
        } }
    }
}
