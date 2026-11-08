package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderType
import com.openminis.app.memory.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

/** Embedded in MemoryManagementScreen's scrollable SettingsScaffold; never starts model work on entry. */
@Composable
fun AdvancedMemorySettings() {
    val context = LocalContext.current.applicationContext
    var store by remember { mutableStateOf<AdvancedMemoryStore?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableStateOf(0) }
    LaunchedEffect(retry) {
        failure = null
        try {
            store = withContext(Dispatchers.IO) { AdvancedMemoryRuntime.store(context) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure = e.message ?: context.getString(R.string.am_failed)
        }
    }
    val ready = store
    if (ready == null) {
        SettingsSection(header = stringResource(R.string.am_title)) {
            SettingsCardBlock {
                Text(failure ?: stringResource(R.string.am_loading))
                if (failure != null) TextButton(onClick = { retry++ }) { Text(stringResource(R.string.am_retry)) }
            }
        }
    } else {
        AdvancedMemoryContent(ready)
    }
}

@Composable
private fun AdvancedMemoryContent(store: AdvancedMemoryStore) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val config by store.config.collectAsState()
    val records by store.records.collectAsState()
    val runtimeStatus by AdvancedMemoryRuntime.status.collectAsState()
    val repo = remember(context) { (context as? MinisApp)?.providerRepositoryOrNull }
    val emptyConfig = remember { MutableStateFlow(ProviderConfig()) }
    val providers by (repo?.config ?: emptyConfig).collectAsState()
    var message by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var operation by remember { mutableStateOf<Job?>(null) }
    var picker by remember { mutableStateOf<String?>(null) }
    var confirmAuto by remember { mutableStateOf(false) }
    var confirmReindex by remember { mutableStateOf(false) }
    var confirmSummarize by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<MemoryRecord?>(null) }
    var editorOpen by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<MemoryRecord?>(null) }
    var filter by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<MemoryKind?>(null) }
    var shown by remember { mutableStateOf(25) }
    var query by remember { mutableStateOf("") }
    var useEmbedding by remember { mutableStateOf(false) }
    var hits by remember { mutableStateOf<List<MemoryHit>?>(null) }
    var testedThreshold by remember { mutableStateOf(config.threshold) }
    var sourceSession by remember { mutableStateOf("") }

    fun runOperation(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = false
        message = context.getString(R.string.am_working)
        operation = scope.launch {
            try {
                block()
                if (message == context.getString(R.string.am_working)) message = context.getString(R.string.am_done)
            } catch (e: CancellationException) {
                message = context.getString(R.string.am_cancelled)
                throw e
            } catch (e: Exception) {
                error = true
                message = context.getString(R.string.am_error, e.message ?: context.getString(R.string.am_failed))
            } finally {
                busy = false
                operation = null
            }
        }
    }
    fun update(change: (AdvancedMemoryConfig) -> AdvancedMemoryConfig) = runOperation {
        withContext(Dispatchers.IO) { store.updateConfig(change(store.config.value)) }
    }
    val available = remember(providers) {
        val enabled = providers.instances.filter { it.isEnabled && it.providerType.isUsable }.map { it.id }.toSet()
        providers.modelEntries.filter { it.providerInstanceId in enabled && !it.isHidden && !it.isUnavailableFromProvider }
    }
    val summaries = remember(available, providers) { available.filter { entry ->
        entry.model.isTextOutput && providers.instances.any { instance ->
            instance.id == entry.providerInstanceId && instance.credentialType == ProviderCredential.apiKey && !instance.azureMode &&
                instance.providerType in setOf(ProviderType.openAI, ProviderType.openAIResponses, ProviderType.openRouter, ProviderType.xAI, ProviderType.anthropic, ProviderType.gemini)
        }
    } }
    val embeddings = remember(available, providers) {
        available.filter { entry ->
            providers.instances.any { instance ->
                instance.id == entry.providerInstanceId && instance.credentialType == ProviderCredential.apiKey &&
                    !instance.azureMode && instance.providerType in setOf(ProviderType.openAI, ProviderType.openAIResponses, ProviderType.openRouter, ProviderType.xAI)
            }
        }
    }
    fun modelLabel(id: String): String {
        if (id.isBlank()) return context.getString(R.string.am_not_selected)
        val entry = available.firstOrNull { it.id == id } ?: return context.getString(R.string.am_unavailable)
        val provider = providers.instances.firstOrNull { it.id == entry.providerInstanceId }
        return "${entry.model.displayName} · ${provider?.label.orEmpty()}"
    }

    SettingsSection(header = stringResource(R.string.am_title), footer = stringResource(R.string.am_mode_help)) {
        SettingsSwitchRow(
            title = stringResource(R.string.am_enabled),
            subtitle = stringResource(if (config.enabled) R.string.am_advanced else R.string.am_standard),
            checked = config.enabled, enabled = !busy,
            onCheckedChange = { value -> update { it.copy(enabled = value) } },
        )
        SettingsValueRow(stringResource(R.string.am_summarizer), modelLabel(config.summarizerEntryId),
            onClick = if (!busy) ({ picker = "summary" }) else null)
        SettingsValueRow(stringResource(R.string.am_embedding), modelLabel(config.embeddingEntryId),
            onClick = if (!busy) ({ picker = "embedding" }) else null, showDivider = false)
        SettingsCardBlock { Text(stringResource(R.string.am_embedding_help), style = MaterialTheme.typography.bodySmall) }
    }

    SettingsSection(header = stringResource(R.string.am_retrieval)) {
        var threshold by remember(config.threshold) { mutableStateOf(config.threshold) }
        SettingsCardBlock {
            Text(stringResource(R.string.am_threshold, threshold.toDouble()))
            Slider(value = threshold, onValueChange = { threshold = it }, valueRange = 0f..1f, enabled = !busy,
                onValueChangeFinished = { update { it.copy(threshold = threshold) } })
            Text(stringResource(R.string.am_threshold_help), style = MaterialTheme.typography.bodySmall)
        }
        var maxResults by remember(config.maxResults) { mutableStateOf(config.maxResults.toFloat()) }
        SettingsCardBlock {
            Text(stringResource(R.string.am_max_results, maxResults.roundToInt()))
            Slider(value = maxResults, onValueChange = { maxResults = it }, valueRange = 1f..20f, steps = 18,
                enabled = !busy, onValueChangeFinished = { update { it.copy(maxResults = maxResults.roundToInt()) } })
        }
    }

    SettingsSection(header = stringResource(R.string.am_automatic), footer = stringResource(R.string.am_auto_help)) {
        SettingsSwitchRow(title = stringResource(R.string.am_auto_enable), checked = config.autoConsolidate,
            enabled = !busy && config.enabled && config.summarizerEntryId.isNotBlank(),
            onCheckedChange = { if (it) confirmAuto = true else update { c -> c.copy(autoConsolidate = false) } },
            showDivider = false)
        var idle by remember(config.idleMinutes) { mutableStateOf(config.idleMinutes.toFloat()) }
        SettingsCardBlock {
            Text(stringResource(R.string.am_idle, idle.roundToInt()))
            Slider(value = idle, onValueChange = { idle = it }, valueRange = 1f..240f, steps = 238,
                enabled = !busy, onValueChangeFinished = { update { it.copy(idleMinutes = idle.roundToInt()) } })
        }
    }

    SettingsSection(header = stringResource(R.string.am_status)) {
        SettingsCardBlock {
            Text(message.ifBlank { context.getString(R.string.am_ready) },
                color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            if (runtimeStatus.isNotBlank()) Text(runtimeStatus, style = MaterialTheme.typography.bodySmall)
            if (busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                TextButton(onClick = { operation?.cancel() }) { Text(stringResource(R.string.am_cancel_operation)) }
            }
        }
    }

    val visible = remember(records, category, filter) {
        records.filter { !it.deleted && (category == null || it.kind == category) &&
            (filter.isBlank() || it.content.contains(filter, ignoreCase = true) || it.sourceSessionId.orEmpty().contains(filter, ignoreCase = true)) }
            .sortedByDescending { it.updatedAt }
    }
    SettingsSection(header = stringResource(R.string.am_manage), footer = stringResource(R.string.am_manage_help)) {
        SettingsCardBlock {
            OutlinedTextField(value = filter, onValueChange = { filter = it; shown = 25 },
                label = { Text(stringResource(R.string.am_filter)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf<MemoryKind?>(null, MemoryKind.CORE, MemoryKind.EPISODE).forEach { kind ->
                    FilterChip(selected = category == kind, onClick = { category = kind; shown = 25 },
                        label = { Text(kindLabel(kind)) })
                }
            }
            Text(stringResource(R.string.am_count, visible.size))
            TextButton(enabled = !busy, onClick = { editing = null; editorOpen = true }) { Text(stringResource(R.string.am_add)) }
        }
        if (visible.isEmpty()) SettingsCardBlock { Text(stringResource(R.string.am_empty)) }
        visible.take(shown).forEach { record ->
            SettingsCardBlock {
                Text(kindLabel(record.kind), style = MaterialTheme.typography.labelLarge)
                Text(record.content, maxLines = 5)
                Text(stringResource(if (record.manual) R.string.am_manual else R.string.am_generated), style = MaterialTheme.typography.labelSmall)
                Text(stringResource(if (record.vector.isEmpty()) R.string.am_vector_missing else R.string.am_vector_present), style = MaterialTheme.typography.labelSmall)
                if (record.sourceSessionId != null) Text(stringResource(R.string.am_source, record.sourceSessionId), style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(enabled = !busy, onClick = { editing = record; editorOpen = true }) { Text(stringResource(R.string.am_edit)) }
                    TextButton(enabled = !busy, onClick = { deleting = record }) { Text(stringResource(R.string.am_delete)) }
                }
            }
        }
        if (shown < visible.size) SettingsCardBlock { TextButton(onClick = { shown += 25 }) { Text(stringResource(R.string.am_more)) } }
    }

    SettingsSection(header = stringResource(R.string.am_test), footer = stringResource(R.string.am_test_help)) {
        SettingsCardBlock {
            OutlinedTextField(value = query, onValueChange = { query = it; hits = null },
                label = { Text(stringResource(R.string.am_query)) }, modifier = Modifier.fillMaxWidth())
        }
        SettingsSwitchRow(title = stringResource(R.string.am_test_embedding), subtitle = stringResource(R.string.am_query_disclosure),
            checked = useEmbedding, onCheckedChange = { useEmbedding = it; hits = null }, enabled = !busy, showDivider = false)
        SettingsCardBlock {
            TextButton(enabled = !busy && query.isNotBlank(), onClick = {
                val text = query.trim().take(AdvancedMemoryPolicy.MAX_QUERY_CHARS)
                val semantic = useEmbedding
                runOperation {
                    val snapshot = store.config.value
                    var vector: List<Float>? = null
                    var fingerprint: String? = null
                    var embeddingError: String? = null
                    if (semantic) {
                        try {
                            val result = withContext(Dispatchers.IO) { AdvancedMemoryRuntime.models(context).embed(text, snapshot.embeddingEntryId) }
                            fingerprint = result.first
                            vector = result.second
                        } catch (e: CancellationException) { throw e
                        } catch (e: Exception) { embeddingError = e.message ?: context.getString(R.string.am_failed) }
                    }
                    val result = withContext(Dispatchers.IO) {
                        // search returns rejected candidates too; limit controls acceptance, NOT candidate visibility.
                        store.search(text, vector, fingerprint, limit = snapshot.maxResults, threshold = snapshot.threshold)
                    }
                    testedThreshold = snapshot.threshold
                    hits = result
                    message = if (embeddingError == null) context.getString(R.string.am_test_done, result.size)
                        else context.getString(R.string.am_lexical_fallback, embeddingError)
                }
            }) { Text(stringResource(R.string.am_run_test)) }
            if (hits != null) {
                Text(stringResource(R.string.am_test_snapshot, testedThreshold.toDouble()))
                if (hits!!.isEmpty()) Text(stringResource(R.string.am_no_candidates))
                hits!!.forEach { hit ->
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text(hit.record.content, maxLines = 5)
                    Text(stringResource(if (hit.accepted) R.string.am_accepted else R.string.am_rejected),
                        color = if (hit.accepted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.am_score, hit.score, hit.lexical,
                        hit.semantic?.let { String.format(Locale.ROOT, "%.3f", it) } ?: context.getString(R.string.am_none)))
                    Text(hit.reason, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    SettingsSection(header = stringResource(R.string.am_maintenance)) {
        SettingsCardBlock {
            Text(stringResource(R.string.am_reindex_help), style = MaterialTheme.typography.bodySmall)
            TextButton(enabled = !busy && config.embeddingEntryId.isNotBlank(), onClick = { confirmReindex = true }) {
                Text(stringResource(R.string.am_reindex))
            }
            OutlinedTextField(value = sourceSession, onValueChange = { sourceSession = it },
                label = { Text(stringResource(R.string.am_session_id)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.am_summarize_help), style = MaterialTheme.typography.bodySmall)
            TextButton(enabled = !busy && config.enabled && config.summarizerEntryId.isNotBlank() && sourceSession.isNotBlank(),
                onClick = { confirmSummarize = true }) { Text(stringResource(R.string.am_summarize)) }
        }
    }

    picker?.let { target ->
        MemoryModelDialog(
            title = stringResource(if (target == "summary") R.string.am_summarizer else R.string.am_embedding),
            entries = if (target == "summary") summaries else embeddings,
            selected = if (target == "summary") config.summarizerEntryId else config.embeddingEntryId,
            providers = providers, embeddingFirst = target == "embedding",
            onDismiss = { picker = null }, onSelect = { id ->
                picker = null
                update { if (target == "summary") it.copy(summarizerEntryId = id) else it.copy(embeddingEntryId = id) }
            },
        )
    }
    if (confirmAuto) MemoryConfirm(stringResource(R.string.am_auto_enable), stringResource(R.string.am_auto_disclosure),
        onDismiss = { confirmAuto = false }, onConfirm = { confirmAuto = false; update { it.copy(autoConsolidate = true) } })
    if (confirmReindex) MemoryConfirm(stringResource(R.string.am_reindex), stringResource(R.string.am_reindex_disclosure),
        onDismiss = { confirmReindex = false }, onConfirm = {
            confirmReindex = false
            runOperation { withContext(Dispatchers.IO) { AdvancedMemoryRuntime.reindex(context) } }
        })
    if (confirmSummarize) MemoryConfirm(stringResource(R.string.am_summarize), stringResource(R.string.am_summarize_disclosure, sourceSession.trim()),
        onDismiss = { confirmSummarize = false }, onConfirm = {
            val session = sourceSession.trim()
            confirmSummarize = false
            runOperation { withContext(Dispatchers.IO) { AdvancedMemoryRuntime.summarizeSession(context, session) } }
        })
    deleting?.let { record ->
        MemoryConfirm(stringResource(R.string.am_delete),
            stringResource(R.string.am_delete_disclosure) + "\n\n" +
                stringResource(R.string.am_record_version, record.revision) + "\n" + record.content.take(240),
            onDismiss = { deleting = null }, onConfirm = {
                deleting = null
                runOperation {
                    withContext(Dispatchers.IO) {
                        check(store.deleteIfRevision(record.id, record.revision)) {
                            context.getString(R.string.am_record_changed)
                        }
                    }
                    hits = null
                }
            })
    }
    if (editorOpen) MemoryEditor(editing, busy, onDismiss = { if (!busy) editorOpen = false }, onSave = { content, kind ->
        val original = editing
        runOperation {
            withContext(Dispatchers.IO) {
                if (original != null) {
                    check(store.updateIfRevision(original.id, original.revision, content) != null) {
                        context.getString(R.string.am_record_changed)
                    }
                } else store.save(content, kind, manual = true)
            }
            editorOpen = false
            hits = null
        }
    }, errorMessage = if (error) message else null)
}

@Composable
private fun kindLabel(kind: MemoryKind?): String = stringResource(when (kind) {
    MemoryKind.CORE -> R.string.am_core
    MemoryKind.EPISODE -> R.string.am_episode
    null -> R.string.am_all
})

@Composable
private fun MemoryConfirm(title: String, disclosure: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(disclosure) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.am_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.am_cancel)) } })
}

@Composable
private fun MemoryModelDialog(title: String, entries: List<ModelEntry>, selected: String,
    providers: ProviderConfig, embeddingFirst: Boolean,
    onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    var search by rememberSaveable(embeddingFirst) { mutableStateOf("") }
    val candidates = remember(entries, providers) {
        val instances = providers.instances.associateBy { it.id }
        entries.map { entry ->
            val model = entry.model
            val provider = instances[entry.providerInstanceId]
            MemoryModelPickerPolicy.Candidate(
                entryId = entry.id, modelName = model.displayName, modelId = model.id,
                providerLabel = provider?.label.orEmpty(), providerName = model.provider,
                providerType = provider?.providerType?.displayName.orEmpty(),
                providerId = entry.providerInstanceId, outputModalities = model.outputModalities,
            )
        }
    }
    val filtered = remember(candidates, search, embeddingFirst) {
        MemoryModelPickerPolicy.search(candidates, search, embeddingFirst)
    }
    val listState = rememberLazyListState()
    LaunchedEffect(search, embeddingFirst) { listState.scrollToItem(0) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        // The dialog supplies a finite height; only the list scrolls, never a nested scroll Column.
        Column(Modifier.fillMaxWidth().heightIn(max = 440.dp)) {
            OutlinedTextField(
                value = search, onValueChange = { search = it }, singleLine = true,
                label = { Text(stringResource(R.string.am_picker_search)) },
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    if (search.isNotEmpty()) TextButton(onClick = { search = "" }) {
                        Text(stringResource(R.string.am_picker_clear))
                    }
                },
            )
            if (embeddingFirst) Text(
                stringResource(R.string.am_picker_embedding_priority),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            // Clearing a configured model stays available even when a search has no matches.
            SettingsChoiceRow(stringResource(R.string.am_not_selected), selected.isBlank(), { onSelect("") })
            if (filtered.isEmpty()) Text(stringResource(
                if (entries.isEmpty()) R.string.am_no_models else R.string.am_picker_no_matches,
            ))
            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) {
                items(filtered, key = { it.entryId }) { candidate ->
                    SettingsChoiceRow(
                        "${candidate.modelName} · ${candidate.providerLabel}\n${candidate.modelId}",
                        selected == candidate.entryId, { onSelect(candidate.entryId) },
                    )
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.am_close)) } })
}

@Composable
private fun MemoryEditor(record: MemoryRecord?, busy: Boolean, onDismiss: () -> Unit,
    onSave: (String, MemoryKind) -> Unit, errorMessage: String?) {
    var text by remember(record?.id) { mutableStateOf(record?.content.orEmpty()) }
    var kind by remember(record?.id) { mutableStateOf(record?.kind ?: MemoryKind.CORE) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(if (record == null) R.string.am_add else R.string.am_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MemoryKind.entries.forEach { candidate ->
                        FilterChip(selected = kind == candidate, enabled = !busy && record == null, onClick = { kind = candidate }, label = { Text(kindLabel(candidate)) })
                    }
                }
                OutlinedTextField(value = text, onValueChange = { text = it }, enabled = !busy,
                    label = { Text(stringResource(R.string.am_content)) }, modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 280.dp))
                Text(stringResource(R.string.am_edit_help), style = MaterialTheme.typography.bodySmall)
                if (errorMessage != null) Text(errorMessage, color = MaterialTheme.colorScheme.error)
            }
        }, confirmButton = {
            TextButton(enabled = !busy && text.isNotBlank(), onClick = { onSave(text.trim(), kind) }) { Text(stringResource(R.string.am_save)) }
        }, dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.am_cancel)) } })
}
