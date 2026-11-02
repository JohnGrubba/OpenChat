package dev.jonas.openchat.data.attachment

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
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class AttachmentStore(private val context: Context) {
    private val attachmentsDir = File(context.filesDir, "attachments").apply { mkdirs() }
    private val cameraDir = File(context.cacheDir, "camera").apply { mkdirs() }
    val documents = DocumentExtractor(context)

    fun newCameraFile(): File = File(cameraDir, "photo_${UUID.randomUUID()}.jpg").apply {
        // Create the file up front so we can reliably tell whether the camera app
        // actually wrote anything into the EXTRA_OUTPUT Uri.
        runCatching { createNewFile() }
    }

    suspend fun importImage(uri: Uri): Result<ContentBlock.Image> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            var name = "image"
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) name = cursor.getString(index) ?: name
                }
            }

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) error("That image could not be read.")

            var sample = 1
            while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            var bitmap = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: error("That image could not be read.")

            val rotation = runCatching {
                resolver.openInputStream(uri)?.use { stream ->
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
