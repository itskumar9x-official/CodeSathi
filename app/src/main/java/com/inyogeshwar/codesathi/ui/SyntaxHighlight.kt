package com.inyogeshwar.codesathi.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Phase 3, Step 5 — small hand-rolled syntax highlighter.
 *
 * No new dependency: a single-pass scanner turns code text into an
 * [AnnotatedString] with spans for keywords, strings, comments and
 * numbers (plus tags for HTML/XML, keys for JSON, headings and inline
 * code for Markdown). It is deliberately a *highlighter*, not a
 * parser: anything it does not recognise stays plain text, and an
 * unterminated string or comment is capped at the end of the line so
 * a snippet cut off mid-token (the diff card caps at 600 chars) can
 * never paint the rest of the text one colour.
 *
 * Colours are fixed mid-tones chosen to stay readable on both the
 * light and the dark Material theme; the diff card and the Markdown
 * code blocks use the defaults.
 */
enum class CodeLanguage {
    /** Kotlin / Java (and .kts). */
    JVM,
    /** Python. */
    PYTHON,
    /** JavaScript / TypeScript (.js, .mjs, .cjs, .ts, .tsx, .jsx). */
    JAVASCRIPT,
    /** HTML / XML. */
    MARKUP,
    /** JSON. */
    JSON,
    /** Markdown. */
    MARKDOWN
}

/** Pick a language from a file path's extension, or null when unknown.
 *  Unknown means "show it plain" — the caller must never guess. */
fun languageForPath(path: String): CodeLanguage? {
    return when (path.substringAfterLast('.', "").lowercase()) {
        "kt", "kts", "java" -> CodeLanguage.JVM
        "py" -> CodeLanguage.PYTHON
        "js", "mjs", "cjs", "ts", "tsx", "jsx" -> CodeLanguage.JAVASCRIPT
        "html", "htm", "xml" -> CodeLanguage.MARKUP
        "json" -> CodeLanguage.JSON
        "md", "markdown" -> CodeLanguage.MARKDOWN
        else -> null
    }
}

/** Pick a language from a fenced-code-block info string ("kotlin",
 *  "python", …) or a bare extension, or null when unknown. */
fun languageForName(name: String): CodeLanguage? {
    return when (name.trim().lowercase()) {
        "kotlin", "kt", "kts", "java" -> CodeLanguage.JVM
        "python", "py" -> CodeLanguage.PYTHON
        "javascript", "js", "typescript", "ts", "tsx", "jsx" -> CodeLanguage.JAVASCRIPT
        "html", "xml" -> CodeLanguage.MARKUP
        "json" -> CodeLanguage.JSON
        "markdown", "md" -> CodeLanguage.MARKDOWN
        else -> null
    }
}

/** The palette the highlighter paints with. */
data class HighlightColors(
    val keyword: Color = Color(0xFF7E57C2),
    val string: Color = Color(0xFF2E9E5B),
    val comment: Color = Color(0xFF8A8A8A),
    val number: Color = Color(0xFF1E88E5),
    val tag: Color = Color(0xFF00838F),
    val heading: Color = Color(0xFF5E35B1)
)

/**
 * Highlight [code] for [language]. When [language] is null the text
 * is returned plain — no guessing. Pure function: callers compute it
 * once with `remember(code, path)` so recomposition never re-scans.
 */
fun highlightCode(
    code: String,
    language: CodeLanguage?,
    colors: HighlightColors = HighlightColors()
): AnnotatedString {
    if (code.isEmpty()) return AnnotatedString("")
    return when (language) {
        null -> AnnotatedString(code)
        CodeLanguage.JVM -> scanCodeLike(
            code, JVM_KEYWORDS, lineComment = "//",
            blockComment = "/*" to "*/", stringQuotes = setOf('"', '\''),
            colors = colors
        )
        CodeLanguage.PYTHON -> scanCodeLike(
            code, PYTHON_KEYWORDS, lineComment = "#",
            blockComment = null, stringQuotes = setOf('"', '\''),
            colors = colors
        )
        CodeLanguage.JAVASCRIPT -> scanCodeLike(
            code, JS_KEYWORDS, lineComment = "//",
            blockComment = "/*" to "*/", stringQuotes = setOf('"', '\'', '`'),
            colors = colors
        )
        CodeLanguage.MARKUP -> scanMarkup(code, colors)
        CodeLanguage.JSON -> scanJson(code, colors)
        CodeLanguage.MARKDOWN -> scanMarkdown(code, colors)
    }
}

/** One styled span inside the text. */
private data class Run(val start: Int, val end: Int, val style: SpanStyle)

private fun AnnotatedString.Builder.applyRuns(text: String, runs: List<Run>) {
    append(text)
    runs.forEach { addStyle(it.style, it.start, it.end) }
}

/**
 * Single-pass scanner for C-like / Python / JS code: keywords,
 * line + block comments, quoted strings (including `"""` triple
 * quotes for Kotlin/Python), numbers.
 */
private fun scanCodeLike(
    code: String,
    keywords: Set<String>,
    lineComment: String?,
    blockComment: Pair<String, String>?,
    stringQuotes: Set<Char>,
    colors: HighlightColors
): AnnotatedString {
    val runs = mutableListOf<Run>()
    val keywordStyle = SpanStyle(color = colors.keyword, fontWeight = FontWeight.Bold)
    val stringStyle = SpanStyle(color = colors.string)
    val commentStyle = SpanStyle(color = colors.comment, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
    val numberStyle = SpanStyle(color = colors.number)
    var i = 0
    while (i < code.length) {
        // Line comment: to the end of the line.
        if (lineComment != null && code.startsWith(lineComment, i)) {
            val end = code.indexOf('\n', i).let { if (it < 0) code.length else it }
            runs += Run(i, end, commentStyle)
            i = end
            continue
        }
        // Block comment: to its end, or to the end of the text.
        if (blockComment != null && code.startsWith(blockComment.first, i)) {
            val close = code.indexOf(blockComment.second, i + blockComment.first.length)
            val end = if (close < 0) code.length else close + blockComment.second.length
            runs += Run(i, end, commentStyle)
            i = end
            continue
        }
        // Triple-quoted string (Kotlin / Python): to the closing """.
        if (code.startsWith("\"\"\"", i)) {
            val close = code.indexOf("\"\"\"", i + 3)
            val end = if (close < 0) code.length else close + 3
            runs += Run(i, end, stringStyle)
            i = end
            continue
        }
        // Triple-single-quoted string (Python docstrings): same rule.
        // Only reached when '\'' is a string quote for this language;
        // in Kotlin/JS ''' is not valid syntax, so this never misfires.
        if ('\'' in stringQuotes && code.startsWith("'''", i)) {
            val close = code.indexOf("'''", i + 3)
            val end = if (close < 0) code.length else close + 3
            runs += Run(i, end, stringStyle)
            i = end
            continue
        }
        // Quoted string: to the matching quote, honouring backslash
        // escapes, and never past the end of the line (a snippet cut
        // at 600 chars must not paint everything after it green).
        val ch = code[i]
        if (ch in stringQuotes) {
            var j = i + 1
            var closed = false
            while (j < code.length && code[j] != '\n') {
                if (code[j] == '\\' && j + 1 < code.length) {
                    j += 2
                    continue
                }
                if (code[j] == ch) {
                    closed = true
                    break
                }
                j++
            }
            val end = if (closed) j + 1 else j
            runs += Run(i, end, stringStyle)
            i = end
            continue
        }
        // Number: digits (0x hex included — 'x' is a letter), with
        // '.' and '_' inside (1_000, 3.14).
        if (ch.isDigit() || (ch == '.' && i + 1 < code.length && code[i + 1].isDigit())) {
            var j = i
            while (j < code.length && (code[j].isLetterOrDigit() || code[j] == '.' || code[j] == '_')) j++
            runs += Run(i, j, numberStyle)
            i = j
            continue
        }
        // Word: keyword, or plain.
        if (ch.isLetter() || ch == '_' || ch == '$') {
            var j = i
            while (j < code.length && (code[j].isLetterOrDigit() || code[j] == '_' || code[j] == '$')) j++
            if (code.substring(i, j) in keywords) runs += Run(i, j, keywordStyle)
            i = j
            continue
        }
        i++
    }
    return buildAnnotatedString { applyRuns(code, runs) }
}

/** HTML / XML: tag names in the tag colour, quoted attribute values
 *  as strings, `<!-- … -->` comments in the comment colour. */
private fun scanMarkup(code: String, colors: HighlightColors): AnnotatedString {
    val runs = mutableListOf<Run>()
    val tagStyle = SpanStyle(color = colors.tag, fontWeight = FontWeight.Bold)
    val stringStyle = SpanStyle(color = colors.string)
    val commentStyle = SpanStyle(color = colors.comment, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
    var i = 0
    while (i < code.length) {
        if (code.startsWith("<!--", i)) {
            val close = code.indexOf("-->", i + 4)
            val end = if (close < 0) code.length else close + 3
            runs += Run(i, end, commentStyle)
            i = end
            continue
        }
        if (code[i] == '<') {
            val close = code.indexOf('>', i)
            val end = if (close < 0) code.length else close + 1
            // Tag name: the word after '<' or '</'.
            var nameStart = i + 1
            if (nameStart < end && code[nameStart] == '/') nameStart++
            var nameEnd = nameStart
            while (nameEnd < end && (code[nameEnd].isLetterOrDigit() || code[nameEnd] == '-' || code[nameEnd] == ':')) nameEnd++
            if (nameEnd > nameStart) runs += Run(nameStart, nameEnd, tagStyle)
            // Quoted attribute values inside the tag.
            var j = nameEnd
            while (j < end) {
                val c = code[j]
                if (c == '"' || c == '\'') {
                    var k = j + 1
                    while (k < end && code[k] != c) k++
                    runs += Run(j, minOf(k + 1, end), stringStyle)
                    j = minOf(k + 1, end)
                } else {
                    j++
                }
            }
            i = end
            continue
        }
        i++
    }
    return buildAnnotatedString { applyRuns(code, runs) }
}

/** JSON: object keys (a string followed by ':') in the tag colour,
 *  other strings green, numbers blue, true / false / null bold. */
private fun scanJson(code: String, colors: HighlightColors): AnnotatedString {
    val runs = mutableListOf<Run>()
    val keyStyle = SpanStyle(color = colors.tag, fontWeight = FontWeight.Bold)
    val stringStyle = SpanStyle(color = colors.string)
    val numberStyle = SpanStyle(color = colors.number)
    val literalStyle = SpanStyle(color = colors.keyword, fontWeight = FontWeight.Bold)
    var i = 0
    while (i < code.length) {
        val ch = code[i]
        if (ch == '"') {
            var j = i + 1
            while (j < code.length && code[j] != '\n') {
                if (code[j] == '\\' && j + 1 < code.length) {
                    j += 2
                    continue
                }
                if (code[j] == '"') break
                j++
            }
            val end = minOf(j + 1, code.length)
            // Key when the next non-space char after the string is ':'.
            var k = end
            while (k < code.length && code[k].isWhitespace()) k++
            runs += Run(i, end, if (k < code.length && code[k] == ':') keyStyle else stringStyle)
            i = end
            continue
        }
        if (ch.isDigit() || (ch == '-' && i + 1 < code.length && code[i + 1].isDigit())) {
            var j = i + 1
            while (j < code.length && (code[j].isLetterOrDigit() || code[j] == '.' || code[j] == '_' || code[j] == '+' || code[j] == '-')) j++
            runs += Run(i, j, numberStyle)
            i = j
            continue
        }
        if (ch.isLetter()) {
            var j = i
            while (j < code.length && code[j].isLetter()) j++
            if (code.substring(i, j) in setOf("true", "false", "null")) runs += Run(i, j, literalStyle)
            i = j
            continue
        }
        i++
    }
    return buildAnnotatedString { applyRuns(code, runs) }
}

/** Markdown: `#` headings, `inline code` spans, `**bold**` spans.
 *  Line-based, so a heading never leaks onto the next line.
 *  The walk is index-based, not `code.lines()`: lines() strips a
 *  CRLF ending but the offset math would only count one char, so
 *  every span after the first CRLF line would drift one char per
 *  line (Windows-edited .md files). */
private fun scanMarkdown(code: String, colors: HighlightColors): AnnotatedString {
    val runs = mutableListOf<Run>()
    val headingStyle = SpanStyle(color = colors.heading, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    val codeStyle = SpanStyle(color = colors.string, fontFamily = FontFamily.Monospace)
    val boldStyle = SpanStyle(fontWeight = FontWeight.Bold)
    var offset = 0
    while (offset < code.length) {
        val nl = code.indexOf('\n', offset)
        val lineEnd = if (nl < 0) code.length else nl
        val line = code.substring(offset, lineEnd)
        val trimmed = line.trimStart()
        if (trimmed.startsWith("#")) {
            runs += Run(offset, offset + line.length, headingStyle)
        } else {
            var j = 0
            while (j < line.length) {
                if (line[j] == '`') {
                    val close = line.indexOf('`', j + 1)
                    val end = if (close < 0) line.length else close + 1
                    runs += Run(offset + j, offset + end, codeStyle)
                    j = end
                    continue
                }
                if (line.startsWith("**", j)) {
                    val close = line.indexOf("**", j + 2)
                    val end = if (close < 0) line.length else close + 2
                    runs += Run(offset + j, offset + end, boldStyle)
                    j = end
                    continue
                }
                j++
            }
        }
        offset = if (nl < 0) code.length else nl + 1
    }
    return buildAnnotatedString { applyRuns(code, runs) }
}

private val JVM_KEYWORDS = setOf(
    "abstract", "annotation", "as", "break", "by", "catch", "class", "companion",
    "const", "constructor", "continue", "data", "do", "else", "enum", "false",
    "final", "finally", "for", "fun", "if", "import", "in", "init", "interface",
    "internal", "is", "lateinit", "null", "object", "open", "override", "package",
    "private", "protected", "public", "return", "sealed", "static", "super",
    "this", "throw", "try", "true", "val", "var", "void", "when", "while",
    "extends", "implements", "new", "switch", "case", "default", "synchronized",
    "throws", "transient", "volatile", "instanceof", "native", "boolean", "int",
    "long", "double", "float", "char", "byte", "short"
)

private val PYTHON_KEYWORDS = setOf(
    "and", "as", "assert", "async", "await", "break", "class", "continue",
    "def", "del", "elif", "else", "except", "False", "finally", "for", "from",
    "global", "if", "import", "in", "is", "lambda", "None", "nonlocal", "not",
    "or", "pass", "raise", "return", "True", "try", "while", "with", "yield"
)

private val JS_KEYWORDS = setOf(
    "break", "case", "catch", "class", "const", "continue", "debugger",
    "default", "delete", "do", "else", "export", "extends", "false", "finally",
    "for", "function", "if", "import", "in", "instanceof", "let", "new", "null",
    "return", "super", "switch", "this", "throw", "true", "try", "typeof",
    "var", "void", "while", "with", "yield", "async", "await", "of", "from",
    "interface", "type", "enum", "implements", "private", "public", "protected",
    "readonly", "static", "abstract", "declare", "namespace"
)
