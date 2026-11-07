package com.openminis.app.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.openminis.app.R
import com.openminis.app.ui.settings.getAppearancePrefs
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Local-only policy; neither the URI nor image bytes enter chat/session/provider data. */
internal object ChatBackgroundPolicy {
    const val FILE_KEY = "chat_background_file"
    const val DIM_KEY = "chat_background_dim"
    const val DEFAULT_DIM = 0.65f
    const val MAX_BYTES = 20L * 1024 * 1024
    const val MAX_EDGE = 2048
    fun dim(value: Float): Float = if (value.isFinite()) value.coerceIn(0f, 1f) else DEFAULT_DIM
    fun validName(name: String): Boolean = Regex("wallpaper-[a-f0-9-]+\\.jpg").matches(name)
    fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (maxOf(width, height).toLong() > MAX_EDGE.toLong() * sample) sample *= 2
        return sample
    }
}

internal class ChatBackgroundImportException(val messageRes: Int) : IOException()

/** One process-wide gate also serializes operations from multiple settings instances. */
internal object ChatBackgroundStore {
    private val mutex = Mutex()
    val busy = MutableStateFlow(false)
    private fun directory(context: Context) = File(context.filesDir, "chat-background")
    fun file(context: Context, name: String?): File? =
        name?.takeIf(ChatBackgroundPolicy::validName)?.let { File(directory(context), it) }

    private suspend fun <T> operation(block: suspend () -> T): T = mutex.withLock {
        busy.value = true
        try { block() } finally { busy.value = false }
    }

    suspend fun importImage(context: Context, uri: Uri) = operation {
        withContext(Dispatchers.IO) {
            val dir = directory(context)
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Private directory unavailable")
            val source = File.createTempFile("import-", ".tmp", dir)
            var candidate: File? = null
            var published = false
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    source.outputStream().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var total = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > ChatBackgroundPolicy.MAX_BYTES) {
                                throw ChatBackgroundImportException(R.string.chat_background_error_size)
                            }
                            output.write(buffer, 0, count)
                        }
                    }
                } ?: throw IOException("Cannot open selected image")
                currentCoroutineContext().ensureActive()
                val target = File(dir, "wallpaper-${UUID.randomUUID()}.jpg")
                candidate = target
                try {
                    encode(source, target)
                } catch (_: OutOfMemoryError) {
                    // Bitmap allocation is bounded, but a device can already be under pressure.
                    throw ChatBackgroundImportException(R.string.chat_background_error_image)
                }
                currentCoroutineContext().ensureActive()
                // Once publication starts, cancellation cannot leave a dangling preference.
                withContext(NonCancellable) {
                    val prefs = getAppearancePrefs(context)
                    val old = prefs.getString(ChatBackgroundPolicy.FILE_KEY, null)
                    if (!prefs.edit().putString(ChatBackgroundPolicy.FILE_KEY, target.name).commit()) {
                        // commit() may change in-memory values even on a disk failure.
                        prefs.edit().apply {
                            if (old == null) remove(ChatBackgroundPolicy.FILE_KEY)
                            else putString(ChatBackgroundPolicy.FILE_KEY, old)
                        }.commit()
                        throw IOException("Cannot save wallpaper preference")
                    }
                    published = true
                    file(context, old)?.delete()
                }
            } finally {
                source.delete()
                if (!published) candidate?.delete()
            }
        }
    }

    suspend fun remove(context: Context) = operation {
        withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                val prefs = getAppearancePrefs(context)
                val old = prefs.getString(ChatBackgroundPolicy.FILE_KEY, null)
                if (!prefs.edit().remove(ChatBackgroundPolicy.FILE_KEY).commit()) {
                    if (old != null) prefs.edit().putString(ChatBackgroundPolicy.FILE_KEY, old).commit()
                    throw IOException("Cannot remove wallpaper preference")
                }
                file(context, old)?.delete()
            }
        }
    }

    suspend fun setDim(context: Context, value: Float) = operation {
        withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                val prefs = getAppearancePrefs(context)
                val old = prefs.getFloat(ChatBackgroundPolicy.DIM_KEY, ChatBackgroundPolicy.DEFAULT_DIM)
                if (!prefs.edit().putFloat(ChatBackgroundPolicy.DIM_KEY, ChatBackgroundPolicy.dim(value)).commit()) {
                    prefs.edit().putFloat(ChatBackgroundPolicy.DIM_KEY, old).commit()
                    throw IOException("Cannot save wallpaper dim")
                }
            }
        }
    }

    private fun encode(source: File, target: File) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
            bounds.outWidth > 100_000 || bounds.outHeight > 100_000 ||
            bounds.outWidth.toLong() * bounds.outHeight > 200_000_000L
        ) throw ChatBackgroundImportException(R.string.chat_background_error_image)
        val options = BitmapFactory.Options().apply {
            inSampleSize = ChatBackgroundPolicy.sampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(source.path, options)
            ?: throw ChatBackgroundImportException(R.string.chat_background_error_image)
        var oriented: Bitmap? = null
        var flattened: Bitmap? = null
        try {
            val exif = ExifInterface(source)
            val matrix = Matrix().apply {
                when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                    ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                    ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
                }
            }
            val image = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            oriented = image
            val scale = minOf(1f, ChatBackgroundPolicy.MAX_EDGE.toFloat() / maxOf(image.width, image.height))
            val output = Bitmap.createBitmap(
                (image.width * scale).toInt().coerceAtLeast(1),
                (image.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888,
            )
            flattened = output
            Canvas(output).apply {
                drawColor(Color.WHITE)
                drawBitmap(image, null, android.graphics.Rect(0, 0, output.width, output.height),
                    android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
            }
            target.outputStream().use {
                if (!output.compress(Bitmap.CompressFormat.JPEG, 90, it)) throw IOException("JPEG encoding failed")
                it.flush()
                it.fd.sync()
            }
        } finally {
            flattened?.recycle()
            if (oriented !== decoded) oriented?.recycle()
            decoded.recycle()
        }
    }
}
