package com.openminis.app.ui.chat

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessageBackplateStyleTest {
    @Test fun noReadableWallpaperMeansInactiveInEitherTheme() {
        listOf(false, true).forEach { dark ->
            assertFalse(resolveMessageBackplateStyle(false, dark, 0.8f).active)
        }
    }

    @Test fun lightThemeUsesWhiteFillAndOpaqueBlackText() {
        val style = resolveMessageBackplateStyle(true, false, 0.8f)
        assertTrue(style.active)
        assertEquals(Color.White.copy(alpha = 0.8f), style.background)
        assertEquals(Color.Black, style.text)
    }

    @Test fun darkThemeUsesNeutralGreyFillAndOpaqueWhiteText() {
        val style = resolveMessageBackplateStyle(true, true, 0.8f)
        assertTrue(style.active)
        assertEquals(Color(0xFF303030).copy(alpha = 0.8f), style.background)
        assertEquals(Color.White, style.text)
    }

    @Test fun sharedOpacityOnlyAffectsFillAndIsSafeForInvalidValues() {
        listOf(false, true).forEach { dark ->
            val transparent = resolveMessageBackplateStyle(true, dark, 0f)
            val opaque = resolveMessageBackplateStyle(true, dark, 1f)
            assertTrue(transparent.active)
            assertEquals(0f, transparent.background.alpha, 0f)
            assertEquals(1f, opaque.background.alpha, 0f)
            assertEquals(transparent.text, opaque.text)
            assertEquals(1f, transparent.text.alpha, 0f)
            assertEquals(transparent, resolveMessageBackplateStyle(true, dark, -1f))
            assertEquals(opaque, resolveMessageBackplateStyle(true, dark, 2f))
            listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach {
                assertEquals(
                    resolveMessageBackplateStyle(true, dark, 0.8f),
                    resolveMessageBackplateStyle(true, dark, it),
                )
            }
        }
    }
}
