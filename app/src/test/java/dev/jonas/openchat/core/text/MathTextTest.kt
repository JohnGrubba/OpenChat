package dev.jonas.openchat.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MathTextTest {
    @Test
    fun `fractions and roots become readable unicode`() {
        assertEquals("a/b", MathText.toUnicode("\\frac{a}{b}"))
        assertEquals("√x", MathText.toUnicode("\\sqrt{x}"))
        assertEquals("√(x+1)", MathText.toUnicode("\\sqrt{x+1}"))
        assertEquals("³√x", MathText.toUnicode("\\sqrt[3]{x}"))
    }

    @Test
    fun `scripts and greek letters are converted`() {
        assertEquals("x² + y² = z²", MathText.toUnicode("x^2 + y^2 = z^2"))
        assertEquals("x₁ + x₂", MathText.toUnicode("x_1 + x_2"))
        assertEquals("α + β ≤ γ", MathText.toUnicode("\\alpha + \\beta \\le \\gamma"))
    }

    @Test
    fun `text and common operators are preserved`() {
        assertEquals("speed = distance/time", MathText.toUnicode("\\text{speed} = \\frac{distance}{time}"))
        assertTrue(MathText.toUnicode("a \\times b \\div c").contains("×"))
        assertTrue(MathText.toUnicode("a \\times b \\div c").contains("÷"))
        assertEquals("sin x", MathText.toUnicode("\\sin x"))
    }
}
