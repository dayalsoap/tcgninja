package com.tcgninja.app.integration

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Only non-secret provider/model preferences go in SharedPreferences. */
class ProviderSettings(context: Context) {
    internal val context: Context = context.applicationContext
    private val preferences = this.context.getSharedPreferences("provider_settings", Context.MODE_PRIVATE)

    var provider: String
        get() = preferences.getString("provider", OPENAI) ?: OPENAI
        set(value) { requireProvider(value); preferences.edit().putString("provider", value).apply() }
    var openAiModel: String
        get() = preferences.getString("openai_model", "gpt-4.1-mini") ?: "gpt-4.1-mini"
        set(value) { preferences.edit().putString("openai_model", validateModel(value)).apply() }
    var anthropicModel: String
        get() = preferences.getString("anthropic_model", "claude-sonnet-4-6") ?: "claude-sonnet-4-6"
        set(value) { preferences.edit().putString("anthropic_model", validateModel(value)).apply() }

    fun getKey(provider: String): String = synchronized(keyLock) {
        requireProvider(provider)
        try {
            val file = secretFile(provider)
            if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return@synchronized ""
            val bytes = file.openRead().use { it.readBytes() }
            if (bytes.size !in 30..8192 || bytes[0] != 1.toByte()) throw IllegalStateException()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(create = false), GCMParameterSpec(128, bytes, 1, 12))
            cipher.updateAAD(provider.toByteArray(Charsets.UTF_8))
            String(cipher.doFinal(bytes, 13, bytes.size - 13), Charsets.UTF_8)
        } catch (_: Exception) {
            throw IntegrationException(IntegrationException.Kind.SETTINGS,
                "The saved API key could not be unlocked. Re-enter it in Settings on this device.")
        }
    }

    /** Passing an empty key disconnects the provider. Ciphertexts are excluded from Android backup. */
    fun setKey(provider: String, key: String) = synchronized(keyLock) {
        requireProvider(provider)
        val value = key.trim()
        if (value.length > 4096 || value.any { it.code !in 33..126 }) throw IntegrationException(
            IntegrationException.Kind.SETTINGS, "Enter a valid API key without spaces or line breaks."
        )
        try {
            val file = secretFile(provider)
            if (value.isEmpty()) {
                file.delete()
            } else {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, secretKey(create = true))
                cipher.updateAAD(provider.toByteArray(Charsets.UTF_8))
                val encrypted = byteArrayOf(1) + cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
                val stream = file.startWrite()
                try {
                    stream.write(encrypted)
                    file.finishWrite(stream)
                } catch (e: Exception) {
                    file.failWrite(stream)
                    throw e
                }
            }
        } catch (_: Exception) {
            throw IntegrationException(IntegrationException.Kind.SETTINGS,
                "Could not save the API key securely on this device. Try again.")
        }
    }

    private fun secretFile(provider: String): AtomicFile = AtomicFile(
        File(context.noBackupFilesDir, if (provider == OPENAI) "openai-key.aes" else "anthropic-key.aes")
    )

    private fun secretKey(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        if (!create) throw IllegalStateException()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
        }.generateKey()
    }

    private fun validateModel(value: String): String = value.trim().also {
        if (!Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}").matches(it)) throw IntegrationException(
            IntegrationException.Kind.SETTINGS, "Enter the model ID from the provider's documentation."
        )
    }

    companion object {
        const val OPENAI = "OpenAI"
        const val ANTHROPIC = "Anthropic"
        private const val KEY_ALIAS = "tcgninja.provider-keys.v1"
        private val keyLock = Any()
        private fun requireProvider(provider: String) {
            if (provider != OPENAI && provider != ANTHROPIC) throw IntegrationException(
                IntegrationException.Kind.UNSUPPORTED,
                "Choose OpenAI or Anthropic. Microsoft Copilot does not have a supported integration yet."
            )
        }
    }
}
