package com.openminis.app.memory

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.Rule
import org.junit.rules.TemporaryFolder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AdvancedMemoryIntegrationPolicyTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun staleDialogCannotEditOrDeleteNewRevision() {
        val s=AdvancedMemoryStore(temporary.newFolder())
        val first=s.save("original")
        val next=s.updateIfRevision(first.id,first.revision,"new")!!
        assertNull(s.updateIfRevision(first.id,first.revision,"stale"))
        assertFalse(s.deleteIfRevision(first.id,first.revision))
        assertEquals("new",s.records.value.single().content)
        assertTrue(s.deleteIfRevision(next.id,next.revision))
    }
    @Test fun committedSnapshotIsIdempotentButNewSnapshotUpdates() {
        val s=AdvancedMemoryStore(temporary.newFolder())
        s.updateConfig(s.config.value.copy(enabled=true))
        val epoch=s.config.value.epoch
        val a=s.commitEpisode("session","first summary",epoch,"snapshot-a")!!
        val b=s.commitEpisode("session","should not overwrite",epoch,"snapshot-a")!!
        assertEquals(a,b)
        val c=s.commitEpisode("session","next summary",epoch,"snapshot-b")!!
        assertEquals(a.id,c.id)
        assertTrue(c.revision>a.revision)
        s.delete(c.id)
        assertNull(s.commitEpisode("session","resurrection",epoch,"snapshot-c"))
    }
    @Test fun summaryVisibleTextExcludesHiddenAttachmentAndThoughts() {
        val text="""[{"type":"text","value":"Visible <user-attached-files>private path</user-attached-files><think>hidden</think> text"},{"type":"tool_use","name":"secret"}]"""
        val visible=AdvancedMemoryRuntime.visibleText(text)
        assertTrue(visible.contains("Visible"))
        assertFalse(visible.contains("private"))
        assertFalse(visible.contains("hidden"))
        assertFalse(visible.contains("secret"))
    }
}
