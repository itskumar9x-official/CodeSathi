package com.inyogeshwar.codesathi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import com.inyogeshwar.codesathi.ui.theme.SathiCodeType
import com.inyogeshwar.codesathi.ui.theme.SathiColors
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * Small self-contained Markdown renderer for chat bubbles (v1).
 * Supports headings, bold / italic, inline code, fenced code blocks
 * (with a copy button), bullet + numbered lists, blockquotes, rules
 * and links. No third-party Markdown library — the app only needs this
 * much, and a full renderer would pull in a large dependency for a
 * phone app. Anything unrecognised falls back to plain text.
 */
sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Code(val language: String, val code: String) : MdBlock
    data class BulletList(val items: List<String>) : MdBlock
    data class OrderedList(val items: List<String>) : MdBlock
    data class Quote(val text: String) : MdBlock
    object Rule : MdBlock
}

fun parseMarkdown(text: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = text.lines()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()
        when {
            trimmed.startsWith("```") -> {
                val language = trimmed.removePrefix("```").trim()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    code.appendLine(lines[i])
                    i++
                }
                blocks += MdBlock.Code(language, code.toString().trimEnd('\n'))
            }
            trimmed.startsWith("#") -> {
                val level = trimmed.takeWhile { it == '#' }.length.coerceIn(1, 6)
                blocks += MdBlock.Heading(level, trimmed.drop(level).trim())
            }
            trimmed == "---" || trimmed == "***" || trimmed == "___" -> blocks += MdBlock.Rule
            trimmed.startsWith("> ") || trimmed == ">" -> {
                blocks += MdBlock.Quote(trimmed.removePrefix(">").trim())
            }
            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                val items = mutableListOf<String>()
                while (i < lines.size) {
                    val t = lines[i].trim()
                    if (t.startsWith("- ") || t.startsWith("* ")) items += t.drop(2) else break
                    i++
                }
                blocks += MdBlock.BulletList(items)
                continue
            }
            Regex("^\\d+\\.\\s").containsMatchIn(trimmed) -> {
                val items = mutableListOf<String>()
                while (i < lines.size && Regex("^\\d+\\.\\s").containsMatchIn(lines[i].trim())) {
                    items += lines[i].trim().replaceFirst(Regex("^\\d+\\.\\s"), "")
                    i++
                }
                blocks += MdBlock.OrderedList(items)
                continue
            }
            trimmed.isEmpty() -> Unit // blank line: block separator
            else -> {
                // Paragraph: join consecutive plain lines.
                val para = StringBuilder(trimmed)
                i++
                while (i < lines.size) {
                    val next = lines[i].trim()
                    if (next.isEmpty() || next.startsWith("#") || next.startsWith("```") ||
                        next.startsWith("- ") || next.startsWith("> ") ||
                        Regex("^\\d+\\.\\s").containsMatchIn(next)
                    ) break
                    para.append(' ').append(next)
                    i++
                }
                blocks += MdBlock.Paragraph(para.toString())
                continue
            }
        }
        i++
    }
    return blocks
}

/** Inline spans: `code`, **bold**, *italic*, [text](url). */
fun parseInline(text: String): AnnotatedString {
    val bold = SpanStyle(fontWeight = FontWeight.Bold)
    val italic = SpanStyle(fontStyle = FontStyle.Italic)
    val code = SpanStyle(fontFamily = FontFamily.Monospace)
    val link = SpanStyle(color = Color(0xFF0D9488), textDecoration = TextDecoration.Underline)
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end < 0) {
                        append(text.substring(i)); break
                    }
                    withStyle(bold) { appendInline(text.substring(i + 2, end)) }
                    i = end + 2
                }
                text.startsWith("`", i) -> {
                    val end = text.indexOf('`', i + 1)
                    if (end < 0) {
                        append(text.substring(i)); break
                    }
                    withStyle(code) { append(text.substring(i + 1, end)) }
                    i = end + 1
                }
                text.startsWith("[", i) -> {
                    val close = text.indexOf("](", i)
                    val endParen = if (close >= 0) text.indexOf(')', close + 2) else -1
                    if (close < 0 || endParen < 0) {
                        append(text.substring(i)); break
                    }
                    val label = text.substring(i + 1, close)
                    val url = text.substring(close + 2, endParen)
                    pushStringAnnotation(tag = "url", annotation = url)
                    withStyle(link) { append(label) }
                    pop()
                    i = endParen + 1
                }
                text.startsWith("*", i) || text.startsWith("_", i) -> {
                    val marker = text[i]
                    val end = text.indexOf(marker, i + 1)
                    if (end < 0) {
                        append(text.substring(i)); break
                    }
                    withStyle(italic) { appendInline(text.substring(i + 1, end)) }
                    i = end + 1
                }
                else -> {
                    append(text[i])
                    i++
                }
            }
        }
    }
}

/** Inline parsing inside a bold/italic span (links and code still work). */
private fun AnnotatedString.Builder.appendInline(text: String) {
    append(parseInline(text))
}

@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { parseMarkdown(text) }
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    Column(modifier = modifier) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> Text(
                    text = parseInline(block.text),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
                is MdBlock.Paragraph -> ClickableText(
                    text = parseInline(block.text),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 4.dp),
                    onClick = { offset ->
                        parseInline(block.text)
                            .getStringAnnotations(tag = "url", start = offset, end = offset)
                            .firstOrNull()?.let { uriHandler.openUri(it.item) }
                    }
                )
                is MdBlock.Code -> CodeBlock(block, onCopy = { clipboard.setText(AnnotatedString(block.code)) })
                is MdBlock.BulletList -> block.items.forEach { item ->
                    Row(Modifier.padding(bottom = 2.dp)) {
                        Text("• ", style = MaterialTheme.typography.bodyMedium)
                        ClickableText(
                            text = parseInline(item),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                            onClick = { offset ->
                                parseInline(item)
                                    .getStringAnnotations(tag = "url", start = offset, end = offset)
                                    .firstOrNull()?.let { uriHandler.openUri(it.item) }
                            }
                        )
                    }
                }
                is MdBlock.OrderedList -> block.items.forEachIndexed { index, item ->
                    Row(Modifier.padding(bottom = 2.dp)) {
                        Text("${index + 1}. ", style = MaterialTheme.typography.bodyMedium)
                        ClickableText(
                            text = parseInline(item),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                            onClick = { offset ->
                                parseInline(item)
                                    .getStringAnnotations(tag = "url", start = offset, end = offset)
                                    .firstOrNull()?.let { uriHandler.openUri(it.item) }
                            }
                        )
                    }
                }
                is MdBlock.Quote -> Text(
                    text = parseInline(block.text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)
                )
                MdBlock.Rule -> {
                    Spacer(Modifier.height(4.dp))
                    Divider()
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

/** Code-block colours on the dark code surface (both themes). */
private val CodeBlockHighlight = HighlightColors(
    keyword = Color(0xFFC4A7FF),
    string = Color(0xFF86EFAC),
    comment = Color(0xFF7C8B88),
    number = Color(0xFF7DD3FC),
    tag = Color(0xFF5EEAD4),
    heading = Color(0xFFFFC46B)
)

/**
 * Fenced code block (v1.2): dark code surface, language label, Copy
 * button that confirms with "Copied" for a moment, horizontal scroll
 * for long lines. Highlighting is the same hand-rolled highlighter as
 * the diff card (Phase 3, Step 5), computed once per block.
 */
@Composable
private fun CodeBlock(block: MdBlock.Code, onCopy: () -> Unit) {
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_500)
            copied = false
        }
    }
    val highlighted = remember(block.code, block.language) {
        highlightCode(block.code, languageForName(block.language), CodeBlockHighlight)
    }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        shape = RoundedCornerShape(14.dp),
        color = SathiColors.CodeSurface
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().background(SathiColors.CodeHeader).padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = block.language.ifBlank { "code" },
                    style = SathiCodeType.small,
                    color = SathiColors.CodeMuted,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = { onCopy(); copied = true },
                    colors = ButtonDefaults.textButtonColors(contentColor = SathiColors.Mint)
                ) {
                    Icon(
                        if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                        contentDescription = if (copied) "Copied" else "Copy code",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (copied) "Copied" else "Copy", style = MaterialTheme.typography.labelMedium)
                }
            }
            Text(
                text = highlighted,
                style = SathiCodeType.body,
                color = SathiColors.CodeText,
                softWrap = false,
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            )
        }
    }
}
