package app.babelecho.android.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import org.json.JSONArray
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
        asrKind = prefs.getString("asr_kind", "openai") ?: "openai",
        liveTranscription = prefs.getBoolean("live_transcription", false),
        autoMinutes = prefs.getBoolean("auto_minutes", true),
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
        asrPricePerHour = prefs.getString("asr_price_hour", "0")?.toDoubleOrNull() ?: 0.0,
        inputPricePerMillion = prefs.getString("input_price_million", "0")?.toDoubleOrNull() ?: 0.0,
        outputPricePerMillion = prefs.getString("output_price_million", "0")?.toDoubleOrNull() ?: 0.0,
        priceCurrency = prefs.getString("price_currency", "USD") ?: "USD",
        useAsrProfiles = prefs.getBoolean("use_asr_profiles", false),
        asrProfiles = readAsrProfiles(),
        asrRoutes = ProviderDefaults.routes().mapValues { (language, default) -> prefs.getString("asr_route_$language", default) ?: default },
        useTextProfiles = prefs.getBoolean("use_text_profiles", false),
        textProfiles = readTextProfiles(),
        textRoles = ProviderDefaults.roles().mapValues { (task, default) -> TextRole(prefs.getString("role_provider_$task", default.provider) ?: default.provider, prefs.getString("role_model_$task", default.model) ?: default.model) },
    )

    fun save(value: AppSettings) {
        require(value.chunkSeconds in 10..120) { "Chunk duration must be 10–120 seconds" }
        require(value.overlapSeconds in 0..10 && value.overlapSeconds < value.chunkSeconds) {
            "Overlap must be shorter than the chunk duration"
        }
        require(value.uiLanguage in setOf("zh", "ja", "en")) { "Unsupported interface language" }
        require(value.asrKind in setOf("openai", "mimo", "deepgram")) { "Unsupported ASR protocol" }
        require(listOf(value.asrPricePerHour, value.inputPricePerMillion, value.outputPricePerMillion).all { it.isFinite() && it >= 0 }) { "Prices must be finite and non-negative" }
        require(value.priceCurrency in setOf("USD", "CNY", "JPY")) { "Unsupported price currency" }
        require(value.autoSummarySeconds == 0 || value.autoSummarySeconds in 60..1_800) { "Auto summary must be off or 60–1800 seconds" }
        require(value.asrProfiles.map { it.id }.toSet() == ProviderDefaults.asr().map { it.id }.toSet()) { "Invalid ASR profiles" }
        require(value.textProfiles.map { it.id }.toSet() == ProviderDefaults.text().map { it.id }.toSet()) { "Invalid text profiles" }
        val editor = prefs.edit()
            .putString("ui_language", value.uiLanguage)
            .putString("asr_base_url", value.asrBaseUrl.trim().trimEnd('/'))
            .putString("asr_model", value.asrModel.trim())
            .putString("asr_language", value.asrLanguage.trim().ifBlank { "auto" })
            .putString("asr_key", encrypt(value.asrApiKey.trim()))
            .putBoolean("asr_no_auth", value.asrNoAuth)
            .putString("asr_kind", value.asrKind)
            .putBoolean("live_transcription", value.liveTranscription)
            .putBoolean("auto_minutes", value.autoMinutes)
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
            .putString("asr_price_hour", value.asrPricePerHour.toString())
            .putString("input_price_million", value.inputPricePerMillion.toString())
            .putString("output_price_million", value.outputPricePerMillion.toString())
            .putString("price_currency", value.priceCurrency)
        editor.putBoolean("use_asr_profiles", value.useAsrProfiles).putBoolean("use_text_profiles", value.useTextProfiles)
        editor.putString("asr_profiles", JSONArray().apply {
            value.asrProfiles.forEach { profile ->
                put(JSONObject().put("id", profile.id).put("kind", profile.kind).put("url", profile.baseUrl.trim().trimEnd('/'))
                    .put("model", profile.model.trim()).put("enabled", profile.enabled).put("noAuth", profile.noAuth))
                editor.putString("asr_profile_${profile.id}_key", encrypt(profile.apiKey.trim()))
            }
        }.toString())
        editor.putString("text_profiles", JSONArray().apply {
            value.textProfiles.forEach { profile ->
                put(JSONObject().put("id", profile.id).put("url", profile.baseUrl.trim().trimEnd('/')).put("enabled", profile.enabled).put("noAuth", profile.noAuth))
                editor.putString("text_profile_${profile.id}_key", encrypt(profile.apiKey.trim()))
            }
        }.toString())
        value.asrRoutes.forEach { (language, provider) -> editor.putString("asr_route_$language", provider) }
        value.textRoles.forEach { (task, role) -> editor.putString("role_provider_$task", role.provider).putString("role_model_$task", role.model.trim()) }
        editor.apply()
    }

    private fun readAsrProfiles(): List<AsrProfile> {
        val array = runCatching { JSONArray(prefs.getString("asr_profiles", "[]")) }.getOrDefault(JSONArray())
        return ProviderDefaults.asr().map { default ->
            val item = (0 until array.length()).map { array.getJSONObject(it) }.firstOrNull { it.optString("id") == default.id }
            if (item == null) default else default.copy(kind = item.optString("kind", default.kind), baseUrl = item.optString("url", default.baseUrl),
                model = item.optString("model", default.model), enabled = item.optBoolean("enabled"), noAuth = item.optBoolean("noAuth"), apiKey = decrypt(prefs.getString("asr_profile_${default.id}_key", null)))
        }
    }

    private fun readTextProfiles(): List<TextProfile> {
        val array = runCatching { JSONArray(prefs.getString("text_profiles", "[]")) }.getOrDefault(JSONArray())
        return ProviderDefaults.text().map { default ->
            val item = (0 until array.length()).map { array.getJSONObject(it) }.firstOrNull { it.optString("id") == default.id }
            if (item == null) default else default.copy(baseUrl = item.optString("url", default.baseUrl), enabled = item.optBoolean("enabled"),
                noAuth = item.optBoolean("noAuth"), apiKey = decrypt(prefs.getString("text_profile_${default.id}_key", null)))
        }
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
