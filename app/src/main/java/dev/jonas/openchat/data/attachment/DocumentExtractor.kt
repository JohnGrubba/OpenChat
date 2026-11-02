package dev.jonas.openchat.data.attachment

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

data class ExtractedDocument(
    val name: String,
    val mimeType: String,
    val text: String,
    val truncated: Boolean,
)

class DocumentExtractor(private val context: Context) {

    init {
        runCatching { PDFBoxResourceLoader.init(context.applicationContext) }
    }

    suspend fun extract(uri: Uri): Result<ExtractedDocument> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            var name = "attachment"
            var size = -1L
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
            if (size > MAX_INPUT_BYTES) {
                error("That file is too large (limit 15 MB).")
            }
            val mime = resolver.getType(uri).orEmpty().lowercase()
            val bytes = resolver.openInputStream(uri)?.use { it.readLimited(MAX_INPUT_BYTES) }
                ?: error("Could not open that file.")
            if (bytes.isEmpty()) error("That file is empty.")

            extractBytes(name, mime, bytes)
        }
    }

    /** Exposed for tests and for callers that already have the bytes. */
    internal fun extractBytes(name: String, mime: String, bytes: ByteArray): ExtractedDocument {
        if (bytes.isEmpty()) error("That file is empty.")
        val extension = name.substringAfterLast('.', "").lowercase()
        val text = when {
            extension == "pdf" || mime == "application/pdf" -> extractPdf(bytes)
            extension == "docx" -> extractZipXml(bytes, DOCX_ENTRY)
            extension == "pptx" -> extractPptx(bytes)
            extension == "xlsx" -> extractXlsx(bytes)
            extension == "odt" -> extractZipXml(bytes, "content.xml")
            extension == "html" || extension == "htm" || mime.contains("html") ->
                Jsoup.parse(String(bytes, Charsets.UTF_8)).body()?.text().orEmpty()
            extension in TEXT_EXTENSIONS || mime.startsWith("text/") ||
                mime.contains("json") || mime.contains("xml") || mime.contains("csv") ->
                decodeText(bytes)
            else -> {
                val decoded = decodeText(bytes)
                if (decoded.count { it == '\u0000' } > 0) {
                    error("Unsupported file type. Attach PDF, DOCX, PPTX, XLSX, or a text file.")
                }
                decoded
            }
        }.replace("\r\n", "\n").trim()

        if (text.isBlank()) {
            error("No readable text was found in that file.")
        }
        val truncated = text.length > MAX_CHARS
        return ExtractedDocument(
            name = name,
            mimeType = mime.ifBlank { "application/octet-stream" },
            text = if (truncated) text.take(MAX_CHARS) else text,
            truncated = truncated,
        )
    }

    private fun extractPdf(bytes: ByteArray): String {
        PDDocument.load(ByteArrayInputStream(bytes)).use { document ->
            val stripper = PDFTextStripper()
            stripper.sortByPosition = true
            return stripper.getText(document)
        }
    }

    private fun extractZipXml(bytes: ByteArray, entryName: String): String {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == entryName) {
                    return stripXml(String(zip.readLimited(MAX_INPUT_BYTES), Charsets.UTF_8))
                }
            }
        }
        error("Could not find $entryName inside the document.")
    }

    private fun extractPptx(bytes: ByteArray): String {
        val builder = StringBuilder()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name.matches(Regex("ppt/slides/slide\\d+\\.xml"))) {
                    val xml = stripXml(String(zip.readLimited(MAX_INPUT_BYTES), Charsets.UTF_8))
                    if (xml.isNotBlank()) builder.append(xml).append("\n\n")
                }
            }
        }
        return builder.toString()
    }

    private fun extractXlsx(bytes: ByteArray): String {
        val builder = StringBuilder()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "xl/sharedStrings.xml" || entry.name.matches(Regex("xl/worksheets/sheet\\d+\\.xml"))) {
                    val xml = stripXml(String(zip.readLimited(MAX_INPUT_BYTES), Charsets.UTF_8))
                    if (xml.isNotBlank()) builder.append(xml).append('\n')
                }
            }
        }
        return builder.toString()
    }

    private fun stripXml(xml: String): String {
        val withBreaks = xml
            .replace(Regex("</(?:w|a|text):p>"), "\n")
            .replace(Regex("<(?:w|a):br\\s*/?>"), "\n")
            .replace(Regex("<(?:w|a):tab\\s*/?>"), "\t")
            .replace(Regex("<[^>]+>"), "")
        return Parser.unescapeEntities(withBreaks, false)
            .replace(Regex("[ \\t]+\\n"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    private fun decodeText(bytes: ByteArray): String {
        val utf8 = String(bytes, Charsets.UTF_8)
        val replacementRatio = utf8.count { it == '\uFFFD' }.toDouble() / utf8.length.coerceAtLeast(1)
        return if (replacementRatio > 0.02) String(bytes, Charsets.ISO_8859_1) else utf8
    }

    private fun java.io.InputStream.readLimited(maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read == -1) break
            val remaining = maxBytes - total
            if (remaining <= 0) error("That file is too large (limit 15 MB).")
            output.write(buffer, 0, minOf(read, remaining))
            total += read
        }
        return output.toByteArray()
    }

    companion object {
        private const val MAX_INPUT_BYTES = 15 * 1024 * 1024
        private const val MAX_CHARS = 120_000
        private const val DOCX_ENTRY = "word/document.xml"
        private val TEXT_EXTENSIONS = setOf(
            "txt", "md", "markdown", "csv", "tsv", "json", "jsonl", "xml", "yaml", "yml",
            "kt", "kts", "java", "js", "ts", "tsx", "jsx", "py", "rb", "go", "rs", "c", "h",
            "cpp", "hpp", "cs", "swift", "sh", "bash", "zsh", "sql", "css", "scss", "toml",
            "ini", "log", "gradle", "properties", "env", "tex", "rst", "svg",
        )
    }
}
