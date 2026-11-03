package dev.jonas.openchat.data.attachment

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import dev.jonas.openchat.core.model.ContentBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

class AttachmentStore(private val context: Context) {
    private val attachmentsDir = File(context.filesDir, "attachments").apply { mkdirs() }

    /**
     * Camera output lives on external app storage when possible. OEM camera apps are much happier
     * writing to external cache than to the app's private internal cache, and this directory is
     * still covered by the FileProvider paths. The internal cache is only a fallback for devices
     * where external storage is unavailable.
     */
    private val cameraDir: File =
        File(context.getExternalCacheDir() ?: context.cacheDir, "camera").apply { mkdirs() }

    val documents = DocumentExtractor(context)

    /**
     * Returns a fresh path for the camera app to write into. The file is deliberately *not*
     * created: several camera apps (Samsung's in particular) refuse to write when the output
     * file already exists, which resulted in empty captures. FileProvider creates the file on
     * the camera app's first write.
     */
    fun newCameraFile(): File {
        cameraDir.mkdirs()
        // A previous capture that was abandoned should never be confused with this one.
        cameraDir.listFiles()?.forEach { runCatching { it.delete() } }
        return File(cameraDir, "photo_${UUID.randomUUID()}.jpg")
    }

    /**
     * Most recent non-empty capture in the camera directory. Used as a recovery path when the
     * activity was recreated while the camera app was in the foreground and the in-memory path
     * was lost.
     */
    fun latestCameraFile(): File? = cameraDir.listFiles()
        ?.filter { it.isFile && it.length() > 0 }
        ?.maxByOrNull { it.lastModified() }

    /**
     * Waits until the camera app has finished writing [file]. Some camera apps report success
     * before the bytes are flushed (or write the JPEG incrementally), so a non-zero length alone
     * is not enough: wait for the size to settle and for the JPEG header to decode.
     */
    suspend fun awaitCameraCapture(file: File, timeoutMs: Long = 6_000): Boolean =
        withContext(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + timeoutMs
            var lastSize = -1L
            var stableSince = 0L
            while (System.currentTimeMillis() < deadline) {
                val size = if (file.exists()) file.length() else 0L
                if (size > 0 && size == lastSize) {
                    if (stableSince == 0L) stableSince = System.currentTimeMillis()
                    if (System.currentTimeMillis() - stableSince >= 250 && isDecodable(file)) {
                        return@withContext true
                    }
                } else {
                    lastSize = size
                    stableSince = 0L
                }
                delay(100)
            }
            file.exists() && file.length() > 0 && isDecodable(file)
        }

    private fun isDecodable(file: File): Boolean = runCatching {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        options.outWidth > 0 && options.outHeight > 0
    }.getOrDefault(false)

    private fun openInput(uri: Uri): InputStream? {
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            val path = uri.path ?: return null
            return runCatching { FileInputStream(path) }.getOrNull()
        }
        return context.contentResolver.openInputStream(uri)
    }

    suspend fun importImage(uri: Uri): Result<ContentBlock.Image> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            var name = "image"
            if (uri.scheme == ContentResolver.SCHEME_FILE) {
                // Camera captures arrive as file:// URIs. Skip ContentResolver for those: a few
                // OEM resolver implementations behave inconsistently with file URIs.
                name = uri.lastPathSegment ?: name
            } else {
                resolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0) name = cursor.getString(index) ?: name
                    }
                }
            }

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            openInput(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) error("That image could not be read.")

            var sample = 1
            while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            var bitmap = openInput(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: error("That image could not be read.")

            val rotation = runCatching {
                openInput(uri)?.use { stream ->
                    ExifInterface(stream).rotationDegrees
                } ?: 0
            }.getOrDefault(0)
            if (rotation != 0) {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (rotated != bitmap) bitmap.recycle()
                bitmap = rotated
            }

            val file = File(attachmentsDir, "img_${UUID.randomUUID()}.jpg")
            writeJpeg(bitmap, file)
            bitmap.recycle()

            ContentBlock.Image(path = file.absolutePath, mimeType = "image/jpeg", name = name)
        }
    }

    /**
     * Saves a bitmap that is already in memory, such as the thumbnail some camera apps return when
     * they ignore EXTRA_OUTPUT. Takes ownership of [bitmap] and recycles it.
     */
    suspend fun importBitmap(bitmap: Bitmap, name: String = "photo.jpg"): Result<ContentBlock.Image> =
        withContext(Dispatchers.IO) {
            try {
                val file = File(attachmentsDir, "img_${UUID.randomUUID()}.jpg")
                writeJpeg(bitmap, file)
                Result.success(
                    ContentBlock.Image(path = file.absolutePath, mimeType = "image/jpeg", name = name),
                )
            } catch (t: Throwable) {
                Result.failure(t)
            } finally {
                bitmap.recycle()
            }
        }

    private fun writeJpeg(bitmap: Bitmap, file: File) {
        val longest = maxOf(bitmap.width, bitmap.height)
        val scaled = if (longest > MAX_DIMENSION) {
            val scale = MAX_DIMENSION.toFloat() / longest
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            bitmap
        }
        val flattened = if (scaled.hasAlpha()) {
            Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888).also { out ->
                Canvas(out).apply {
                    drawColor(Color.WHITE)
                    drawBitmap(scaled, 0f, 0f, null)
                }
            }
        } else {
            scaled
        }
        try {
            FileOutputStream(file).use { out ->
                flattened.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
        } finally {
            if (flattened !== scaled) flattened.recycle()
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    suspend fun importDocument(uri: Uri): Result<ContentBlock.Document> =
        documents.extract(uri).map { extracted ->
            ContentBlock.Document(
                name = extracted.name,
                mimeType = extracted.mimeType,
                text = extracted.text,
                truncated = extracted.truncated,
            )
        }

    fun readBase64(path: String): String? = runCatching {
        val file = File(path)
        if (!file.exists()) return null
        Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
    }.getOrNull()

    fun delete(path: String) {
        runCatching { File(path).delete() }
    }

    fun deleteAttachment(block: ContentBlock) {
        when (block) {
            is ContentBlock.Image -> delete(block.path)
            else -> Unit
        }
    }

    companion object {
        private const val MAX_DIMENSION = 1568
        private const val JPEG_QUALITY = 86
    }
}
