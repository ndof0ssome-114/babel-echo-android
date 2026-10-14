package app.babelecho.android.data

/** Upgrade the legacy active endpoints in memory without rerouting requests or overwriting inactive credentials. */
object ProviderMigration {
    fun upgrade(original: AppSettings, fresh: Boolean = false): AppSettings {
        var value = original
        if (!value.useAsrProfiles) {
            val match = value.asrProfiles.firstOrNull { it.baseUrl.trimEnd('/') == value.asrBaseUrl.trimEnd('/') && it.kind == value.asrKind }
            val reusable = match?.takeIf { it == ProviderDefaults.asr().firstOrNull { preset -> preset.id == it.id } ||
                it.apiKey == value.asrApiKey && it.model == value.asrModel && it.noAuth == value.asrNoAuth }
            val id = reusable?.id ?: unique("legacy-asr", value.asrProfiles.map { it.id })
            val profile = AsrProfile(id, value.asrKind, value.asrBaseUrl, value.asrModel, value.hasAsr,
                value.asrApiKey, value.asrNoAuth, if (value.asrKind == "mimo") setOf("zh", "en") else ProviderDefaults.languages.toSet(),
                reusable?.label ?: "")
            value = value.copy(useAsrProfiles = true,
                asrProfiles = if (reusable == null) value.asrProfiles + profile else value.asrProfiles.map { if (it.id == id) profile else it },
                asrRoutes = (ProviderDefaults.languages + value.asrLanguage).associateWith { id })
        }
        if (!value.useTextProfiles) {
            val match = value.textProfiles.firstOrNull { it.baseUrl.trimEnd('/') == value.llmBaseUrl.trimEnd('/') }
            val reusable = match?.takeIf { it == ProviderDefaults.text().firstOrNull { preset -> preset.id == it.id } ||
                it.apiKey == value.llmApiKey && it.noAuth == value.llmNoAuth }
            val id = reusable?.id ?: unique("legacy-text", value.textProfiles.map { it.id })
            val profile = TextProfile(id, value.llmBaseUrl, value.hasText("summary") || value.hasText("minutes") || value.hasText("ask"),
                value.llmApiKey, value.llmNoAuth, reusable?.label ?: "")
            val roles = ProviderDefaults.tasks.associateWith { task ->
                if (fresh) TextModelPolicy.initial(profile, task) else {
                    val model = when (task) { "summary" -> value.summaryModel; "minutes" -> value.minutesModel; "translation" -> value.translateModel; else -> value.askModel }
                    TextRole(id, model.ifBlank { value.llmModel }, value.textThinking[task])
                }
            }
            value = value.copy(useTextProfiles = true,
                textProfiles = if (reusable == null) value.textProfiles + profile else value.textProfiles.map { if (it.id == id) profile else it }, textRoles = roles)
        }
        return value
    }

    private fun unique(base: String, ids: List<String>): String {
        var id = base; var suffix = 1
        while (id in ids) { id = "$base-${suffix++}" }
        return id
    }
}
