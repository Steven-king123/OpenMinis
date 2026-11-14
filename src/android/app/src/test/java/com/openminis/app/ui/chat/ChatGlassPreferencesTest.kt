package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGlassPreferencesTest {
    @Test fun defaultsAreOptInEvenWithoutWallpaper() {
        val state = ChatBackgroundState(file = null, dim = ChatBackgroundPolicy.DEFAULT_DIM)
        assertFalse(state.hasImage)
        assertFalse(state.glassEnabled)
        assertFalse(state.resizeWithInput)
        assertFalse(ChatBackgroundPolicy.DEFAULT_GLASS_ENABLED)
        assertFalse(ChatBackgroundPolicy.DEFAULT_RESIZE_WITH_INPUT)
    }

    @Test fun glassResizeAndMessageOpacityAreIndependent() {
        val original = ChatBackgroundState(null, 0.4f, messageOpacity = 0.23f)
        val glassOnly = original.copy(glassEnabled = true)
        assertTrue(glassOnly.glassEnabled)
        assertFalse(glassOnly.resizeWithInput)
        val resizeOnly = original.copy(resizeWithInput = true)
        assertFalse(resizeOnly.glassEnabled)
        assertTrue(resizeOnly.resizeWithInput)
        listOf(glassOnly, resizeOnly, glassOnly.copy(resizeWithInput = true)).forEach {
            assertEquals(original.messageOpacity, it.messageOpacity, 0f)
            assertEquals(original.dim, it.dim, 0f)
            assertFalse(it.hasImage)
        }
    }

    @Test fun persistedSwitchesUseDistinctKeys() {
        assertNotEquals(ChatBackgroundPolicy.GLASS_ENABLED_KEY, ChatBackgroundPolicy.RESIZE_WITH_INPUT_KEY)
        assertNotEquals(ChatBackgroundPolicy.GLASS_ENABLED_KEY, ChatBackgroundPolicy.MESSAGE_OPACITY_KEY)
        assertNotEquals(ChatBackgroundPolicy.RESIZE_WITH_INPUT_KEY, ChatBackgroundPolicy.FILE_KEY)
    }
}
