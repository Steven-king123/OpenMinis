package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatBackgroundPolicyTest {
    @Test fun overlayIsFiniteAndClamped() {
        assertEquals(0.65f, ChatBackgroundPolicy.DEFAULT_DIM, 0f)
        assertEquals(0.65f, ChatBackgroundPolicy.dim(Float.NaN), 0f)
        assertEquals(0.65f, ChatBackgroundPolicy.dim(Float.POSITIVE_INFINITY), 0f)
        assertEquals(0.65f, ChatBackgroundPolicy.dim(Float.NEGATIVE_INFINITY), 0f)
        assertEquals(0f, ChatBackgroundPolicy.dim(-1f), 0f)
        assertEquals(1f, ChatBackgroundPolicy.dim(2f), 0f)
        assertEquals(0.4f, ChatBackgroundPolicy.dim(0.4f), 0f)
    }

    @Test fun onlyGeneratedPrivateNamesAreAccepted() {
        assertTrue(ChatBackgroundPolicy.validName("wallpaper-00000000-0000-0000-0000-000000000000.jpg"))
        listOf("../wallpaper-a.jpg", "/wallpaper-a.jpg", "wallpaper-a.jpg/../x",
            "content://image", "wallpaper-a.png", "import-a.tmp", "").forEach {
            assertFalse(it, ChatBackgroundPolicy.validName(it))
        }
    }

    @Test fun boundedPowerOfTwoDecodeForPortraitAndLandscape() {
        listOf(1 to 1, 2048 to 2048, 2049 to 1, 1 to 2049,
            4032 to 3024, 3024 to 4032, 100000 to 1).forEach { (width, height) ->
            val sample = ChatBackgroundPolicy.sampleSize(width, height)
            assertTrue(sample > 0)
            assertEquals(0, sample and (sample - 1))
            assertTrue(maxOf(width, height).toLong() <= ChatBackgroundPolicy.MAX_EDGE.toLong() * sample)
        }
        assertEquals(1, ChatBackgroundPolicy.sampleSize(2048, 1024))
        assertEquals(2, ChatBackgroundPolicy.sampleSize(2049, 1024))
        assertEquals(20L * 1024 * 1024, ChatBackgroundPolicy.MAX_BYTES)
    }
}
