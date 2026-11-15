package com.inyogeshwar.codesathi.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParseTest {
    @Test
    fun fencedCodeBlockKeepsLanguageAndCode() {
        val blocks = parseMarkdown("Intro\n\n```kotlin\nval x = 1\nprintln(x)\n```\nAfter")
        val code = blocks.filterIsInstance<MdBlock.Code>().single()
        assertEquals("kotlin", code.language)
        assertEquals("val x = 1\nprintln(x)", code.code)
        assertTrue(blocks.first() is MdBlock.Paragraph)
        assertTrue(blocks.last() is MdBlock.Paragraph)
    }
}
