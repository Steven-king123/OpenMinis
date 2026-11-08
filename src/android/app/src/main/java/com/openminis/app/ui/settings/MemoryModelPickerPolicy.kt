package com.openminis.app.ui.settings

import java.util.Locale

/** Local display/search policy only. A high rank never proves /embeddings compatibility. */
internal object MemoryModelPickerPolicy {
    data class Candidate(
        val entryId: String,
        val modelName: String,
        val modelId: String,
        val providerLabel: String,
        val providerName: String,
        val providerType: String,
        val providerId: String,
        val outputModalities: List<String>? = null,
    ) {
        // Build once per catalog change, not once per keystroke or visible row.
        internal val searchText = listOf(modelName, modelId, entryId, providerLabel,
            providerName, providerType, providerId).joinToString("\n").lowercase(Locale.ROOT)
        val likelyEmbedding: Boolean = isLikelyEmbedding(modelId, modelName, outputModalities)
    }

    fun search(candidates: List<Candidate>, query: String, embeddingFirst: Boolean): List<Candidate> {
        val tokens = query.trim().lowercase(Locale.ROOT).split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
        val matches = if (tokens.isEmpty()) candidates else candidates.filter { candidate ->
            tokens.all { it in candidate.searchText }
        }
        if (!embeddingFirst) return matches
        // Stable partition: preserve the catalog order in BOTH groups, dropping no compatible entry.
        val (recognized, remaining) = matches.partition { it.likelyEmbedding }
        return recognized + remaining
    }

    private val embeddingName = Regex(
        "(?:^|[^a-z0-9])(?:text[-_ ]embeddings?|embeddings?|embed|embeddinggemma)(?=$|[^a-z0-9]|v[0-9])"
    )
    private val familyName = Regex(
        "(?:^|[^a-z0-9])(?:bge[-_ ](?:small|base|large|m3|multilingual)|" +
            "e5[-_ ](?:small|base|large|mistral)|" +
            "gte[-_ ](?:small|base|large|multilingual|qwen[0-9]))(?=$|[^a-z0-9])"
    )
    private val rerankerName = Regex("(?:^|[^a-z0-9])rerank(?:er|ing)?(?=$|[^a-z0-9])")

    fun isLikelyEmbedding(id: String, name: String, outputModalities: List<String>?): Boolean {
        val modalities = outputModalities.orEmpty().map {
            it.trim().lowercase(Locale.ROOT).removeSuffix("_output")
        }
        if (modalities.any { it in setOf("embedding", "embeddings", "vector", "vectors") }) return true
        // Text-only metadata is common for embeddings and is not evidence against them.
        // Explicit dedicated media output IS meaningful; do not promote it on a name alone.
        if (modalities.any { it in setOf("image", "audio", "video", "speech") }) return false
        val names = listOf(id, name).map { it.lowercase(Locale.ROOT) }
        if (names.any { rerankerName.containsMatchIn(it) }) return false
        return names.any { embeddingName.containsMatchIn(it) || familyName.containsMatchIn(it) }
    }
}
