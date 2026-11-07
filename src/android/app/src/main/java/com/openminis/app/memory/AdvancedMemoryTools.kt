package com.openminis.app.memory

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.tools.MemoryTools
import org.json.JSONArray
import org.json.JSONObject

/** These tools only read the structured store, never raw chat history or legacy Markdown. */
object AdvancedMemoryTools {
    fun definitions(readOnly: Boolean): List<AgentToolDefinition> = buildList {
        if (!readOnly) {
            add(AgentToolDefinition("memory_write", "Save a concise core memory in the advanced memory store. Do not save secrets. User can manage it in Settings > Memory. Not a Markdown daily log.", mapOf("content" to AgentToolParam("string", "Concise reusable fact or preference, not raw conversation."), "tool_title" to AgentToolParam("string", "Brief localized action title")), listOf("content")))
            add(AgentToolDefinition("memory_update", "Correct an existing advanced memory by its exact ID. Requires its current revision from memory_get; stale updates are rejected. User-edited episodes are protected from automatic replacement.", mapOf("id" to AgentToolParam("string", "Memory ID"), "revision" to AgentToolParam("integer", "Current revision"), "content" to AgentToolParam("string", "Replacement content"), "tool_title" to AgentToolParam("string", "Brief localized action title")), listOf("id", "revision", "content")))
            add(AgentToolDefinition("memory_delete", "Delete an advanced memory by exact ID and current revision. Removes text and vector and prevents its source episode from automatic recreation. Only do so when requested or clearly obsolete.", mapOf("id" to AgentToolParam("string", "Memory ID"), "revision" to AgentToolParam("integer", "Current revision"), "tool_title" to AgentToolParam("string", "Brief localized action title")), listOf("id", "revision")))
        }
        add(AgentToolDefinition("memory_get", "Search stored core and episodic memories with configured relevance threshold. Does not search original chat history. Empty keywords list recent stored items. Scores are relevance, not truth probabilities.", mapOf("keywords" to AgentToolParam("string", "A natural-language question or keywords"), "scope" to AgentToolParam("string", "Compatibility field; searches only the advanced store"), "tool_title" to AgentToolParam("string", "Brief localized action title")), emptyList()))
    }

    fun result(hits: List<MemoryHit>, title: String): MemoryTools.ToolResult {
        val rows = JSONArray()
        hits.filter { it.accepted && !it.record.deleted }.forEach { h -> rows.put(JSONObject().put("id",h.record.id).put("revision",h.record.revision).put("kind",h.record.kind.name).put("content",h.record.content).put("score",h.score).put("reason",h.reason)) }
        return MemoryTools.ToolResult(JSONObject().put("memories",rows).put("note","Scores are relevance, not factual confidence. No raw old chats searched.").toString(),true,title)
    }

    fun mutate(context:Context, name:String, args:String): MemoryTools.ToolResult = try {
        val store=AdvancedMemoryRuntime.store(context)
        check(store.config.value.enabled) { "Advanced memory disabled" }
        val a=JSONObject(args)
        val title=a.optString("tool_title",name)
        val record = synchronized(store) {
            if(name=="memory_write") store.save(a.getString("content"),MemoryKind.CORE,manual=false)
            else {
                val current=store.records.value.firstOrNull { it.id==a.getString("id") && !it.deleted } ?: error("Memory no longer exists")
                check(current.revision==a.getLong("revision")) { "Memory changed; search again before modifying it" }
                if(name=="memory_delete") { check(store.deleteIfRevision(current.id,current.revision)) { "Memory changed; search again" }; null }
                else { check(name=="memory_update"); store.updateIfRevision(current.id,current.revision,a.getString("content")) ?: error("Memory changed; search again") }
            }
        }
        MemoryTools.ToolResult(JSONObject().put("ok",true).put("id",record?.id).put("revision",record?.revision).put("deleted",name=="memory_delete").toString(),true,title)
    } catch(e:Exception) { MemoryTools.ToolResult("Advanced memory operation failed: ${e.message?.take(200)}",false,name) }
}
