package app.babelecho.android.data

import org.json.JSONArray
import org.json.JSONObject

/** Non-secret provider metadata. Keys remain separate encrypted preferences. */
object ProviderJson {
    fun asr(profiles: List<AsrProfile>) = JSONArray().apply { profiles.forEach { p ->
        put(JSONObject().put("id", p.id).put("kind", p.kind).put("url", p.baseUrl.trim().trimEnd('/'))
            .put("model", p.model.trim()).put("enabled", p.enabled).put("noAuth", p.noAuth)
            .put("label", p.label.trim()).put("languages", JSONArray(p.languages.toList())))
    } }.toString()
    fun text(profiles: List<TextProfile>) = JSONArray().apply { profiles.forEach { p ->
        put(JSONObject().put("id", p.id).put("url", p.baseUrl.trim().trimEnd('/')).put("enabled", p.enabled)
            .put("noAuth", p.noAuth).put("label", p.label.trim()))
    } }.toString()

    fun readAsr(raw: String, key: (String) -> String): List<AsrProfile> {
        val array = JSONArray(raw)
        return (0 until array.length()).map { i ->
            val item = array.getJSONObject(i); val id = item.getString("id")
            val default = ProviderDefaults.asr().firstOrNull { it.id == id } ?: AsrProfile(id, "openai", "", "")
            val languages = item.optJSONArray("languages")?.let { values -> (0 until values.length()).map { values.getString(it) }.toSet() } ?: default.languages
            default.copy(kind = item.optString("kind", default.kind), baseUrl = item.optString("url", default.baseUrl),
                model = item.optString("model", default.model), enabled = item.optBoolean("enabled"), noAuth = item.optBoolean("noAuth"),
                label = item.optString("label"), languages = languages, apiKey = key(id))
        }
    }
    fun readText(raw: String, key: (String) -> String): List<TextProfile> {
        val array = JSONArray(raw)
        return (0 until array.length()).map { i ->
            val item = array.getJSONObject(i); val id = item.getString("id")
            val default = ProviderDefaults.text().firstOrNull { it.id == id } ?: TextProfile(id, "")
            default.copy(baseUrl = item.optString("url", default.baseUrl), enabled = item.optBoolean("enabled"),
                noAuth = item.optBoolean("noAuth"), label = item.optString("label"), apiKey = key(id))
        }
    }
}
