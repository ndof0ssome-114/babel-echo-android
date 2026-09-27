package app.babelecho.android.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): AppSettings = AppSettings(
        uiLanguage = prefs.getString("ui_language", null) ?: "zh",
        asrBaseUrl = prefs.getString("asr_base_url", null) ?: AppSettings().asrBaseUrl,
        asrModel = prefs.getString("asr_model", null) ?: AppSettings().asrModel,
        asrLanguage = prefs.getString("asr_language", null) ?: AppSettings().asrLanguage,
        asrApiKey = decrypt(prefs.getString("asr_key", null)),
        asrNoAuth = prefs.getBoolean("asr_no_auth", false),
        llmBaseUrl = prefs.getString("llm_base_url", null) ?: AppSettings().llmBaseUrl,
        llmModel = prefs.getString("llm_model", null) ?: AppSettings().llmModel,
        summaryModel = prefs.getString("summary_model", null) ?: prefs.getString("llm_model", null) ?: AppSettings().summaryModel,
        minutesModel = prefs.getString("minutes_model", null) ?: prefs.getString("llm_model", null) ?: AppSettings().minutesModel,
        translateModel = prefs.getString("translate_model", null) ?: prefs.getString("llm_model", null) ?: AppSettings().translateModel,
        askModel = prefs.getString("ask_model", null) ?: prefs.getString("llm_model", null) ?: AppSettings().askModel,
        llmApiKey = decrypt(prefs.getString("llm_key", null)),
        llmNoAuth = prefs.getBoolean("llm_no_auth", false),
        chunkSeconds = prefs.getInt("chunk_seconds", 25).coerceIn(10, 120),
        overlapSeconds = prefs.getInt("overlap_seconds", 2).coerceIn(0, 10),
        autoSummarySeconds = prefs.getInt("auto_summary_seconds", 180).let { if (it == 0) 0 else it.coerceIn(60, 1_800) },
        translateTo = prefs.getString("translate_to", null) ?: "",
        allowInsecureHttp = prefs.getBoolean("allow_insecure_http", false),
    )

    fun save(value: AppSettings) {
        require(value.chunkSeconds in 10..120) { "Chunk duration must be 10–120 seconds" }
        require(value.overlapSeconds in 0..10 && value.overlapSeconds < value.chunkSeconds) {
            "Overlap must be shorter than the chunk duration"
        }
        require(value.uiLanguage in setOf("zh", "ja", "en")) { "Unsupported interface language" }
        require(value.autoSummarySeconds == 0 || value.autoSummarySeconds in 60..1_800) { "Auto summary must be off or 60–1800 seconds" }
        prefs.edit()
            .putString("ui_language", value.uiLanguage)
            .putString("asr_base_url", value.asrBaseUrl.trim().trimEnd('/'))
            .putString("asr_model", value.asrModel.trim())
            .putString("asr_language", value.asrLanguage.trim().ifBlank { "auto" })
            .putString("asr_key", encrypt(value.asrApiKey.trim()))
            .putBoolean("asr_no_auth", value.asrNoAuth)
            .putString("llm_base_url", value.llmBaseUrl.trim().trimEnd('/'))
            .putString("llm_model", value.llmModel.trim())
            .putString("summary_model", value.summaryModel.trim())
            .putString("minutes_model", value.minutesModel.trim())
            .putString("translate_model", value.translateModel.trim())
            .putString("ask_model", value.askModel.trim())
            .putString("llm_key", encrypt(value.llmApiKey.trim()))
            .putBoolean("llm_no_auth", value.llmNoAuth)
            .putInt("chunk_seconds", value.chunkSeconds)
            .putInt("overlap_seconds", value.overlapSeconds)
            .putInt("auto_summary_seconds", value.autoSummarySeconds)
            .putString("translate_to", value.translateTo)
            .putBoolean("allow_insecure_http", value.allowInsecureHttp)
            .apply()
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private fun encrypt(plain: String): String {
        if (plain.isBlank()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val packed = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String?): String {
        if (encoded.isNullOrBlank()) return ""
        return runCatching {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, packed.copyOfRange(0, 12)))
            String(cipher.doFinal(packed.copyOfRange(12, packed.size)), Charsets.UTF_8)
        }.getOrDefault("")
    }

    companion object {
        private const val KEY_ALIAS = "babel_echo_api_keys_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
