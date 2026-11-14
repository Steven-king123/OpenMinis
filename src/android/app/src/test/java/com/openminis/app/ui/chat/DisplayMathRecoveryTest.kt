package com.openminis.app.ui.chat

import org.junit.Assert.*
import org.junit.Test

class DisplayMathRecoveryTest {
    @Test(timeout = 5000) fun literalHashSuffixCannotStallTheParser() {
        for (suffix in listOf("#tail", "#1", "##", "after")) {
            val text = "\\[x=1\\]$suffix"
            assertEquals(listOf("x=1"), debugCollectDisplayLatex(text))
            assertTrue(debugParseBlockRaws(text).last().contains(suffix))
        }
        assertEquals(listOf("Paragraph"), debugParseBlockShapes("#tail"))
        assertEquals(listOf("Paragraph"), debugParseBlockShapes("#1"))
    }

    @Test fun incompleteMathRetainsEscapedBracesAndRowsAcrossBlankLines() {
        val raw = "\\[\nx=1\n\n\\{x\\} \\\\ y"
        assertEquals(listOf("RawMath"), debugParseBlockShapes(raw))
        assertEquals(listOf(raw), debugParseBlockRaws(raw))
        assertEquals(listOf(raw), splitMarkdownIntoBlockTexts(raw))
        assertTrue(debugCollectDisplayLatex(raw).isEmpty())
    }

    @Test fun recoveryStopsAtHeadingAndDoesNotLoseFollowingFencedCode() {
        val raw = "\\[\nx=1\n\n\\{x\\}\n## next\n~~~tex\n\\[not math\\]\n~~~"
        assertEquals(listOf("RawMath", "Heading", "CodeBlock"), debugParseBlockShapes(raw))
        assertTrue(debugParseBlockRaws(raw).first().contains("\\{x\\}"))
        assertTrue(debugCollectDisplayLatex(raw).isEmpty())
    }

    @Test fun listFormulaWithCodeKeepsBothAsStructuralChildren() {
        val text = "- intro\n  \\[x=1\\]\n  ```tex\n  \\frac{1}{2}\n  ```"
        val shapes = debugParseBlockShapes(text)
        assertTrue(shapes.toString(), shapes.single().contains("MathDisplay"))
        assertTrue(shapes.toString(), shapes.single().contains("CodeBlock"))
        assertEquals(listOf("x=1"), debugCollectDisplayLatex(text))
    }

    @Test fun taskListFormulaRemainsNestedWithInternalBlankLines() {
        val text = "- [x] explanation\n  \\[\n  a=b\n\n  c=d\n  \\]\n  tail"
        assertEquals(listOf("a=b\n\nc=d"), debugCollectDisplayLatex(text))
        assertTrue(debugParseBlockShapes(text).single().startsWith("TaskList["))
        assertEquals(listOf("a=b\n\nc=d"), splitMarkdownIntoBlockTexts(text).flatMap { debugCollectDisplayLatex(it) })
        assertEquals(listOf("a=b\n\nc=d"), debugIncrementalDisplayLatex((0..text.length step 3).map { text.take(it) } + text))
    }

    @Test(timeout = 10000) fun manySameLineFormulaPairsDoNotRecurseBySuffix() {
        val text = (1..600).joinToString("") { "\\[x=1\\]" }
        val latex = debugCollectDisplayLatex(text)
        assertEquals(600, latex.size)
        assertTrue(latex.all { it == "x=1" })
    }
}
