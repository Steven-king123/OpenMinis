package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/** Rendering-only policy shared by user text, assistant text, thinking and preview. */
data class MessageBackplateStyle(
    val active: Boolean,
    val background: Color,
    val text: Color,
)

/** Only ordinary markdown text reads this; code/link palettes remain independent. */
internal val LocalMessageTextColor = compositionLocalOf<Color?> { null }

internal fun resolveMessageBackplateStyle(
    hasImage: Boolean,
    dark: Boolean,
    opacity: Float,
): MessageBackplateStyle {
    val alpha = if (opacity.isFinite()) opacity.coerceIn(0f, 1f) else 0.8f
    return MessageBackplateStyle(
        active = hasImage,
        background = (if (dark) Color(0xFF303030) else Color.White).copy(alpha = alpha),
        text = if (dark) Color.White else Color.Black,
    )
}

@Composable
fun messageBackplateStyle(): MessageBackplateStyle {
    // hasImage is based on the background store's IO-validated readable file.
    val state by rememberChatBackgroundState()
    return resolveMessageBackplateStyle(
        hasImage = state.hasImage,
        // Follow the actual app theme (including an explicit appearance override).
        dark = MaterialTheme.colorScheme.background.luminance() < 0.5f,
        opacity = state.messageOpacity,
    )
}

/** No input/selection/alpha layer: only a rounded fill and normal-text palette. */
@Composable
fun ChatMessageBackplate(content: @Composable () -> Unit) {
    val style = messageBackplateStyle()
    CompositionLocalProvider(
        LocalMessageTextColor provides if (style.active) style.text else null,
        LocalContentColor provides if (style.active) style.text else LocalContentColor.current,
    ) {
        // Keep the content's composition identity stable when wallpaper is toggled.
        // Do not clip: selection handles, code styles and media keep their behaviour.
        Box(
            modifier = if (style.active) {
                Modifier.background(style.background, RoundedCornerShape(16.dp)).padding(12.dp)
            } else Modifier,
        ) {
            content()
        }
    }
}
