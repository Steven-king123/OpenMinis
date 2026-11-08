package com.openminis.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class MemoryModelPickerPolicyTest {
    private fun candidate(
        key: String, id: String = key, name: String = id,
        provider: String = "My Relay", modalities: List<String>? = null,
    ) = MemoryModelPickerPolicy.Candidate(
        entryId = key, modelName = name, modelId = id, providerLabel = provider,
        providerName = "OpenAI", providerType = "OpenAI Compatible", providerId = "relay-instance",
        outputModalities = modalities,
    )

    private fun search(items: List<MemoryModelPickerPolicy.Candidate>, query: String = "", embedding: Boolean = false) =
        MemoryModelPickerPolicy.search(items, query, embedding).map { it.entryId }

    @Test fun `search covers model name id entry id and provider fields`() {
        val items = listOf(candidate("uuid-123", "vendor/model-v3", "Custom Model", "个人中转"))
        for (query in listOf("custom", "MODEL-V3", "uuid-123", "个人", "openai", "compatible", "relay-instance")) {
            assertEquals(query, listOf("uuid-123"), search(items, query))
        }
        assertTrue(search(items, "missing").isEmpty())
    }

    @Test fun `whitespace and multi term search span fields`() {
        val items = listOf(candidate("one", "vendor/model-v3"), candidate("two", "other"))
        assertEquals(listOf("one"), search(items, "  MODEL-v3\t my\nrelay  "))
        assertEquals(listOf("one", "two"), search(items, " \n\t"))
        assertTrue(search(items, "model-v3 missing").isEmpty())
    }

    @Test fun `search does not depend on device locale`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale("tr", "TR"))
            assertEquals(listOf("ID"), search(listOf(candidate("ID", name = "MINI")), "mini"))
        } finally { Locale.setDefault(previous) }
    }

    @Test fun `embedding priority is a stable partition retaining all compatible entries`() {
        val items = listOf(candidate("chat-a"), candidate("embed-a", "text-embedding-3-small"),
            candidate("chat-b"), candidate("embed-b", "BAAI/bge-m3"), candidate("chat-c"))
        assertEquals(listOf("embed-a", "embed-b", "chat-a", "chat-b", "chat-c"), search(items, embedding = true))
        assertEquals(items.map { it.entryId }, search(items))
        assertEquals(listOf("chat-a", "chat-b", "chat-c"), search(items, "chat", true))
        assertEquals(items.size, search(items, embedding = true).size)
    }

    @Test fun `recognized advertised vector outputs precede opaque chat names`() {
        for (modality in listOf("embedding", "embeddings", "vector", "vectors", " EMBEDDING_OUTPUT ")) {
            assertTrue(modality, candidate("opaque", modalities = listOf(modality)).likelyEmbedding)
        }
        assertEquals(listOf("opaque", "chat"), search(listOf(candidate("chat"),
            candidate("opaque", modalities = listOf("vector"))), embedding = true))
    }

    @Test fun `conservative embedding families and separators are recognized`() {
        for (id in listOf("text-embedding-3-small", "Qwen/Qwen3-Embedding-8B", "jina-embeddings-v3",
            "nomic-embed-text-v1.5", "BAAI/bge-m3", "BAAI/bge-large-en-v1.5", "intfloat/e5-large-v2",
            "multilingual-e5-base", "Alibaba-NLP/gte-Qwen2-7B-instruct", "gte-multilingual-base",
            "embed-v4.0", "embeddinggemma-300m")) {
            assertTrue(id, candidate(id).likelyEmbedding)
        }
        assertTrue(candidate("opaque", name = "Text Embedding Model").likelyEmbedding)
    }

    @Test fun `generic text metadata still permits name heuristics`() {
        for (modalities in listOf(null, emptyList(), listOf("text"), listOf("text_output"))) {
            assertTrue(candidate("text-embedding-3-large", modalities = modalities).likelyEmbedding)
        }
    }

    @Test fun `substrings provider names rerankers and dedicated media are not promoted`() {
        for (id in listOf("gpt-5", "embedded-chat", "embeddingless-chat", "allegedly-e50", "stage-model",
            "target-large", "bge-reranker-v2-m3", "jina-reranker-v2-base", "nomic-chat", "jina-chat")) {
            assertFalse(id, candidate(id, provider = "Embedding Provider").likelyEmbedding)
        }
        assertFalse(candidate("bge-reranker-v2", name = "Embedding helper").likelyEmbedding)
        assertFalse(candidate("text-embedding-image", modalities = listOf("image_output")).likelyEmbedding)
        assertFalse(candidate("embed-audio", modalities = listOf("audio")).likelyEmbedding)
        assertTrue(candidate("bge-reranker", modalities = listOf("embedding")).likelyEmbedding)
    }

    @Test fun `identical model ids on different providers retain distinct stable entry ids`() {
        val items = listOf(candidate("entry-a", "text-embedding-3-small", provider = "Alpha"),
            candidate("entry-b", "text-embedding-3-small", provider = "Beta"))
        assertEquals(listOf("entry-a", "entry-b"), search(items, embedding = true))
        assertEquals(listOf("entry-b"), search(items, "beta", true))
    }

    @Test fun `empty and zero match catalogs stay empty`() {
        assertTrue(search(emptyList(), embedding = true).isEmpty())
        assertTrue(search(listOf(candidate("one")), "missing", true).isEmpty())
    }
}
