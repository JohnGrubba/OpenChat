package dev.jonas.openchat.data.attachment

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DocumentExtractorTest {
    private val extractor = DocumentExtractor(
        ApplicationProvider.getApplicationContext<Context>(),
    )

    @Test
    fun `plain text and markdown are read`() {
        val text = extractor.extractBytes(
            "notes.md",
            "text/markdown",
            "# Title\n\nHello **world**".toByteArray(),
        )
        assertTrue(text.text.contains("Hello **world**"))
    }

    @Test
    fun `html is reduced to readable text`() {
        val html = "<html><body><h1>Heading</h1><p>Body text</p></body></html>"
        val text = extractor.extractBytes("page.html", "text/html", html.toByteArray())
        assertTrue(text.text.contains("Heading"))
        assertTrue(text.text.contains("Body text"))
    }

    @Test
    fun `docx paragraphs are extracted`() {
        val xml = """
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                <w:p><w:r><w:t>Hello from DOCX</w:t></w:r></w:p>
                <w:p><w:r><w:t>Second paragraph</w:t></w:r></w:p>
              </w:body>
            </w:document>
        """.trimIndent()
        val bytes = zipOf("word/document.xml" to xml.toByteArray())
        val text = extractor.extractBytes("document.docx", DOCX_MIME, bytes)
        assertTrue(text.text.contains("Hello from DOCX"))
        assertTrue(text.text.contains("Second paragraph"))
    }

    @Test
    fun `pdf text is extracted`() {
        val bytes = createPdf("Hello from PDF")
        val text = extractor.extractBytes("document.pdf", "application/pdf", bytes)
        assertTrue(text.text.contains("Hello from PDF"))
    }

    @Test(expected = IllegalStateException::class)
    fun `binary garbage is rejected`() {
        extractor.extractBytes(
            "unknown.bin",
            "application/octet-stream",
            byteArrayOf(0, 1, 2, 3, 0, 5),
        )
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun createPdf(text: String): ByteArray {
        PDDocument().use { document ->
            val page = PDPage(PDRectangle.LETTER)
            document.addPage(page)
            PDPageContentStream(document, page).use { content ->
                content.beginText()
                content.setFont(PDType1Font.HELVETICA, 12f)
                content.newLineAtOffset(72f, 700f)
                content.showText(text)
                content.endText()
            }
            val out = ByteArrayOutputStream()
            document.save(out)
            return out.toByteArray()
        }
    }

    companion object {
        private const val DOCX_MIME =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    }
}
