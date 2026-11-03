package dev.jonas.openchat.data.attachment

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AttachmentStoreTest {
    @Test
    fun `large images are downscaled and stored as jpeg`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = File(context.cacheDir, "large.png")
        val bitmap = Bitmap.createBitmap(2400, 1600, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.RED)
        FileOutputStream(source).use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()

        val store = AttachmentStore(context)
        val image = store.importImage(Uri.fromFile(source)).getOrThrow()
        val file = File(image.path)
        assertTrue(file.exists())
        assertEquals("image/jpeg", image.mimeType)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(image.path, options)
        assertTrue(options.outWidth <= 1568)
        assertTrue(options.outHeight <= 1568)
        assertTrue(store.readBase64(image.path)!!.isNotBlank())
    }

    @Test
    fun `in-memory bitmaps used as camera fallback are stored as jpeg`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = AttachmentStore(context)
        val bitmap = Bitmap.createBitmap(2400, 1600, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLUE)

        val image = store.importBitmap(bitmap).getOrThrow()

        assertEquals("image/jpeg", image.mimeType)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(image.path, options)
        assertTrue(options.outWidth <= 1568)
        assertTrue(options.outHeight <= 1568)
        assertTrue(store.readBase64(image.path)!!.isNotBlank())
    }

    @Test
    fun `camera captures imported from a file uri are stored`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = AttachmentStore(context)
        val source = store.newCameraFile()
        val bitmap = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.CYAN)
        FileOutputStream(source).use {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)
        }
        bitmap.recycle()

        val image = store.importImage(Uri.fromFile(source)).getOrThrow()

        assertEquals("image/jpeg", image.mimeType)
        assertEquals(source.name, image.name)
        assertTrue(File(image.path).exists())
        source.delete()
        File(image.path).delete()
        Unit
    }

    @Test
    fun `camera output path is reserved without creating the file`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = AttachmentStore(context)

        val file = store.newCameraFile()

        // Some camera apps refuse to overwrite an already existing output file.
        assertFalse(file.exists())
        assertEquals("jpg", file.extension)
    }

    @Test
    fun `camera output is writable through the file provider without pre-creating it`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = AttachmentStore(context)
        val file = store.newCameraFile()

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        assertEquals("content", uri.scheme)
        assertFalse(file.exists())

        context.contentResolver.openOutputStream(uri, "w")?.use { out ->
            out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()))
        }

        assertTrue(file.exists())
        assertTrue(file.length() > 0)
        file.delete()
    }

    @Test
    fun `awaitCameraCapture waits for a delayed write to finish`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = AttachmentStore(context)
        val file = store.newCameraFile()

        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.GREEN)
        val bytes = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            bitmap.recycle()
            out.toByteArray()
        }

        val writer = launch(Dispatchers.IO) {
            delay(300)
            FileOutputStream(file).use { it.write(bytes) }
        }

        assertTrue(store.awaitCameraCapture(file, timeoutMs = 5_000))
        writer.join()
        assertTrue(store.latestCameraFile()?.exists() == true)
        file.delete()
        Unit
    }
}
