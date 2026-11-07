package com.openminis.app.memory

import android.content.Context
import com.openminis.app.MinisApp
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.model.ContentPart
import com.openminis.app.data.model.contentPartJson
import com.openminis.app.data.repository.ChatRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** No automatic import, historical session sweep, prompt mutation or provider fallback. */
object AdvancedMemoryRuntime {
    @Volatile private var memoryStore: AdvancedMemoryStore? = null
    @Volatile private var memoryModels: AdvancedMemoryModels? = null
    private val state = MutableStateFlow("Ready")
    val status: StateFlow<String> = state.asStateFlow()
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val summaryMutex = Mutex()
    private val indexMutex = Mutex()
    private val activityLock = Any()
    private val activityVersions = mutableMapOf<String, Long>()

    fun store(context: Context): AdvancedMemoryStore = memoryStore ?: synchronized(this) {
        memoryStore ?: AdvancedMemoryStore(context.applicationContext).also { memoryStore = it }
    }
    private val initializing = java.util.concurrent.atomic.AtomicBoolean(false)
    private fun initializeAsync(context: Context) {
        if (memoryStore != null || !initializing.compareAndSet(false, true)) return
        scope.launch {
            try { store(context.applicationContext) }
            catch (_: Exception) { state.value = "Memory storage unavailable; injection disabled without deleting data." }
            finally { initializing.set(false) }
        }
    }
    // Called from Compose/getters: never parse a multi-megabyte index on the UI thread.
    fun isAvailable(context: Context): Boolean {
        if (memoryStore == null) initializeAsync(context)
        return memoryStore != null
    }

    fun isEnabled(context: Context): Boolean {
        if (memoryStore == null) initializeAsync(context)
        return memoryStore?.config?.value?.enabled == true
    }

    fun models(context: Context): AdvancedMemoryModels = memoryModels ?: synchronized(this) {
        memoryModels ?: AdvancedMemoryModels(context.applicationContext).also { memoryModels = it }
    }
    internal fun report(message: String) { state.value = message }
    private fun failure(e: Exception): String = when (e) {
        is TimeoutCancellationException -> "Memory operation timed out; try again or select a faster model."
        is MemoryModelException -> e.message ?: "Check memory model settings."
        else -> "Memory operation failed; check storage and model settings."
    }

    suspend fun recall(context: Context, query: String): List<MemoryHit> = withContext(Dispatchers.IO) {
        val store = store(context)
        val config = store.config.value
        if (!config.enabled || query.isBlank()) return@withContext emptyList()
        val bounded = query.take(AdvancedMemoryPolicy.MAX_QUERY_CHARS)
        var vector: List<Float>? = null
        var fingerprint: String? = null
        var degraded: String? = null
        if (config.embeddingEntryId.isNotBlank()) {
            try {
                val result = withTimeout(12_000) { models(context).embed(bounded, config.embeddingEntryId) }
                fingerprint = result.first
                vector = result.second
            } catch (e: TimeoutCancellationException) {
                degraded = failure(e)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { degraded = failure(e) }
        } else degraded = "Select an embedding model to enable semantic recall."
        if (store.config.value.epoch != config.epoch || !store.config.value.enabled) return@withContext emptyList()
        try {
            val hits = store.search(bounded, vector, fingerprint, config.maxResults, config.threshold)
                .filter { it.accepted }.take(config.maxResults)
            state.value = if (degraded != null) "Lexical recall only: $degraded" else "Recall: ${hits.size} relevant memories"
            hits
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { state.value = failure(e); emptyList() }
    }

    /** Explicit UI action; cancelling the caller stops the next item and cancels its HTTP call. */
    suspend fun reindex(context: Context) = withContext(Dispatchers.IO) {
        indexMutex.withLock {
            val store = store(context)
            val config = store.config.value
            try {
                val model = models(context).modelFingerprint(config.embeddingEntryId)
                val records = store.records.value.filter { !it.deleted }
                var done = 0
                var failed = 0
                var lastFailure: String? = null
                for (record in records) {
                    currentCoroutineContext().ensureActive()
                    if (store.config.value.epoch != config.epoch) {
                        state.value = "Reindex stopped: memory configuration changed."; return@withLock
                    }
                    if (record.embeddingModel == model && record.vector.isNotEmpty()) continue
                    state.value = "Reindexing ${done + failed + 1}/${records.size}"
                    try {
                        val (fingerprint, vector) = models(context).embed(record.content, config.embeddingEntryId)
                        if (store.config.value.epoch != config.epoch) {
                            state.value = "Reindex stopped: memory configuration changed."; return@withLock
                        }
                        if (fingerprint != model) { state.value = "Reindex stopped: provider configuration changed."; return@withLock }
                        if (store.attachVector(record.id, record.revision, fingerprint, vector)) done++
                    } catch (e: TimeoutCancellationException) { failed++; lastFailure = failure(e)
                    } catch (e: CancellationException) { throw e
                    } catch (e: Exception) { failed++; lastFailure = failure(e) }
                }
                state.value = "Reindex complete: $done indexed, $failed failed." + (lastFailure?.let { " $it" } ?: "")
            } catch (e: CancellationException) { state.value = "Reindex cancelled."; throw e
            } catch (e: Exception) { state.value = failure(e) }
        }
    }

    suspend fun summarizeSession(context: Context, sessionId: String) {
        summarizeChecked(context.applicationContext, sessionId, null, null)
    }

    internal suspend fun summarizeScheduled(context: Context, sessionId: String, epoch: Long, token: String) {
        summarizeChecked(context.applicationContext, sessionId, epoch, token)
    }

    private suspend fun summarizeChecked(context: Context, sessionId: String, expectedEpoch: Long?, token: String?) = withContext(Dispatchers.IO) {
        summaryMutex.withLock {
            val store = store(context)
            val config = store.config.value
            if (!config.enabled || (expectedEpoch != null && (config.epoch != expectedEpoch || !config.autoConsolidate))) return@withLock
            if (token != null && !AdvancedMemoryIdleScheduler.isCurrent(context, sessionId, token)) return@withLock
            try {
                withTimeout(60_000) {
                    val repo = (context.applicationContext as? MinisApp)?.chatRepositoryOrNull
                        ?: throw MemoryModelException("Chat storage is not ready; leave safe mode or retry after startup.")
                    val before = snapshot(repo, sessionId) ?: run {
                        state.value = "This session is missing, memory-disabled or a helper session."; return@withTimeout
                    }
                    if (before.text.isBlank()) { state.value = "No visible conversation text to summarize."; return@withTimeout }
                    // Deleted/manual records are protected by commitEpisode; avoid needless cloud cost as well.
                    if (store.records.value.any { it.sourceSessionId == sessionId && (it.deleted || it.manual) }) {
                        state.value = "This session's memory is manually protected or deleted."; return@withTimeout
                    }
                    val completed = store.records.value.firstOrNull {
                        !it.deleted && !it.manual && it.sourceSessionId == sessionId && it.sourceVersion == before.version
                    }
                    state.value = if (completed == null) "Summarizing selected session…" else "Summary already saved; repairing index only."
                    val output = completed?.content ?: models(context).summarize(before.text, config.summarizerEntryId)
                    currentCoroutineContext().ensureActive()
                    val latest = store.config.value
                    if (!latest.enabled || latest.epoch != config.epoch ||
                        (expectedEpoch != null && !latest.autoConsolidate) ||
                        (token != null && !AdvancedMemoryIdleScheduler.isCurrent(context, sessionId, token))) {
                        state.value = "Summary discarded: memory configuration or session activity changed."; return@withTimeout
                    }
                    val after = snapshot(repo, sessionId)
                    if (after == null || after != before) {
                        state.value = "Summary discarded: source conversation changed or was deleted."; return@withTimeout
                    }
                    val record = store.commitEpisode(sessionId, output, config.epoch, before.version)
                    if (record == null) {
                        state.value = "Summary skipped: source memory is protected or settings changed."
                    } else {
                        state.value = "Session memory saved; lexical retrieval available."
                        if (config.embeddingEntryId.isNotBlank()) {
                            try {
                                val (fingerprint, vector) = models(context).embed(record.content, config.embeddingEntryId)
                                if (store.config.value.enabled && store.config.value.epoch == config.epoch &&
                                    (token == null || AdvancedMemoryIdleScheduler.isCurrent(context, sessionId, token)) &&
                                    store.attachVector(record.id, record.revision, fingerprint, vector)) {
                                    state.value = "Session memory saved and indexed."
                                }
                            } catch (e: TimeoutCancellationException) { state.value = "Memory saved; embedding timed out. Lexical retrieval active."
                            } catch (e: CancellationException) { throw e
                            } catch (_: Exception) { state.value = "Memory saved; embedding unavailable. Lexical retrieval active." }
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) { state.value = failure(e)
            } catch (e: CancellationException) { state.value = "Summarization cancelled."; throw e
            } catch (e: Exception) { state.value = failure(e) }
        }
    }

    /** Main calls ONLY after the current real chat's persisted successful completion. */
    fun schedule(context: Context, sessionId: String) {
        val app = context.applicationContext
        val activityVersion = synchronized(activityLock) { activityVersions[sessionId] ?: 0L }
        scope.launch {
            try {
                val config = store(app).config.value
                if (!config.enabled || !config.autoConsolidate) { AdvancedMemoryIdleScheduler.cancel(app, sessionId); return@launch }
                val repo = (app as? MinisApp)?.chatRepositoryOrNull ?: return@launch
                if (!eligible(repo.getSession(sessionId))) return@launch
                synchronized(activityLock) {
                    if ((activityVersions[sessionId] ?: 0L) == activityVersion && store(app).config.value.epoch == config.epoch) {
                        AdvancedMemoryIdleScheduler.schedule(app, sessionId, config)
                    }
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { state.value = "Could not schedule idle memory; check Android background settings." }
        }
    }

    /** Main calls on any new turn/edit/retry/stop before the source can change. */
    fun onSessionActivity(context: Context, sessionId: String) {
        synchronized(activityLock) {
            activityVersions[sessionId] = (activityVersions[sessionId] ?: 0L) + 1L
            runCatching { AdvancedMemoryIdleScheduler.cancel(context.applicationContext, sessionId) }
                .onFailure { state.value = "Could not cancel idle memory work; results will be snapshot-checked." }
        }
    }

    private fun eligible(session: ChatSessionEntity?): Boolean = session != null && session.memoryEnabled != 0 &&
        session.parentSessionId == null && session.parentToolUseId == null &&
        session.source?.lowercase()?.let { it.contains("helper") || it.contains("subagent") || it.contains("sub-agent") } != true

    private data class SourceSnapshot(val version: String, val text: String)

    /** Bounded recent slice of exactly one persisted session, never loadMessages() / all sessions. */
    private suspend fun snapshot(repo: ChatRepository, sessionId: String): SourceSnapshot? {
        val session = repo.getSession(sessionId)
        if (!eligible(session)) return null
        session ?: return null
        val count = repo.dao.messageCountForSession(sessionId)
        val start = (count - 160).coerceAtLeast(0)
        val transcript = StringBuilder()
        val versions = StringBuilder("${session.updatedAt}:${session.editCount}:$count:")
        var scanned = 0
        var offset = start
        while (offset < count && scanned < 2_000_000) {
            currentCoroutineContext().ensureActive()
            val page = repo.dao.loadMessagesPage(sessionId, offset, minOf(8, count - offset))
            if (page.isEmpty()) break
            for (row in page) {
                scanned += row.partsJson.length
                versions.append(row.id).append(':').append(row.updatedAt).append(':').append(row.sortOrder)
                    .append(':').append(AdvancedMemoryModels.sha256(row.partsJson)).append(';')
                if (scanned > 2_000_000 || row.partsJson.length > 131_072) continue
                val role = row.role.lowercase()
                if (role != "user" && role != "assistant") continue
                val visible = visibleText(row.partsJson)
                if (visible.isBlank()) continue
                transcript.append(if (role == "user") "USER: " else "ASSISTANT: ").append(visible).append('\n')
                // Keep the most recent visible text, in chronological order.
                if (transcript.length > AdvancedMemoryModels.MAX_SUMMARY_INPUT_CHARS)
                    transcript.delete(0, transcript.length - AdvancedMemoryModels.MAX_SUMMARY_INPUT_CHARS)
            }
            offset += page.size
        }
        if (repo.getSession(sessionId) != session || repo.dao.messageCountForSession(sessionId) != count) return null
        return SourceSnapshot(AdvancedMemoryModels.sha256(versions.toString()), transcript.toString())
    }

    /** Only an explicitly typed text part is decoded, never raw JSON/tool payload fallback. */
    internal fun visibleText(partsJson: String): String {
        if (partsJson.length > 131_072) return ""
        val parts = runCatching { contentPartJson.parseToJsonElement(partsJson) as? JsonArray }.getOrNull() ?: return ""
        val text = StringBuilder()
        for (part in parts.take(128)) {
            val obj = part as? JsonObject ?: continue
            if ((obj["type"] as? JsonPrimitive)?.contentOrNull != "text") continue
            val decoded = runCatching { contentPartJson.decodeFromJsonElement(ContentPart.Text.serializer(), obj).value }.getOrNull() ?: continue
            val clean = hiddenBlocks.replace(decoded, "").take(6_000)
            if (clean.isNotBlank()) text.append(clean).append('\n')
            if (text.length >= 6_000) break
        }
        return text.toString().take(6_000).trim()
    }
    private val hiddenBlocks = Regex("<(system-reminder|think|thinking|reasoning|context-summary|user-attached-files)(?:\\s[^>]*)?>[\\s\\S]*?(?:</\\1>|$)", RegexOption.IGNORE_CASE)
}
