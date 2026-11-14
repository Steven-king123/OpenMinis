package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Semantic tests of the actual native block parser, fragment splitter and incremental cache.
 * Formula bodies below are exact screenshot LaTeX, not reconstructed conversation fixtures.
 * Only neutral surrounding prose is retained; no session IDs or private messages are included.
 */
class DisplayMathRegressionTest {
    private val dollars = "$" + "$"

    private val ratio = "AA:Aa:aa=4:4:1"
    private val geneticsAligned = """
        \begin{aligned}
        AA&:\frac13+\frac23\times\frac14=\frac12\\
        Aa&:\frac23\times\frac12=\frac13\\
        aa&:\frac23\times\frac14=\frac16
        \end{aligned}
    """.trimIndent()
    private val underbrace = """
        \underbrace{(q\mathbf w\times\mathbf B)\cdot\mathbf u}_{\text{对整体运动的正功率}}
        =
        -\underbrace{(q\mathbf u\times\mathbf B)\cdot\mathbf w}_{\text{对定向运动的负功率}}.
    """.trimIndent()
    private val dotProduct = """\mathbf F_B\cdot\mathbf v=0."""
    private val sumAligned = """
        \begin{aligned}
        P_n
        &=2\left(1-\frac12+\frac12-\frac13+\cdots+\frac1n-\frac1{n+1}\right)\\
        &=\boxed{2-\frac2{n+1}}.
        \end{aligned}
    """.trimIndent()
    private val inequalityAligned = """
        \begin{aligned}
        P_n<Q_n
        &\iff 2-\frac2{n+1}<2-\frac1{2^{n-1}}\\
        &\iff \frac2{n+1}>\frac1{2^{n-1}}\\
        &\iff \boxed{2^n>n+1}.
        \end{aligned}
    """.trimIndent()
    private val screenshotBodies
        get() = listOf(ratio, geneticsAligned, underbrace, dotProduct, sumAligned, inequalityAligned)

    private fun display(body: String, dollar: Boolean = false): String =
        if (dollar) "$dollars\n$body\n$dollars" else "\\[\n$body\n\\]"

    private fun assertMath(content: String, vararg expected: String) {
        assertEquals("actual MathDisplay latex must be recognized intact", expected.toList(), debugCollectDisplayLatex(content))
    }

    private fun assertSplitMath(content: String, expected: List<String>) {
        val fragments = splitMarkdownIntoBlockTexts(content)
        assertTrue("splitter must retain a fragment", fragments.isNotEmpty())
        assertEquals(
            "independently parsed fragments must contain complete display pairs, not two equally broken parses",
            expected,
            fragments.flatMap { debugCollectDisplayLatex(it) },
        )
        for (body in expected) {
            assertEquals("one fragment must retain the exact multiline body", 1, fragments.count { it.contains(body) })
        }
    }

    /**
     * Hooks consume cumulative snapshots, not deltas. Each cut creates a fresh two-snapshot
     * replay so both sides of every delimiter and each internal blank are exercised.
     * There are only length + 1 replays (at most twice that many parser snapshots),
     * not a replay of all prior prefixes for every cut, which would become cubic work.
     */
    private fun assertEveryCut(content: String, expected: List<String>) {
        assertEquals("positive full-parse oracle", expected, debugCollectDisplayLatex(content))
        for (cut in 0..content.length) {
            assertEquals(
                "incremental semantic result at cut $cut of ${content.length}",
                expected,
                debugIncrementalDisplayLatex(listOf(content.substring(0, cut), content)),
            )
        }
        // Also exercise several consecutive cache updates, including the final sealed boundary.
        val snapshots = (0..content.length step 7).map { content.substring(0, it) } + content
        assertEquals(expected, debugIncrementalDisplayLatex(snapshots))
    }

    @Test
    fun screenshotFormulaBodiesAreExactAndRecognizedAfterAdjacentProse() {
        assertEquals(listOf(14, 140, 151, 28, 129, 136), screenshotBodies.map { it.length })
        for (body in screenshotBodies) {
            for (dollar in listOf(false, true)) {
                val content = "说明：\n${display(body, dollar)}\n结束。"
                assertMath(content, body)
                assertEquals(listOf("Paragraph", "MathDisplay", "Paragraph"), debugParseBlockShapes(content))
            }
        }
    }

    @Test
    fun sameLinePairsStillDispatchAfterProseWithoutABlankLine() {
        for (pair in listOf("\\[$ratio\\]", "$dollars$ratio$dollars")) {
            val content = "说明：\n$pair\n结束。"
            assertMath(content, ratio)
            assertEquals(listOf("Paragraph", "MathDisplay", "Paragraph"), debugParseBlockShapes(content))
        }
    }

    @Test
    fun completeAlignedAndUnderbraceBodiesRetainInternalBlankLines() {
        for (original in listOf(geneticsAligned, underbrace, sumAligned)) {
            val body = original.replaceFirst("\n", "\n\n")
            for (dollar in listOf(false, true)) {
                val content = "说明：\n${display(body, dollar)}\n\n结束。"
                assertMath(content, body)
                assertSplitMath(content, listOf(body))
            }
        }
    }

    @Test
    fun indentedOrderedAndBulletContinuationsRecognizeMultilineDisplayMath() {
        val body = geneticsAligned.replaceFirst("\n", "\n\n")
        for ((marker, shape) in listOf("-" to "UnorderedList", "1." to "OrderedList")) {
            for (dollar in listOf(false, true)) {
                val indented = display(body, dollar).lineSequence().joinToString("\n") { "   $it" }
                val content = "$marker 说明：\n$indented\n   结束。"
                assertMath(content, body)
                assertTrue("formula must remain a list child", debugParseBlockShapes(content).any {
                    it.startsWith("$shape[") && it.contains("MathDisplay")
                })
                assertEquals(listOf(body), splitMarkdownIntoBlockTexts(content).flatMap { debugCollectDisplayLatex(it) })
            }
        }
    }

    @Test
    fun quotedDisplayMathIsCollectedRecursivelyWithoutQuoteMarkersInLatex() {
        val body = underbrace.replaceFirst("\n", "\n\n")
        for (dollar in listOf(false, true)) {
            val content = ("说明：\n${display(body, dollar)}\n结束。")
                .lineSequence().joinToString("\n") { "> $it" }
            assertMath(content, body)
            assertTrue(debugParseBlockShapes(content).single().startsWith("BlockQuote"))
            assertEquals(listOf(body), splitMarkdownIntoBlockTexts(content).flatMap { debugCollectDisplayLatex(it) })
        }
    }

    @Test
    fun splitterKeepsBothDelimiterKindsAndMultipleFormulasWhole() {
        val first = geneticsAligned.replaceFirst("\n", "\n\n")
        val second = underbrace.replaceFirst("\n", "\n\n")
        val content = "说明：\n${display(first)}\n\n说明：\n${display(second, true)}\n\n结束。"
        assertMath(content, first, second)
        assertSplitMath(content, listOf(first, second))
    }

    @Test
    fun everyScreenshotFormulaConvergesAtEveryPossibleStreamingCut() {
        for (body in screenshotBodies) {
            for (dollar in listOf(false, true)) {
                assertEveryCut("说明：\n${display(body, dollar)}\n\n结束。", listOf(body))
            }
        }
    }

    @Test
    fun blankInternalListAndQuoteStreamsDoNotFreezeAnIncompleteFormula() {
        val body = geneticsAligned.replaceFirst("\n", "\n\n")
        for (dollar in listOf(false, true)) {
            val math = display(body, dollar)
            assertEveryCut("说明：\n$math\n\n结束。", listOf(body))
            val indented = math.lineSequence().joinToString("\n") { "   $it" }
            assertEveryCut("1. 说明：\n$indented\n   结束。\n\n结束。", listOf(body))
            val quoted = math.lineSequence().joinToString("\n") { "> $it" }
            assertEveryCut("> 说明：\n$quoted\n> 结束。\n\n结束。", listOf(body))
        }
    }

    @Test fun incompleteMathRemainsLiteralAndCannotConsumeCodeOrHeading() {
        val raw = "说明：\n\\[\n\\frac{1}{2}_x\n\n## next\n```tex\n\\[inside\\]\n```"
        assertTrue(debugCollectDisplayLatex(raw).isEmpty())
        assertTrue(debugParseBlockShapes(raw).contains("RawMath"))
        assertTrue(debugParseBlockShapes(raw).contains("CodeBlock"))
        assertTrue(parseInlineForTest("\\frac{1}{2} \\begin{aligned} \\[ \\]").text.contains("\\frac"))
        assertTrue(parseInlineForTest("\\[ \\]").text.contains("\\["))
    }

    @Test fun fencedAndInlineCodeAreNotDisplayMath() {
        val raw = "```latex\n\\[\nx=1\n\\]\n```\n\n`\\[x=2\\]`\n\n~~~tex\n${dollars}\nx=3\n${dollars}\n~~~"
        assertTrue(debugCollectDisplayLatex(raw).isEmpty())
        assertTrue(splitMarkdownIntoBlockTexts(raw).flatMap { debugCollectDisplayLatex(it) }.isEmpty())
    }

    @Test fun normalPunctuationEscapesStillWorkAndInlineLatexIsUnchanged() {
        assertEquals("*literal*", parseInlineForTest("\\*literal\\*").text)
        assertEquals(listOf("x_i"), collectInlineMathLatex("value \\(x_i\\)"))
        assertEquals(listOf("x_i"), collectInlineMathLatex("value \$x_i\$"))
        assertTrue(debugCollectDisplayLatex("Price \\$5 and \\$10; ordinary text").isEmpty())
    }

    @Test fun rejectedDollarCandidatesDoNotSwallowFollowingDocuments() {
        assertTrue(debugCollectDisplayLatex("${dollars}\nno closer\n\n# Heading\ntext").isEmpty())
        assertTrue(debugParseBlockShapes("${dollars}\nno closer\n\n# Heading\ntext").contains("Heading"))
        assertMath("${dollars}x=1${dollars}", "x=1")
    }
}
