package dev.jonas.openchat.ui.markdown

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jonas.openchat.core.text.MathText

/**
 * Renders a display (block) formula with the built-in Unicode typesetter.
 *
 * The app intentionally does not load a native LaTeX engine here: formulas arrive as untrusted
 * model output, and a crash inside a native renderer would kill the whole process and repeat on
 * every launch because the saved answer is rendered again. The built-in renderer cannot crash the
 * app and still produces readable, nicely styled mathematics.
 */
@Composable
internal fun MathBlockView(tex: String) {
    val display = remember(tex) {
        runCatching { MathText.toDisplay(tex) }
            .getOrElse { tex }
            .ifBlank { tex }
    }
    val scrollState = rememberScrollState()

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
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            val viewport = maxWidth
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(scrollState),
            ) {
                Box(
                    modifier = Modifier.widthIn(min = viewport),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = display,
                        fontFamily = FontFamily.Serif,
                        fontSize = 18.sp,
                        lineHeight = 28.sp,
                        letterSpacing = 0.2.sp,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}
