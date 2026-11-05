package dev.jonas.openchat.ui.markdown

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import android.util.LruCache
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jonas.openchat.core.text.MathText
import io.ratex.RaTeXEngine
import io.ratex.RaTeXFontLoader
import io.ratex.RaTeXRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ceil

private data class MathBitmap(
    val bitmap: Bitmap,
    val widthPx: Int,
    val heightPx: Int,
)

/**
 * Renders a display (block) formula with the native RaTeX engine, which uses the KaTeX fonts.
 * Long formulas scroll horizontally instead of being squashed, and if the native engine cannot
 * run on this device we fall back to Unicode math rather than showing raw LaTeX.
 */
@Composable
internal fun MathBlockView(tex: String) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val color = MaterialTheme.colorScheme.onSurface
    val fontSize = (MaterialTheme.typography.bodyLarge.fontSize.value * 1.1f).sp
    val fontSizePx = with(density) { fontSize.toPx() }
    val fallback = remember(tex) { MathText.toUnicode(tex).ifBlank { tex } }

    var rendered: MathBitmap? by remember(tex, fontSizePx, color) { mutableStateOf(null) }

    LaunchedEffect(tex, fontSizePx, color) {
        rendered = null
        rendered = withContext(Dispatchers.IO) {
            renderMath(context, tex, fontSizePx, color.toArgb())
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f),
        ),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
        ) {
            val viewport = maxWidth
            val scrollState = rememberScrollState()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(scrollState),
            ) {
                Box(
                    modifier = Modifier.widthIn(min = viewport),
                    contentAlignment = Alignment.Center,
                ) {
                    val image = rendered
                    if (image != null) {
                        Image(
                            bitmap = image.bitmap.asImageBitmap(),
                            contentDescription = tex,
                            filterQuality = FilterQuality.High,
                            modifier = Modifier.size(
                                width = with(density) { image.widthPx.toDp() },
                                height = with(density) { image.heightPx.toDp() },
                            ),
                        )
                    } else {
                        Text(
                            text = fallback,
                            fontFamily = FontFamily.Serif,
                            fontSize = fontSize,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

private fun renderMath(
    context: Context,
    tex: String,
    fontSizePx: Float,
    color: Int,
): MathBitmap? {
    val key = "$fontSizePx|$color|$tex"
    MathBitmapCache.get(key)?.let { return it }

    if (nativeUnavailable) return null
    return try {
        RaTeXFontLoader.ensureLoaded(context)
        val displayList = RaTeXEngine.parseBlocking(
            latex = tex,
            displayMode = true,
            color = color,
        )
        val renderer = RaTeXRenderer(displayList, fontSizePx) { fontId ->
            RaTeXFontLoader.getTypeface(fontId)
        }
        val width = ceil(renderer.widthPx).toInt().coerceAtLeast(1)
        val height = ceil(renderer.totalHeightPx).toInt().coerceAtLeast(1)
        if (width > 6_000 || height > 6_000) return null

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        renderer.draw(Canvas(bitmap))
        MathBitmap(bitmap, width, height).also { MathBitmapCache.put(key, it) }
    } catch (error: Throwable) {
        if (error is LinkageError) nativeUnavailable = true
        Log.w("MathView", "Could not render LaTeX (${tex.take(40)}): ${error.message}")
        null
    }
}

@Volatile
private var nativeUnavailable = false

private object MathBitmapCache {
    private val cache = object : LruCache<String, MathBitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: MathBitmap): Int = value.bitmap.byteCount
    }

    fun get(key: String): MathBitmap? = cache.get(key)

    fun put(key: String, value: MathBitmap) {
        cache.put(key, value)
    }
}
