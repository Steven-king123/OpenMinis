package com.openminis.app.memory

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Collections
import java.util.UUID

/**
 * Canonical text, vectors, tombstones and config share ONE AtomicFile transaction.
 * All instances in this process for the same path share a lock and StateFlows.
 * Corrupt/unsupported stores fail closed (never silently reset deletion protection).
 * This is an app-process store; callers must not write it from another process.
 */
class AdvancedMemoryStore private constructor(private val shared: Shared) {
    constructor(context: Context) : this(sharedFor(File(context.applicationContext.filesDir,
        "minis-global/memory/advanced-memory")))
    internal constructor(directory: File) : this(sharedFor(directory))

    val records: StateFlow<List<MemoryRecord>> = shared.records.asStateFlow()
    val config: StateFlow<AdvancedMemoryConfig> = shared.config.asStateFlow()

    fun updateConfig(value: AdvancedMemoryConfig) = synchronized(shared) {
        AdvancedMemoryPolicy.validateConfig(value)
        val old = shared.snapshot
        if (value.copy(epoch = old.config.epoch) == old.config) return@synchronized
        val next = value.copy(epoch = increment(old.config.epoch))
        // attachVector has revision CAS, not an epoch parameter: revision bumps invalidate
        // embeddings already in flight even when settings change and then change back.
        val changedModel = next.embeddingEntryId != old.config.embeddingEntryId
        val records = old.records.map {
            if (it.deleted) it else it.copy(revision = increment(it.revision),
                embeddingModel = if (changedModel) null else it.embeddingModel,
                vector = if (changedModel) emptyList() else it.vector)
        }
        shared.commit(Snapshot(next, records))
    }

    fun save(
        content: String, kind: MemoryKind = MemoryKind.CORE, id: String? = null,
        sourceSessionId: String? = null, manual: Boolean = true,
    ): MemoryRecord = synchronized(shared) {
        AdvancedMemoryPolicy.validateContent(content)
        id?.let { AdvancedMemoryPolicy.validateId(it) }
        sourceSessionId?.let { AdvancedMemoryPolicy.validateId(it) }
        val old = shared.snapshot
        val existing = id?.let { key -> old.records.firstOrNull { it.id == key } }
        require(existing?.deleted != true) { "Deleted memory cannot be resurrected" }
        require(existing?.manual != true || manual) { "Manual memory is protected from automatic updates" }
        require(existing?.sourceSessionId == null || sourceSessionId == null ||
            existing.sourceSessionId == sourceSessionId) { "Source session cannot change" }
        val source = existing?.sourceSessionId ?: sourceSessionId
        if (!manual && source != null) {
            require(old.records.none { it.sourceSessionId == source && (it.deleted || it.manual) }) {
                "Source session is protected by a deletion or manual edit"
            }
            require(old.records.none { it.sourceSessionId == source && it.id != existing?.id }) {
                "Use commitEpisode to update an existing source session"
            }
        }
        val record = MemoryRecord(id ?: UUID.randomUUID().toString(), kind, content,
            increment(existing?.revision ?: 0), System.currentTimeMillis(), source,
            manual = manual || existing?.manual == true)
        shared.commit(Snapshot(old.config, replace(old.records, record)))
        record
    }

    fun updateIfRevision(id: String, revision: Long, content: String): MemoryRecord? = synchronized(shared) {
        val current = shared.snapshot.records.firstOrNull { it.id == id && !it.deleted && it.revision == revision }
            ?: return@synchronized null
        save(content, current.kind, current.id, current.sourceSessionId, manual = true)
    }

    fun deleteIfRevision(id: String, revision: Long): Boolean = synchronized(shared) {
        if (shared.snapshot.records.none { it.id == id && !it.deleted && it.revision == revision }) return@synchronized false
        delete(id)
        true
    }

    fun delete(id: String) = synchronized(shared) {
        AdvancedMemoryPolicy.validateId(id)
        val old = shared.snapshot
        val record = old.records.firstOrNull { it.id == id } ?: return@synchronized
        if (record.deleted) return@synchronized
        val tombstone = record.copy(content = "", revision = increment(record.revision),
            updatedAt = System.currentTimeMillis(), deleted = true, embeddingModel = null, vector = emptyList())
        shared.commit(Snapshot(old.config, replace(old.records, tombstone)))
    }

    fun attachVector(id: String, revision: Long, model: String, vector: List<Float>): Boolean = synchronized(shared) {
        AdvancedMemoryPolicy.validateId(id)
        require(revision > 0) { "Invalid revision" }
        AdvancedMemoryPolicy.validateModel(model)
        AdvancedMemoryPolicy.validateVector(vector)
        val old = shared.snapshot
        val record = old.records.firstOrNull { it.id == id } ?: return@synchronized false
        if (record.deleted || record.revision != revision) return@synchronized false
        shared.commit(Snapshot(old.config, replace(old.records,
            record.copy(embeddingModel = model, vector = immutable(vector)))))
        true
    }

    fun commitEpisode(sessionId: String, content: String, expectedEpoch: Long, sourceVersion: String? = null): MemoryRecord? = synchronized(shared) {
        AdvancedMemoryPolicy.validateId(sessionId)
        AdvancedMemoryPolicy.validateContent(content)
        val old = shared.snapshot
        if (!old.config.enabled || old.config.epoch != expectedEpoch) return@synchronized null
        val sameSource = old.records.filter { it.sourceSessionId == sessionId }
        if (sameSource.any { it.deleted || it.manual }) return@synchronized null
        val existing = sameSource.firstOrNull() ?: run {
            val record = MemoryRecord(UUID.randomUUID().toString(), MemoryKind.EPISODE,
                content, 1, System.currentTimeMillis(), sessionId, sourceVersion = sourceVersion)
            shared.commit(Snapshot(old.config, replace(old.records, record)))
            return@synchronized record
        }
        if (sourceVersion != null && existing.sourceVersion == sourceVersion) return@synchronized existing
        val record = existing.copy(kind = MemoryKind.EPISODE, content = content,
            revision = increment(existing.revision), updatedAt = System.currentTimeMillis(),
            embeddingModel = null, vector = emptyList(), sourceVersion = sourceVersion)
        shared.commit(Snapshot(old.config, replace(old.records, record)))
        record
    }

    fun search(
        query: String, queryVector: List<Float>?, model: String?,
        limit: Int = config.value.maxResults, threshold: Float = config.value.threshold,
    ): List<MemoryHit> {
        val snapshot = synchronized(shared) { shared.snapshot }
        return AdvancedMemoryPolicy.search(snapshot.records, query, queryVector, model, limit, threshold)
    }

    private data class Snapshot(val config: AdvancedMemoryConfig, val records: List<MemoryRecord>)

    private class Shared(directory: File) {
        private val file: AtomicFile
        var snapshot: Snapshot
            private set
        val records: MutableStateFlow<List<MemoryRecord>>
        val config: MutableStateFlow<AdvancedMemoryConfig>
        init {
            check(directory.isDirectory || directory.mkdirs()) { "Cannot create memory directory" }
            file = AtomicFile(File(directory, "store.json"))
            snapshot = try {
                file.openRead().use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var count: Int
                    while (input.read(buffer).also { count = it } != -1) {
                        check(output.size() + count <= AdvancedMemoryPolicy.MAX_FILE_BYTES) { "Memory store exceeds byte limit" }
                        output.write(buffer, 0, count)
                    }
                    decode(JSONObject(output.toString("UTF-8")))
                }
            } catch (error: java.io.FileNotFoundException) {
                // Missing file is only safe on FIRST use, not if backup/base exists unreadably.
                check(!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) {
                    "Memory store is unreadable; refusing to reset it"
                }
                Snapshot(AdvancedMemoryConfig(), emptyList())
            }
            records = MutableStateFlow(snapshot.records)
            config = MutableStateFlow(snapshot.config)
        }

        fun commit(value: Snapshot) {
            validate(value)
            val safe = value.copy(records = immutable(value.records.map {
                it.copy(vector = immutable(it.vector))
            }))
            val bytes = encode(safe).toString().toByteArray(Charsets.UTF_8)
            check(bytes.size <= AdvancedMemoryPolicy.MAX_FILE_BYTES) { "Memory store full; no records were evicted" }
            val output = file.startWrite()
            try {
                output.write(bytes)
                output.fd.sync()
                file.finishWrite(output)
            } catch (error: Throwable) {
                file.failWrite(output)
                throw error
            }
            // Publish only after durable storage succeeds.
            snapshot = safe
            records.value = safe.records
            config.value = safe.config
        }
    }

    companion object {
        private val stores = mutableMapOf<String, Shared>()
        private fun sharedFor(directory: File): Shared = synchronized(stores) {
            stores.getOrPut(directory.canonicalPath) { Shared(directory) }
        }
        private fun increment(value: Long): Long {
            check(value < Long.MAX_VALUE) { "Memory version exhausted" }
            return value + 1
        }
        private fun <T> immutable(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
        private fun replace(records: List<MemoryRecord>, record: MemoryRecord): List<MemoryRecord> =
            if (records.any { it.id == record.id }) records.map { if (it.id == record.id) record else it }
            else records + record

        private fun validate(value: Snapshot) {
            AdvancedMemoryPolicy.validateConfig(value.config)
            require(value.records.size <= AdvancedMemoryPolicy.MAX_RECORDS) { "Memory record capacity reached; tombstones are retained" }
            require(value.records.map { it.id }.toSet().size == value.records.size) { "Duplicate memory ID" }
            var contentChars = 0L
            var vectorValues = 0L
            for (record in value.records) {
                AdvancedMemoryPolicy.validateId(record.id)
                record.sourceSessionId?.let { AdvancedMemoryPolicy.validateId(it) }
                require(record.revision > 0 && record.updatedAt >= 0) { "Invalid memory version/timestamp" }
                if (record.deleted) require(record.content.isEmpty() && record.vector.isEmpty() && record.embeddingModel == null)
                else AdvancedMemoryPolicy.validateContent(record.content)
                if (record.vector.isEmpty()) require(record.embeddingModel == null) { "Model without vector" }
                else {
                    require(record.embeddingModel != null) { "Vector without model" }
                    AdvancedMemoryPolicy.validateModel(record.embeddingModel)
                    AdvancedMemoryPolicy.validateVector(record.vector)
                }
                contentChars += record.content.length
                vectorValues += record.vector.size
            }
            require(contentChars <= AdvancedMemoryPolicy.MAX_TOTAL_CONTENT_CHARS) { "Memory text capacity reached; no eviction" }
            require(vectorValues <= AdvancedMemoryPolicy.MAX_TOTAL_VECTOR_VALUES) { "Memory index capacity reached; no eviction" }
        }

        private fun encode(value: Snapshot): JSONObject = JSONObject().put("version", 1)
            .put("config", JSONObject().put("enabled", value.config.enabled)
                .put("summarizerEntryId", value.config.summarizerEntryId).put("embeddingEntryId", value.config.embeddingEntryId)
                .put("threshold", value.config.threshold.toDouble()).put("maxResults", value.config.maxResults)
                .put("autoConsolidate", value.config.autoConsolidate).put("idleMinutes", value.config.idleMinutes)
                .put("epoch", value.config.epoch))
            .put("records", JSONArray().also { array -> value.records.forEach { record ->
                array.put(JSONObject().put("id", record.id).put("kind", record.kind.name)
                    .put("content", record.content).put("revision", record.revision).put("updatedAt", record.updatedAt)
                    .put("sourceSessionId", record.sourceSessionId ?: JSONObject.NULL).put("manual", record.manual)
                    .put("sourceVersion", record.sourceVersion ?: JSONObject.NULL)
                    .put("deleted", record.deleted).put("embeddingModel", record.embeddingModel ?: JSONObject.NULL)
                    .put("vector", JSONArray().also { vector -> record.vector.forEach { vector.put(it.toDouble()) } }))
            } })

        private fun string(json: JSONObject, name: String): String = json.get(name).let {
            require(it is String) { "Invalid string field: $name" }; it
        }
        private fun nullableString(json: JSONObject, name: String): String? =
            if (json.get(name) == JSONObject.NULL) null else string(json, name)
        private fun bool(json: JSONObject, name: String): Boolean = json.get(name).let {
            require(it is Boolean) { "Invalid boolean field: $name" }; it
        }
        private fun long(json: JSONObject, name: String): Long = json.get(name).let {
            require(it is Int || it is Long) { "Invalid integer field: $name" }; (it as Number).toLong()
        }
        private fun int(json: JSONObject, name: String): Int = long(json, name).let {
            require(it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()); it.toInt()
        }
        private fun decode(json: JSONObject): Snapshot {
            require(int(json, "version") == 1) { "Unsupported advanced memory store version" }
            val config = json.getJSONObject("config")
            val threshold = config.get("threshold")
            require(threshold is Number) { "Invalid threshold" }
            val options = AdvancedMemoryConfig(bool(config, "enabled"), string(config, "summarizerEntryId"),
                string(config, "embeddingEntryId"), threshold.toFloat(), int(config, "maxResults"),
                bool(config, "autoConsolidate"), int(config, "idleMinutes"), long(config, "epoch"))
            val array = json.getJSONArray("records")
            require(array.length() <= AdvancedMemoryPolicy.MAX_RECORDS) { "Memory record capacity exceeded" }
            val records = (0 until array.length()).map { i ->
                val item = array.getJSONObject(i)
                val vector = item.getJSONArray("vector")
                require(vector.length() <= AdvancedMemoryPolicy.MAX_VECTOR_DIMENSION) { "Embedding dimension exceeded" }
                MemoryRecord(string(item, "id"), MemoryKind.valueOf(string(item, "kind")),
                    string(item, "content"), long(item, "revision"), long(item, "updatedAt"),
                    nullableString(item, "sourceSessionId"), bool(item, "manual"), bool(item, "deleted"),
                    nullableString(item, "embeddingModel"), immutable((0 until vector.length()).map { j ->
                        val number = vector.get(j)
                        require(number is Number) { "Invalid embedding component" }; number.toFloat()
                    }), sourceVersion = if (item.isNull("sourceVersion")) null else item.getString("sourceVersion"))
            }
            return Snapshot(options, immutable(records)).also(::validate)
        }
    }
}
