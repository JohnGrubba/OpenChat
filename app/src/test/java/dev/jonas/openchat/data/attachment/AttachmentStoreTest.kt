package dev.jonas.openchat.data.attachment

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
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
    fun `camera output file exists before the camera app writes it`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = AttachmentStore(context)

        val file = store.newCameraFile()

        assertTrue(file.exists())
        assertEquals(0L, file.length())
    }
}
