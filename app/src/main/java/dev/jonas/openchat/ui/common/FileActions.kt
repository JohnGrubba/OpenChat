package dev.jonas.openchat.ui.common

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import java.io.File

object FileActions {

    fun copy(context: Context, text: String, label: String = "Copied") {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("OpenChat", text))
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            Toast.makeText(context, label, Toast.LENGTH_SHORT).show()
        }
    }

    fun share(context: Context, text: String, subject: String = "OpenChat") {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, "Share"))
    }

    fun saveText(
        context: Context,
        fileName: String,
        content: String,
        mimeType: String = "text/plain",
    ): Boolean = runCatching {
        val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifBlank { "openchat.txt" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                values,
            ) ?: error("Could not create the file")
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(content.toByteArray(Charsets.UTF_8))
            } ?: error("Could not write the file")
        } else {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: context.filesDir
            val file = File(dir, safeName)
            file.writeText(content)
        }
        Toast.makeText(
            context,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                "Saved to Downloads"
            } else {
                "Saved"
            },
            Toast.LENGTH_SHORT,
        ).show()
        true
    }.getOrElse {
        Toast.makeText(context, it.message ?: "Could not save", Toast.LENGTH_SHORT).show()
        false
    }

    fun openUrl(context: Context, url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }
}
