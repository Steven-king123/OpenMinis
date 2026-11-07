package com.openminis.app.memory

import org.junit.Assert.*
import org.junit.Test

class AdvancedMemoryPolicyTest {
    private fun record(id: String, text: String, model: String? = null, vector: List<Float> = emptyList()) =
        MemoryRecord(id, MemoryKind.CORE, text, 1, 1, embeddingModel = model, vector = vector)
    private fun search(records: List<MemoryRecord>, query: String, vector: List<Float>? = null,
        model: String? = null, limit: Int = 5, threshold: Float = 0.55f) =
        AdvancedMemoryPolicy.search(records, query, vector, model, limit, threshold)

    @Test fun chineseBigramsFindRelevantMemoryWithoutWhitespace() {
        val hits = search(listOf(record("a", "用户喜欢喝茉莉花茶"), record("b", "用户乘坐地铁上班")), "茉莉花茶")
        assertTrue(hits.first().accepted)
        assertEquals("a", hits.first().record.id)
        assertFalse(hits.last().accepted)
        assertEquals(0.0, hits.last().score, 0.0)
    }
    @Test fun unicodeNormalizationAndSupplementaryHan() {
        assertEquals(1.0, AdvancedMemoryPolicy.lexicalScore("ＣＡＦÉ", "café"), 0.0)
        assertTrue("b:𠀀茶" in AdvancedMemoryPolicy.tokens("𠀀茶"))
        assertTrue(AdvancedMemoryPolicy.lexicalScore("茶", "爱喝茶") > 0.55)
    }
    @Test fun unicodeWordsRequireWholeTokenNotSubstring() {
        assertEquals(0.0, AdvancedMemoryPolicy.lexicalScore("cat", "education"), 0.0)
        assertEquals(1.0, AdvancedMemoryPolicy.lexicalScore("Привет", "ПРИВЕТ"), 0.0)
    }
    @Test fun noUnrelatedFallbackEvenWithZeroThresholdOrRecency() {
        val hit = search(listOf(record("a", "pottery").copy(updatedAt = Long.MAX_VALUE)), "volcano", threshold = 0f).single()
        assertFalse(hit.accepted)
        assertEquals(0.0, hit.score, 0.0)
        assertTrue(hit.reason.contains("no lexical"))
    }
    @Test fun blankAndPunctuationQueriesRecallNothing() {
        assertTrue(search(listOf(record("a", "tea")), "  ").isEmpty())
        assertTrue(search(listOf(record("a", "tea")), "!!!").isEmpty())
    }
    @Test fun semanticOnlyCanRecallWithExplicitMatchingModel() {
        val hit = search(listOf(record("a", "automobile", "model", listOf(1f, 0f))),
            "vehicle", listOf(1f, 0f), "model").single()
        assertTrue(hit.accepted)
        assertEquals(1.0, hit.semantic!!, 0.0)
        assertEquals(0.9, hit.score, 1e-9)
    }
    @Test fun mismatchedModelOrDimensionCannotContribute() {
        for (record in listOf(record("a", "automobile", "old", listOf(1f, 0f)),
            record("b", "automobile", "new", listOf(1f)))) {
            val hit = search(listOf(record), "vehicle", listOf(1f, 0f), "new").single()
            assertNull(hit.semantic)
            assertFalse(hit.accepted)
        }
    }
    @Test fun cosineIsFiniteAndBoundedForExtremeFloats() {
        assertEquals(1.0, AdvancedMemoryPolicy.semanticScore(listOf(Float.MAX_VALUE), listOf(Float.MAX_VALUE))!!, 1e-12)
        assertEquals(0.0, AdvancedMemoryPolicy.semanticScore(listOf(1f), listOf(-1f))!!, 0.0)
        assertEquals(0.0, AdvancedMemoryPolicy.semanticScore(listOf(1f, 0f), listOf(0f, 1f))!!, 0.0)
        assertNull(AdvancedMemoryPolicy.semanticScore(listOf(0f), listOf(1f)))
        assertNull(AdvancedMemoryPolicy.semanticScore(listOf(Float.NaN), listOf(1f)))
    }
    @Test fun acceptedLimitAndRejectedDebuggerCandidatesAreSeparate() {
        val hits = search((1..10).map { record("$it", "tea recipe $it") }, "tea", limit = 2)
        assertEquals(10, hits.size)
        assertEquals(2, hits.count { it.accepted })
        assertEquals(8, hits.count { it.reason == "maxResults reached" })
    }
    @Test fun strictThresholdDoesNotForceTopCandidate() {
        val hits = search(listOf(record("a", "tea coffee")), "tea coffee water", threshold = 0.99f)
        assertFalse(hits.single().accepted)
    }
    @Test fun deduplicatesNormalizedContentAndOmitsTombstones() {
        val hits = search(listOf(record("a", "Tea"), record("b", "Ｔｅａ"),
            record("c", "").copy(deleted = true)), "tea")
        assertEquals(2, hits.size)
        assertEquals(1, hits.count { it.accepted })
        assertEquals("duplicate content", hits.last().reason)
    }
    @Test fun candidatesHaveHardBound() {
        val hits = search((1..2000).map { record("$it", "tea $it") }, "tea")
        assertEquals(100, hits.size)
        assertEquals(5, hits.count { it.accepted })
        assertTrue(hits.all { it.score.isFinite() && it.score in 0.0..1.0 && it.lexical in 0.0..1.0 })
    }
    @Test fun invalidVectorsRejected() {
        for (vector in listOf(emptyList(), listOf(0f), listOf(Float.NaN), listOf(Float.POSITIVE_INFINITY),
            List(4097) { 1f })) {
            assertThrows(IllegalArgumentException::class.java) { AdvancedMemoryPolicy.validateVector(vector) }
        }
    }
    @Test fun invalidQueriesAndControlsRejected() {
        assertThrows(IllegalArgumentException::class.java) { search(emptyList(), "x".repeat(2049)) }
        assertThrows(IllegalArgumentException::class.java) { search(emptyList(), "tea", limit = 0) }
        assertThrows(IllegalArgumentException::class.java) { search(emptyList(), "tea", threshold = Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { search(emptyList(), "tea", listOf(1f), null) }
        assertThrows(IllegalArgumentException::class.java) { AdvancedMemoryPolicy.validateContent(" ") }
        assertThrows(IllegalArgumentException::class.java) { AdvancedMemoryPolicy.validateContent("x".repeat(8193)) }
        assertThrows(IllegalArgumentException::class.java) { AdvancedMemoryPolicy.validateId("bad\n") }
    }
    @Test fun configBoundsAreStrict() {
        val defaults = AdvancedMemoryConfig()
        AdvancedMemoryPolicy.validateConfig(defaults)
        for (bad in listOf(defaults.copy(threshold = Float.NaN), defaults.copy(threshold = 1.01f),
            defaults.copy(maxResults = 21), defaults.copy(idleMinutes = 0), defaults.copy(epoch = -1))) {
            assertThrows(IllegalArgumentException::class.java) { AdvancedMemoryPolicy.validateConfig(bad) }
        }
    }
}
