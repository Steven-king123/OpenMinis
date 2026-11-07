package com.openminis.app.memory

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AdvancedMemoryStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun store() = AdvancedMemoryStore(temporary.newFolder())
    private fun enabled(store: AdvancedMemoryStore): Long {
        store.updateConfig(store.config.value.copy(enabled = true))
        return store.config.value.epoch
    }
    private fun reopenFromDisk(directory: File): AdvancedMemoryStore {
        // A different canonical path bypasses the process singleton and exercises decoding.
        val copy = temporary.newFolder()
        File(directory, "store.json").copyTo(File(copy, "store.json"))
        return AdvancedMemoryStore(copy)
    }

    @Test fun canonicalTextAndIndexSurviveReloadTogether() {
        val directory = temporary.newFolder()
        val store = AdvancedMemoryStore(directory)
        val record = store.save("喜欢喝茶")
        assertTrue(store.attachVector(record.id, record.revision, "provider:model:urlhash", listOf(1f, 2f)))
        val reload = reopenFromDisk(directory)
        assertEquals(store.records.value, reload.records.value)
        assertTrue(reload.search("喝茶", null, null).single().accepted)
    }
    @Test fun editInvalidatesVectorAndRejectsStaleCas() {
        val store = store()
        val first = store.save("tea")
        assertTrue(store.attachVector(first.id, first.revision, "m", listOf(1f)))
        val edited = store.save("coffee", id = first.id)
        assertEquals(first.revision + 1, edited.revision)
        assertTrue(edited.vector.isEmpty())
        assertNull(edited.embeddingModel)
        assertFalse(store.attachVector(first.id, first.revision, "m", listOf(1f)))
    }
    @Test fun deletionPersistsTombstoneAndBlocksResurrectionAcrossRestart() {
        val directory = temporary.newFolder()
        val store = AdvancedMemoryStore(directory)
        val epoch = enabled(store)
        val record = store.commitEpisode("session", "tea", epoch)!!
        store.delete(record.id)
        val deleted = store.records.value.single()
        assertTrue(deleted.deleted)
        assertEquals("", deleted.content)
        assertEquals("session", deleted.sourceSessionId)
        assertEquals(record.revision + 1, deleted.revision)
        val reload = reopenFromDisk(directory)
        assertNull(reload.commitEpisode("session", "recreated", epoch))
        assertFalse(reload.attachVector(record.id, record.revision, "m", listOf(1f)))
        assertThrows(IllegalArgumentException::class.java) { reload.save("restore", id = record.id) }
        assertThrows(IllegalArgumentException::class.java) { reload.save("restore", sourceSessionId = "session", manual = false) }
    }
    @Test fun manualEditProtectsAutomaticSourceAndCannotDowngrade() {
        val store = store()
        val epoch = enabled(store)
        val auto = store.commitEpisode("session", "generated", epoch)!!
        val manual = store.save("my correction", id = auto.id)
        assertTrue(manual.manual)
        assertEquals("session", manual.sourceSessionId)
        assertNull(store.commitEpisode("session", "overwrite", epoch))
        assertThrows(IllegalArgumentException::class.java) { store.save("overwrite", id = auto.id, manual = false) }
    }
    @Test fun sameSourceUpdatesInsteadOfDuplicating() {
        val store = store()
        val epoch = enabled(store)
        val first = store.commitEpisode("s", "one", epoch)!!
        val second = store.commitEpisode("s", "two", epoch)!!
        assertEquals(first.id, second.id)
        assertEquals(first.revision + 1, second.revision)
        assertEquals(1, store.records.value.size)
    }
    @Test fun configEpochInvalidatesSummaryAndEmbeddingWork() {
        val store = store()
        val epoch = enabled(store)
        val first = store.save("tea")
        store.updateConfig(store.config.value.copy(threshold = 0.6f, epoch = 999))
        assertEquals(epoch + 1, store.config.value.epoch)
        assertNull(store.commitEpisode("session", "late", epoch))
        assertFalse(store.attachVector(first.id, first.revision, "m", listOf(1f)))
        val current = store.config.value
        store.updateConfig(current.copy(epoch = 100))
        assertEquals(current.epoch, store.config.value.epoch)
        store.updateConfig(current.copy(enabled = false))
        assertNull(store.commitEpisode("session", "disabled", store.config.value.epoch))
    }
    @Test fun embeddingModelChangeClearsOldIndex() {
        val store = store()
        val record = store.save("tea")
        store.attachVector(record.id, record.revision, "old", listOf(1f))
        store.updateConfig(store.config.value.copy(embeddingEntryId = "new"))
        assertTrue(store.records.value.single().vector.isEmpty())
    }
    @Test fun samePathInstancesShareStateAndLock() {
        val directory = temporary.newFolder()
        val a = AdvancedMemoryStore(directory)
        val b = AdvancedMemoryStore(directory)
        val pool = Executors.newFixedThreadPool(4)
        val tasks = (1..24).map { index -> pool.submit { (if (index % 2 == 0) a else b).save("tea $index") } }
        tasks.forEach { it.get(10, TimeUnit.SECONDS) }
        pool.shutdownNow()
        assertEquals(24, a.records.value.size)
        assertEquals(a.records.value, b.records.value)
        assertEquals(a.records.value, reopenFromDisk(directory).records.value)
    }
    @Config(sdk = [33], manifest = Config.NONE)
    @Test fun failedDiskWriteDoesNotPublishInMemoryChanges() {
        val directory = temporary.newFolder()
        val store = AdvancedMemoryStore(directory)
        store.save("original")
        val before = store.records.value
        // Block AtomicFile's .new path; no database/config publication may happen.
        val blocker = File(directory, "store.json.new")
        assertTrue(blocker.mkdir())
        File(blocker, "block").writeText("x")
        assertThrows(Exception::class.java) { store.save("must fail") }
        assertEquals(before, store.records.value)
        assertEquals(before, reopenFromDisk(directory).records.value)
    }
    @Test fun corruptStoreFailsClosedRatherThanLosingTombstones() {
        val directory = temporary.newFolder()
        File(directory, "store.json").writeText("not json")
        assertThrows(Exception::class.java) { AdvancedMemoryStore(directory) }
    }
    @Test fun invalidInputDoesNotChangeStateOrDisk() {
        val directory = temporary.newFolder()
        val store = AdvancedMemoryStore(directory)
        val record = store.save("tea")
        val before = File(directory, "store.json").readText()
        assertThrows(IllegalArgumentException::class.java) { store.save(" ") }
        assertThrows(IllegalArgumentException::class.java) { store.attachVector(record.id, 1, "m", listOf(Float.NaN)) }
        assertThrows(IllegalArgumentException::class.java) { store.updateConfig(store.config.value.copy(maxResults = 21)) }
        assertEquals(before, File(directory, "store.json").readText())
    }
    @Test fun strictJsonTypesAreNotSilentlyCoerced() {
        val directory = temporary.newFolder()
        AdvancedMemoryStore(directory).save("tea")
        val json = JSONObject(File(directory, "store.json").readText())
        json.getJSONArray("records").getJSONObject(0).put("manual", "true")
        val broken = temporary.newFolder()
        File(broken, "store.json").writeText(json.toString())
        assertThrows(IllegalArgumentException::class.java) { AdvancedMemoryStore(broken) }
    }
    @Test fun sourceSessionCannotBeDetachedOrChangedByEdit() {
        val store = store()
        val first = store.save("tea", sourceSessionId = "s")
        val edited = store.save("coffee", id = first.id)
        assertEquals("s", edited.sourceSessionId)
        assertThrows(IllegalArgumentException::class.java) { store.save("bad", id = first.id, sourceSessionId = "other") }
    }
}
