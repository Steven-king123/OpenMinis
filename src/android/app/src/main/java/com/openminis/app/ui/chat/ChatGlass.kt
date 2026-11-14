package com.openminis.app.ui.chat

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource

/** One state per chat (or preview), never a process-wide render layer. */
@Stable
data class ChatGlassContext(val enabled: Boolean, val hazeState: HazeState?)

val LocalChatGlass = staticCompositionLocalOf { ChatGlassContext(false, null) }

@Composable
fun chatGlassIsActive(): Boolean = LocalChatGlass.current.enabled

/** Provides preferences only; does not create a source around [content]. */
@Composable
fun ChatGlassHost(content: @Composable () -> Unit) {
    val background by rememberChatBackgroundState()
    ChatGlassHost(enabled = background.glassEnabled, content = content)
}

/** Explicit overload for isolated settings previews, with its own HazeState. */
@Composable
fun ChatGlassHost(enabled: Boolean, content: @Composable () -> Unit) {
    val state = remember { HazeState() }
    val context = remember(enabled, state) { ChatGlassContext(enabled, state) }
    CompositionLocalProvider(LocalChatGlass provides context, content = content)
}

/**
 * Attach ONLY to wallpaper/message regions, not an ancestor of glass surfaces.
 * Separate sources may share the state: wallpaper zIndex=0f, messages zIndex=1f.
 * Haze's zIndex orders recorded sources; Compose zIndex still controls actual drawing.
 * Do not register the same pixels twice or include toolbar/composer in a source.
 */
@Composable
fun Modifier.chatGlassSource(zIndex: Float = 0f): Modifier {
    val glass = LocalChatGlass.current
    return if (glass.enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && glass.hazeState != null) {
        hazeSource(state = glass.hazeState, zIndex = zIndex)
    } else this
}

/**
 * Blurs registered background pixels, NEVER this surface's foreground content.
 * The explicit state argument selects Haze's background-blur overload.
 * OFF retains the caller's existing background; pre-31 uses an opaque safe surface.
 * Material Surface/TopAppBar/TextField containers inside must be transparent when ON.
 */
@Composable
fun Modifier.chatGlassSurface(shape: Shape, fallback: Color): Modifier {
    val glass = LocalChatGlass.current
    if (!glass.enabled) return background(fallback, shape)
    val surface = MaterialTheme.colorScheme.surface.copy(alpha = 1f)
    val safeFallback = fallback.compositeOver(surface).copy(alpha = 1f)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || glass.hazeState == null) {
        return clip(shape).background(safeFallback)
    }
    val dark = surface.luminance() < 0.5f
    val style = HazeStyle(
        backgroundColor = surface,
        tint = HazeTint(surface.copy(alpha = if (dark) 0.68f else 0.55f)),
        blurRadius = 24.dp,
        noiseFactor = 0.02f,
        fallbackTint = HazeTint(safeFallback),
    )
    return clip(shape)
        .hazeEffect(state = glass.hazeState, style = style)
        .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), shape)
}

/** Short alias for callers which prefer the material name. */
@Composable
fun Modifier.chatGlass(shape: Shape, fallback: Color): Modifier = chatGlassSurface(shape, fallback)
