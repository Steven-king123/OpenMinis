package com.openminis.app.ui.chat

import org.robolectric.RuntimeEnvironment
import android.content.Context
import com.openminis.app.ui.settings.getAppearancePrefs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ChatGlassPersistenceTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    @Before fun reset() { getAppearancePrefs(context).edit().clear().commit() }
    @Test fun freshAndLegacyInstallsKeepResizingOff() {
        val prefs = getAppearancePrefs(context)
        prefs.edit().putFloat(ChatBackgroundPolicy.DIM_KEY, 0.4f).commit()
        assertFalse(prefs.getBoolean(ChatBackgroundPolicy.RESIZE_WITH_INPUT_KEY, ChatBackgroundPolicy.DEFAULT_RESIZE_WITH_INPUT))
        assertFalse(prefs.getBoolean(ChatBackgroundPolicy.GLASS_ENABLED_KEY, ChatBackgroundPolicy.DEFAULT_GLASS_ENABLED))
    }
    @Test fun glassAndResizePersistWithoutChangingMessageOpacityOrWallpaper() = runBlocking {
        val prefs = getAppearancePrefs(context)
        val name = "wallpaper-00000000-0000-0000-0000-000000000000.jpg"
        prefs.edit().putString(ChatBackgroundPolicy.FILE_KEY, name).putFloat(ChatBackgroundPolicy.MESSAGE_OPACITY_KEY, 0.31f).commit()
        ChatBackgroundStore.setGlassEnabled(context, true)
        assertTrue(prefs.getBoolean(ChatBackgroundPolicy.GLASS_ENABLED_KEY, false))
        assertFalse(prefs.getBoolean(ChatBackgroundPolicy.RESIZE_WITH_INPUT_KEY, false))
        ChatBackgroundStore.setResizeWithInput(context, true)
        ChatBackgroundStore.setGlassEnabled(context, false)
        assertTrue(prefs.getBoolean(ChatBackgroundPolicy.RESIZE_WITH_INPUT_KEY, false))
        assertFalse(prefs.getBoolean(ChatBackgroundPolicy.GLASS_ENABLED_KEY, true))
        assertEquals(name, prefs.getString(ChatBackgroundPolicy.FILE_KEY, null))
        assertEquals(0.31f, prefs.getFloat(ChatBackgroundPolicy.MESSAGE_OPACITY_KEY, 0f), 0f)
        ChatBackgroundStore.setResizeWithInput(context, false)
        assertFalse(prefs.getBoolean(ChatBackgroundPolicy.RESIZE_WITH_INPUT_KEY, true))
        assertFalse(ChatBackgroundStore.busy.value)
    }
}
