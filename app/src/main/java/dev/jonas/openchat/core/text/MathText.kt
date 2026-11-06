package dev.jonas.openchat.core.text

/**
 * A small LaTeX → Unicode converter used for inline formulas.
 *
 * Inline math cannot easily be replaced by a bitmap without breaking text flow, so formulas
 * are rendered with real Unicode glyphs (fractions, roots, scripts, Greek letters and the common
 * relational/operator symbols). Pathologically nested input is rejected instead of recursing far
 * enough to overflow the stack.
 */
object MathText {
    private const val MAX_DEPTH = 64

    /** Converts inline math to a single Unicode run so it can flow inside a paragraph. */
    fun toUnicode(tex: String): String = try {
        beautify(Converter(tex, display = false).convert())
    } catch (_: TooDeep) {
        tex.trim()
    }

    /**
     * Converts display math to Unicode and keeps line breaks, so environments such as
     * `aligned`, `cases` and `matrix` stay readable as multiple centered lines.
     */
    fun toDisplay(tex: String): String = try {
        val lines = Converter(tex, display = true).convert()
            .lines()
            .map(::beautify)
            .filter { it.isNotEmpty() }
        if (lines.isEmpty()) toUnicode(tex) else lines.joinToString("\n")
    } catch (_: TooDeep) {
        tex.trim()
    }

    private class TooDeep : RuntimeException()

    private class Converter(private val source: String, private val display: Boolean) {
        private var i = 0
        private var depth = 0

        fun convert(): String = convertUntil(null)

        private fun convertUntil(terminator: Char?): String {
            if (depth >= MAX_DEPTH) throw TooDeep()
            depth++
            try {
                val out = StringBuilder()
                while (i < source.length) {
                    val ch = source[i]
                    if (terminator != null && ch == terminator) {
                        i++
                        break
                    }
                    when (ch) {
                        '{' -> {
                            i++
                            out.append(convertUntil('}'))
                        }
                        '}' -> i++
                        '^' -> {
                            i++
                            out.append(superscript(readArgument()))
                        }
                        '_' -> {
                            i++
                            out.append(subscript(readArgument()))
                        }
                        '\\' -> out.append(readCommand())
                        '$' -> i++
                        '&' -> {
                            out.append(' ')
                            i++
                        }
                        '\n', '\r', '\t' -> {
                            out.append(' ')
                            i++
                        }
                        else -> {
                            out.append(ch)
                            i++
                        }
                    }
                }
                return out.toString()
            } finally {
                depth--
            }
        }

        private fun readArgument(): String {
            skipSpaces()
            if (i >= source.length) return ""
            return when (source[i]) {
                '{' -> {
                    i++
                    convertUntil('}')
                }
                '\\' -> readCommand()
                else -> {
                    val ch = source[i]
                    i++
                    ch.toString()
                }
            }
        }

        private fun readRawArgument(): String {
            skipSpaces()
            if (i >= source.length) return ""
            if (source[i] != '{') {
                val ch = source[i]
                i++
                return ch.toString()
            }
            i++
            val out = StringBuilder()
            var depth = 1
            while (i < source.length && depth > 0) {
                val ch = source[i]
                when (ch) {
                    '{' -> {
                        depth++
                        if (depth > 1) out.append(ch)
                        i++
                    }
                    '}' -> {
                        depth--
                        if (depth > 0) out.append(ch)
                        i++
                    }
                    '\\' -> {
                        i++
                        if (i < source.length) {
                            val escaped = source[i]
                            if (escaped.isLetter()) {
                                out.append('\\').append(escaped)
                            } else {
                                when (escaped) {
                                    '{', '}', '%', '$', '&', '#', '_', '\\' -> out.append(escaped)
                                    ' ', ',', ';', ':' -> out.append(' ')
                                    '!' -> Unit
                                    else -> out.append(escaped)
                                }
                            }
                            i++
                        }
                    }
                    else -> {
                        out.append(ch)
                        i++
                    }
                }
            }
            return out.toString()
        }

        private fun readCommand(): String {
            i++ // the backslash
            if (i >= source.length) return ""
            val first = source[i]
            if (!first.isLetter()) {
                i++
                return when (first) {
                    '\\' -> if (display) "\n" else " "
                    '{' -> "{"
                    '}' -> "}"
                    '%' -> "%"
                    '$' -> "$"
                    '&' -> "&"
                    '#' -> "#"
                    '_' -> "_"
                    ',', ';', ':', ' ' -> " "
                    '!' -> ""
                    '|' -> "‖"
                    else -> first.toString()
                }
            }

            val start = i
            while (i < source.length && source[i].isLetter()) i++
            val name = source.substring(start, i)

            return when (name) {
                "frac", "dfrac", "tfrac", "cfrac" -> {
                    val numerator = readArgument()
                    val denominator = readArgument()
                    "${wrapFraction(numerator)}/${wrapFraction(denominator)}"
                }

                "sqrt" -> {
                    skipSpaces()
                    val index = if (i < source.length && source[i] == '[') {
                        i++
                        convertUntil(']')
                    } else {
                        null
                    }
                    val radicand = readArgument()
                    val root = "√" + wrapRoot(radicand)
                    if (index.isNullOrBlank()) root else superscript(index) + root
                }

                "text", "textrm", "textnormal", "textup", "mbox",
                "mathrm", "mathbf", "mathit", "mathsf", "mathtt",
                "mathcal", "mathbb", "mathfrak", "operatorname" -> readRawArgument()

                "color" -> {
                    readRawArgument()
                    ""
                }

                "textcolor", "colorbox" -> {
                    readRawArgument()
                    readArgument()
                }

                "fcolorbox" -> {
                    readRawArgument()
                    readRawArgument()
                    readArgument()
                }

                "left", "right", "middle" -> readDelimiter()

                "big", "Big", "bigg", "Bigg",
                "bigl", "bigr", "Bigl", "Bigr", "biggl", "biggr", "Biggl", "Biggr" ->
                    readDelimiter()

                "begin", "end" -> {
                    readRawArgument()
                    ""
                }

                "limits", "nolimits", "displaystyle", "textstyle", "scriptstyle",
                "scriptscriptstyle", "rm", "bf", "it", "cal", "left.", "right." -> ""

                "quad" -> " "
                "qquad" -> "  "
                "space" -> " "
                "enspace" -> " "

                "hat", "widehat" -> accent(readArgument(), '\u0302')
                "bar", "overline" -> accent(readArgument(), '\u0305')
                "vec", "overrightarrow" -> accent(readArgument(), '\u20D7')
                "dot" -> accent(readArgument(), '\u0307')
                "ddot" -> accent(readArgument(), '\u0308')
                "tilde", "widetilde" -> accent(readArgument(), '\u0303')

                "underbrace", "overbrace" -> readArgument()

                "boxed", "fbox" -> "[" + readArgument() + "]"

                "binom" -> {
                    val n = readArgument()
                    val k = readArgument()
                    "C($n,$k)"
                }

                "pmod" -> "(mod ${readArgument()})"

                "overset" -> {
                    readArgument()
                    readArgument()
                }

                "underset" -> {
                    readArgument()
                    readArgument()
                }

                else -> symbols[name] ?: functions[name]?.let { "$it " } ?: name
            }
        }

        private fun readDelimiter(): String {
            skipSpaces()
            if (i >= source.length) return ""
            val ch = source[i]
            if (ch == '.') {
                i++
                return ""
            }
            if (ch == '\\') return readCommand()
            i++
            return ch.toString()
        }

        private fun skipSpaces() {
            while (i < source.length && source[i] == ' ') i++
        }

        private fun wrapFraction(value: String): String {
            if (value.length <= 1) return value
            val needs = value.any { it.isWhitespace() } ||
                value.any { it in "+-=<>" } ||
                value.startsWith("-")
            return if (needs) "($value)" else value
        }

        private fun wrapRoot(value: String): String {
            if (value.length <= 1) return value
            val needs = value.any { it.isWhitespace() } ||
                value.any { it in "+-=<>/" } ||
                value.contains('+')
            return if (needs) "($value)" else value
        }

        private fun accent(value: String, mark: Char): String =
            if (value.isEmpty()) "" else value + mark
    }

    private fun superscript(value: String): String {
        if (value.isEmpty()) return ""
        val mapped = buildString {
            for (ch in value) {
                append(superscripts[ch] ?: return fallbackScript(value, '^'))
            }
        }
        return mapped
    }

    private fun subscript(value: String): String {
        if (value.isEmpty()) return ""
        val mapped = buildString {
            for (ch in value) {
                append(subscripts[ch] ?: return fallbackScript(value, '_'))
            }
        }
        return mapped
    }

    private fun fallbackScript(value: String, marker: Char): String =
        if (value.length == 1) "$marker$value" else "$marker($value)"

    /** Adds TeX-like spacing around relations, sums and products so formulas are easier to read. */
    private fun beautify(value: String): String {
        var result = value
        // Follow the phone's regional convention for decimals: 3.14 in the US, 3,14 in Europe.
        val decimalSeparator = java.text.DecimalFormatSymbols.getInstance().decimalSeparator
        if (decimalSeparator != '.') {
            result = result.replace(Regex("(?<=\\d)\\.(?=\\d)"), decimalSeparator.toString())
        }
        return result
            .replace(Regex("(?<=\\S)\\s*([=<>≤≥≠≈≡])\\s*(?=\\S)"), " $1 ")
            .replace(Regex("(?<=\\S)\\s*([+×÷±∓])\\s*(?=\\S)"), " $1 ")
            .replace(Regex(" {2,}"), " ")
            .trim()
    }

    private val superscripts: Map<Char, String> = mapOf(
        '0' to "⁰", '1' to "¹", '2' to "²", '3' to "³", '4' to "⁴",
        '5' to "⁵", '6' to "⁶", '7' to "⁷", '8' to "⁸", '9' to "⁹",
        '+' to "⁺", '-' to "⁻", '=' to "⁼", '(' to "⁽", ')' to "⁾",
        'a' to "ᵃ", 'b' to "ᵇ", 'c' to "ᶜ", 'd' to "ᵈ", 'e' to "ᵉ",
        'f' to "ᶠ", 'g' to "ᵍ", 'h' to "ʰ", 'i' to "ⁱ", 'j' to "ʲ",
        'k' to "ᵏ", 'l' to "ˡ", 'm' to "ᵐ", 'n' to "ⁿ", 'o' to "ᵒ",
        'p' to "ᵖ", 'r' to "ʳ", 's' to "ˢ", 't' to "ᵗ", 'u' to "ᵘ",
        'v' to "ᵛ", 'w' to "ʷ", 'x' to "ˣ", 'y' to "ʸ", 'z' to "ᶻ",
        'A' to "ᴬ", 'B' to "ᴮ", 'D' to "ᴰ", 'E' to "ᴱ", 'G' to "ᴳ",
        'H' to "ᴴ", 'I' to "ᴵ", 'J' to "ᴶ", 'K' to "ᴷ", 'L' to "ᴸ",
        'M' to "ᴹ", 'N' to "ᴺ", 'O' to "ᴼ", 'P' to "ᴾ", 'R' to "ᴿ",
        'T' to "ᵀ", 'U' to "ᵁ", 'V' to "ⱽ", 'W' to "ᵂ",
    )

    private val subscripts: Map<Char, String> = mapOf(
        '0' to "₀", '1' to "₁", '2' to "₂", '3' to "₃", '4' to "₄",
        '5' to "₅", '6' to "₆", '7' to "₇", '8' to "₈", '9' to "₉",
        '+' to "₊", '-' to "₋", '=' to "₌", '(' to "₍", ')' to "₎",
        'a' to "ₐ", 'e' to "ₑ", 'h' to "ₕ", 'i' to "ᵢ", 'j' to "ⱼ",
        'k' to "ₖ", 'l' to "ₗ", 'm' to "ₘ", 'n' to "ₙ", 'o' to "ₒ",
        'p' to "ₚ", 'r' to "ᵣ", 's' to "ₛ", 't' to "ₜ", 'u' to "ᵤ",
        'v' to "ᵥ", 'x' to "ₓ",
    )

    private val symbols: Map<String, String> = mapOf(
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ",
        "epsilon" to "ε", "varepsilon" to "ε", "zeta" to "ζ", "eta" to "η",
        "theta" to "θ", "vartheta" to "ϑ", "iota" to "ι", "kappa" to "κ",
        "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ",
        "omicron" to "ο", "pi" to "π", "varpi" to "ϖ", "rho" to "ρ",
        "varrho" to "ϱ", "sigma" to "σ", "varsigma" to "ς", "tau" to "τ",
        "upsilon" to "υ", "phi" to "φ", "varphi" to "ϕ", "chi" to "χ",
        "psi" to "ψ", "omega" to "ω",
        "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ",
        "Xi" to "Ξ", "Pi" to "Π", "Sigma" to "Σ", "Upsilon" to "Υ",
        "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω",

        "times" to "×", "cdot" to "·", "div" to "÷", "pm" to "±",
        "mp" to "∓", "ast" to "∗", "star" to "⋆", "circ" to "∘",
        "bullet" to "•", "oplus" to "⊕", "otimes" to "⊗", "odot" to "⊙",

        "le" to "≤", "leq" to "≤", "ge" to "≥", "geq" to "≥",
        "ne" to "≠", "neq" to "≠", "approx" to "≈", "sim" to "∼",
        "simeq" to "≃", "cong" to "≅", "equiv" to "≡", "propto" to "∝",
        "ll" to "≪", "gg" to "≫", "prec" to "≺", "succ" to "≻",

        "infty" to "∞", "partial" to "∂", "nabla" to "∇",
        "sum" to "∑", "prod" to "∏", "coprod" to "∐",
        "int" to "∫", "iint" to "∬", "iiint" to "∭", "oint" to "∮",

        "to" to "→", "rightarrow" to "→", "leftarrow" to "←",
        "gets" to "←", "leftrightarrow" to "↔", "Rightarrow" to "⇒",
        "Leftarrow" to "⇐", "Leftrightarrow" to "⇔", "mapsto" to "↦",
        "uparrow" to "↑", "downarrow" to "↓",

        "in" to "∈", "notin" to "∉", "ni" to "∋", "subset" to "⊂",
        "subseteq" to "⊆", "supset" to "⊃", "supseteq" to "⊇",
        "cup" to "∪", "cap" to "∩", "emptyset" to "∅", "varnothing" to "∅",
        "setminus" to "∖",

        "forall" to "∀", "exists" to "∃", "nexists" to "∄", "neg" to "¬",
        "land" to "∧", "lor" to "∨", "wedge" to "∧", "vee" to "∨",
        "therefore" to "∴", "because" to "∵",

        "angle" to "∠", "perp" to "⊥", "parallel" to "∥", "triangle" to "△",
        "square" to "□", "diamond" to "◇",

        "ldots" to "…", "dots" to "…", "cdots" to "⋯", "vdots" to "⋮",
        "ddots" to "⋱", "prime" to "′", "degree" to "°",

        "ell" to "ℓ", "hbar" to "ℏ", "Re" to "ℜ", "Im" to "ℑ", "aleph" to "ℵ",
    )

    private val functions: Map<String, String> = mapOf(
        "sin" to "sin", "cos" to "cos", "tan" to "tan", "cot" to "cot",
        "sec" to "sec", "csc" to "csc", "arcsin" to "arcsin",
        "arccos" to "arccos", "arctan" to "arctan", "sinh" to "sinh",
        "cosh" to "cosh", "tanh" to "tanh", "log" to "log", "ln" to "ln",
        "lg" to "lg", "exp" to "exp", "lim" to "lim", "max" to "max",
        "min" to "min", "sup" to "sup", "inf" to "inf", "det" to "det",
        "gcd" to "gcd", "mod" to "mod", "ker" to "ker", "dim" to "dim",
    )
}
