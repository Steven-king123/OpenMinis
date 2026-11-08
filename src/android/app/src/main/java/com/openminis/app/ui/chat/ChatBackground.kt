package com.openminis.app.ui.chat

import android.content.SharedPreferences
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.openminis.app.R
import com.openminis.app.ui.settings.SettingsSection
import com.openminis.app.ui.settings.SettingsValueRow
import com.openminis.app.ui.settings.getAppearancePrefs
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Only a private app File and overlay strength, never a bitmap or provider attachment. */
data class ChatBackgroundState(val file: File?, val dim: Float, val messageOpacity: Float = ChatBackgroundPolicy.DEFAULT_MESSAGE_OPACITY) {
    val hasImage: Boolean get() = file != null
}

/**
 * Shared appearance_prefs observation. Register before refreshing to avoid a missed write;
 * each mounted chat/settings instance unregisters automatically when disposed.
 * File existence is checked on IO at mount and filename changes, never during composition.
 */
@Composable
fun rememberChatBackgroundState(): State<ChatBackgroundState> {
    val context = LocalContext.current.applicationContext
    val prefs = remember(context) { getAppearancePrefs(context) }
    fun readDim() = ChatBackgroundPolicy.dim(
        prefs.getFloat(ChatBackgroundPolicy.DIM_KEY, ChatBackgroundPolicy.DEFAULT_DIM),
    )
    fun readMessageOpacity() = ChatBackgroundPolicy.messageOpacity(prefs.getFloat(
        ChatBackgroundPolicy.MESSAGE_OPACITY_KEY, ChatBackgroundPolicy.DEFAULT_MESSAGE_OPACITY))
    val state = remember(prefs) { mutableStateOf(ChatBackgroundState(null, readDim(), readMessageOpacity())) }
    var filename by remember(prefs) { mutableStateOf(prefs.getString(ChatBackgroundPolicy.FILE_KEY, null)) }
    var refresh by remember(prefs) { mutableIntStateOf(0) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == ChatBackgroundPolicy.FILE_KEY) {
                filename = prefs.getString(ChatBackgroundPolicy.FILE_KEY, null)
                if (filename == null) state.value = state.value.copy(file = null)
                refresh++
            }
            if (key == null || key == ChatBackgroundPolicy.DIM_KEY) {
                state.value = state.value.copy(dim = readDim())
            }
            if (key == null || key == ChatBackgroundPolicy.MESSAGE_OPACITY_KEY) {
                state.value = state.value.copy(messageOpacity = readMessageOpacity())
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        filename = prefs.getString(ChatBackgroundPolicy.FILE_KEY, null)
        state.value = state.value.copy(dim = readDim(), messageOpacity = readMessageOpacity())
        refresh++
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    LaunchedEffect(prefs, filename, refresh) {
        val checked = withContext(Dispatchers.IO) {
            ChatBackgroundStore.file(context, filename)?.takeIf { it.isFile && it.canRead() }
        }
        state.value = state.value.copy(file = checked)
    }
    return state
}

/**
 * Place as FIRST child of an existing chat content Box, using Modifier.matchParentSize().
 * It consumes no input, changes no controls and draws nothing when no wallpaper is set.
 * Keep opaque chat-list backgrounds transparent when state.hasImage, not toolbar/composer.
 */
@Composable
fun ChatBackgroundLayer(modifier: Modifier = Modifier) {
    val state by rememberChatBackgroundState()
    Wallpaper(state.file, state.dim, modifier)
}

@Composable
private fun Wallpaper(file: File?, dim: Float, modifier: Modifier) {
    Box(modifier) {
        if (file != null) {
            AsyncImage(
                model = file,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface.copy(alpha = dim)))
        }
    }
}

/** Drop directly into AppearanceScreen's SettingsScaffold content. No extra Scaffold/scroll. */
@Composable
fun ChatBackgroundSettings() {
    val context = LocalContext.current
    val application = context.applicationContext
    val state by rememberChatBackgroundState()
    val busy by ChatBackgroundStore.busy.collectAsState()
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var draftDim by remember { mutableFloatStateOf(state.dim) }
    var dragging by remember { mutableStateOf(false) }
    var draftMessageOpacity by remember { mutableFloatStateOf(state.messageOpacity) }
    var draggingMessageOpacity by remember { mutableStateOf(false) }
    LaunchedEffect(state.dim) { if (!dragging) draftDim = state.dim }
    LaunchedEffect(state.messageOpacity) { if (!draggingMessageOpacity) draftMessageOpacity = state.messageOpacity }

    fun error(message: Int) {
        Toast.makeText(context, context.getString(message), Toast.LENGTH_LONG).show()
    }
    fun runOperation(block: suspend () -> Unit) {
        working = true
        scope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: ChatBackgroundImportException) {
                error(failure.messageRes)
            } catch (_: Exception) {
                error(R.string.chat_background_error_generic)
            } finally {
                working = false
                dragging = false
                draggingMessageOpacity = false
                draftMessageOpacity = ChatBackgroundPolicy.messageOpacity(getAppearancePrefs(application).getFloat(
                    ChatBackgroundPolicy.MESSAGE_OPACITY_KEY, ChatBackgroundPolicy.DEFAULT_MESSAGE_OPACITY))
                draftDim = ChatBackgroundPolicy.dim(
                    getAppearancePrefs(application).getFloat(ChatBackgroundPolicy.DIM_KEY, ChatBackgroundPolicy.DEFAULT_DIM),
                )
            }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        picking = false
        if (uri != null) runOperation { ChatBackgroundStore.importImage(application, uri) }
    }
    val enabled = !busy && !working && !picking
    SettingsSection(
        header = stringResource(R.string.chat_background_title),
        footer = stringResource(R.string.chat_background_footer),
    ) {
        SettingsValueRow(
            title = stringResource(R.string.chat_background_choose),
            value = stringResource(
                if (busy || working) R.string.chat_background_working
                else if (state.hasImage) R.string.chat_background_selected
                else R.string.chat_background_none,
            ),
            icon = Icons.Outlined.Image,
            onClick = if (enabled) ({
                picking = true
                try { picker.launch("image/*") }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    picking = false
                    error(R.string.chat_background_error_picker)
                }
            }) else null,
            showDivider = state.hasImage,
        )
        if (state.hasImage) {
            SettingsValueRow(
                title = stringResource(R.string.chat_background_remove),
                value = "",
                icon = Icons.Outlined.DeleteOutline,
                onClick = if (enabled) ({ runOperation { ChatBackgroundStore.remove(application) } }) else null,
            )
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text(
                    stringResource(R.string.chat_background_dim, (draftDim * 100).roundToInt()),
                    style = MaterialTheme.typography.bodyMedium,
                )
                val sliderDescription = stringResource(R.string.chat_background_dim_accessibility)
                Slider(
                    value = draftDim,
                    onValueChange = { dragging = true; draftDim = it },
                    onValueChangeFinished = {
                        val value = draftDim
                        runOperation { ChatBackgroundStore.setDim(application, value) }
                    },
                    enabled = enabled,
                    valueRange = 0f..1f,
                    modifier = Modifier.semantics { contentDescription = sliderDescription },
                )
                Text(stringResource(R.string.chat_message_opacity, (draftMessageOpacity * 100).roundToInt()),
                    style = MaterialTheme.typography.bodyMedium)
                val messageOpacityDescription = stringResource(R.string.chat_message_opacity_accessibility)
                Slider(
                    value = draftMessageOpacity,
                    onValueChange = { draggingMessageOpacity = true; draftMessageOpacity = it },
                    onValueChangeFinished = {
                        val value = draftMessageOpacity
                        runOperation { ChatBackgroundStore.setMessageOpacity(application, value) }
                    },
                    enabled = enabled, valueRange = 0f..1f,
                    modifier = Modifier.semantics { contentDescription = messageOpacityDescription },
                )
                Text(stringResource(R.string.chat_message_opacity_help), style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 8.dp))
                Text(
                    stringResource(R.string.chat_background_preview),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Box(
                    Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface),
                ) {
                    Wallpaper(state.file, draftDim, Modifier.fillMaxSize())
                    val previewStyle = resolveMessageBackplateStyle(state.hasImage,
                        MaterialTheme.colorScheme.background.luminance() < 0.5f, draftMessageOpacity)
                    val plate = previewStyle.background
                    val ink = previewStyle.text
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.chat_message_preview_user), color = ink,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.align(Alignment.End).background(plate, RoundedCornerShape(16.dp)).padding(12.dp))
                        Text(stringResource(R.string.chat_message_preview_thinking), color = ink,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.background(plate, RoundedCornerShape(16.dp)).padding(12.dp))
                        Text(stringResource(R.string.chat_background_preview_message), color = ink,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.background(plate, RoundedCornerShape(16.dp)).padding(12.dp))
                    }
                }
            }
        }
    }
}
