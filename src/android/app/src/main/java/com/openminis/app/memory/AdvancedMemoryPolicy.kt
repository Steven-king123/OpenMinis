package com.openminis.app.memory

import java.text.Normalizer
import java.util.Locale
import kotlin.math.sqrt

enum class MemoryKind { CORE, EPISODE }
data class MemoryRecord(
    val id: String, val kind: MemoryKind, val content: String, val revision: Long,
    val updatedAt: Long, val sourceSessionId: String? = null, val manual: Boolean = false,
    val deleted: Boolean = false, val embeddingModel: String? = null,
    val vector: List<Float> = emptyList(),
    val sourceVersion: String? = null,
)
data class MemoryHit(
    val record: MemoryRecord, val score: Double, val semantic: Double?,
    val lexical: Double, val reason: String, val accepted: Boolean,
)
data class AdvancedMemoryConfig(
    val enabled: Boolean = false, val summarizerEntryId: String = "",
    val embeddingEntryId: String = "", val threshold: Float = 0.55f,
    val maxResults: Int = 5, val autoConsolidate: Boolean = false,
    val idleMinutes: Int = 30, val epoch: Long = 0,
)

/** Pure, deterministic policy. No clocks, Android classes, model calls, or recency boost. */
object AdvancedMemoryPolicy {
    const val MAX_RECORDS = 2_000
    const val MAX_CONTENT_CHARS = 8_192
    const val MAX_QUERY_CHARS = 2_048
    const val MAX_ID_CHARS = 256
    const val MAX_MODEL_CHARS = 1_024
    const val MAX_VECTOR_DIMENSION = 4_096
    const val MAX_TOTAL_CONTENT_CHARS = 4_000_000
    const val MAX_TOTAL_VECTOR_VALUES = 512_000
    const val MAX_FILE_BYTES = 16 * 1024 * 1024
    const val MAX_DEBUG_CANDIDATES = 100

    fun validateConfig(value: AdvancedMemoryConfig) {
        require(value.threshold.isFinite() && value.threshold in 0f..1f) { "Threshold must be 0..1" }
        require(value.maxResults in 1..20) { "maxResults must be 1..20" }
        require(value.idleMinutes in 1..240) { "idleMinutes must be 1..240" }
        require(value.epoch >= 0) { "Invalid epoch" }
        validateId(value.summarizerEntryId, allowEmpty = true)
        validateId(value.embeddingEntryId, allowEmpty = true)
    }

    fun validateId(value: String, allowEmpty: Boolean = false) {
        require(value.length <= MAX_ID_CHARS && (allowEmpty && value.isEmpty() || value.isNotBlank())) { "Invalid identifier" }
        require(value.none { it.isISOControl() }) { "Control character in identifier" }
    }

    fun validateContent(value: String) {
        require(value.isNotBlank() && value.length <= MAX_CONTENT_CHARS) { "Memory must contain 1..$MAX_CONTENT_CHARS characters" }
        require('\u0000' !in value) { "NUL is not allowed in memory" }
    }

    fun validateModel(value: String) {
        require(value.isNotBlank() && value.length <= MAX_MODEL_CHARS && value.none { it.isISOControl() }) { "Invalid model fingerprint" }
    }

    fun validVector(vector: List<Float>): Boolean = vector.isNotEmpty() &&
        vector.size <= MAX_VECTOR_DIMENSION && vector.all { it.isFinite() } &&
        vector.any { it != 0f }

    fun validateVector(vector: List<Float>) {
        require(validVector(vector)) { "Embedding must be finite, nonzero, dimension 1..$MAX_VECTOR_DIMENSION" }
    }

    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).trim().replace(Regex("\\s+"), " ")

    /** Han unigrams preserve single-character searches; bigrams carry three times their weight. */
    fun tokens(value: String): Set<String> {
        val normalized = normalize(value.take(MAX_CONTENT_CHARS))
        val result = linkedSetOf<String>()
        val word = StringBuilder()
        var previousHan: String? = null
        fun flush() { if (word.isNotEmpty()) { result.add("w:$word"); word.setLength(0) } }
        var offset = 0
        while (offset < normalized.length) {
            val cp = normalized.codePointAt(offset)
            val character = String(Character.toChars(cp))
            if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN) {
                flush()
                result.add("h:$character")
                previousHan?.let { result.add("b:$it$character") }
                previousHan = character
            } else {
                previousHan = null
                if (Character.isLetterOrDigit(cp) || Character.getType(cp) == Character.NON_SPACING_MARK.toInt()) {
                    word.append(character)
                } else flush()
            }
            offset += Character.charCount(cp)
        }
        flush()
        return result
    }

    private fun weight(token: String): Double = if (token.startsWith("b:")) 3.0 else 1.0
    private fun lexical(query: Set<String>, document: Set<String>): Double {
        if (query.isEmpty() || document.isEmpty()) return 0.0
        val shared = query.filter { it in document }.sumOf(::weight)
        if (shared == 0.0) return 0.0
        val coverage = shared / query.sumOf(::weight)
        val union = query.sumOf(::weight) + document.sumOf(::weight) - shared
        return (0.85 * coverage + 0.15 * shared / union).coerceIn(0.0, 1.0)
    }

    fun lexicalScore(query: String, content: String): Double = lexical(tokens(query), tokens(content))

    /** Double accumulation prevents overflow for finite Float.MAX_VALUE components. */
    fun semanticScore(left: List<Float>, right: List<Float>): Double? {
        if (left.size != right.size || !validVector(left) || !validVector(right)) return null
        var dot = 0.0
        var leftNorm = 0.0
        var rightNorm = 0.0
        for (i in left.indices) {
            val a = left[i].toDouble(); val b = right[i].toDouble()
            dot += a * b; leftNorm += a * a; rightNorm += b * b
        }
        val cosine = dot / (sqrt(leftNorm) * sqrt(rightNorm))
        return if (cosine.isFinite()) cosine.coerceIn(0.0, 1.0) else null
    }

    /** Accepted hits are capped by limit. Up to 100 ranked candidates remain visible for debugging. */
    fun search(
        records: List<MemoryRecord>, query: String, queryVector: List<Float>?, model: String?,
        limit: Int = 5, threshold: Float = 0.55f,
    ): List<MemoryHit> {
        require(query.length <= MAX_QUERY_CHARS) { "Query too long" }
        require(limit in 1..20) { "limit must be 1..20" }
        require(threshold.isFinite() && threshold in 0f..1f) { "Invalid threshold" }
        if (queryVector != null) validateVector(queryVector)
        if (model != null) validateModel(model)
        require(queryVector == null || model != null) { "Query embedding requires a model fingerprint" }
        val queryTokens = tokens(query)
        if (queryTokens.isEmpty()) return emptyList()
        require(records.size <= MAX_RECORDS) { "Too many memory records" }
        val candidates = records.asSequence().filter { !it.deleted }
            .map { record ->
                val lex = lexical(queryTokens, tokens(record.content))
                val semantic = if (queryVector != null && record.embeddingModel == model)
                    semanticScore(queryVector, record.vector) else null
                val score = maxOf(lex, if (semantic != null) 0.9 * semantic + 0.1 * lex else 0.0).coerceIn(0.0, 1.0)
                val relevant = score > 0.0 && score >= threshold.toDouble()
                val reason = when {
                    !relevant -> if (score == 0.0) "no lexical or semantic evidence" else "below threshold"
                    semantic == null && queryVector != null -> "lexical; embedding model/dimension unavailable"
                    semantic == null -> "lexical match"
                    else -> "hybrid lexical/semantic match"
                }
                MemoryHit(record, score, semantic, lex, reason, relevant)
            }.sortedWith(compareByDescending<MemoryHit> { it.score }.thenBy { it.record.id })
            .toList()
        val seen = hashSetOf<String>()
        var accepted = 0
        return candidates.take(MAX_DEBUG_CANDIDATES).map { hit ->
            val duplicate = !seen.add(normalize(hit.record.content))
            when {
                duplicate -> hit.copy(accepted = false, reason = "duplicate content")
                !hit.accepted -> hit
                accepted >= limit -> hit.copy(accepted = false, reason = "maxResults reached")
                else -> { accepted++; hit }
            }
        }
    }
}
