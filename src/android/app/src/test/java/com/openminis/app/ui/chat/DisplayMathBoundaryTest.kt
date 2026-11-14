package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM boundary tests: no Android, parser, renderer or private conversation fixtures. */
class DisplayMathBoundaryTest {
    private fun close(vararg lines: String): DisplayMathBoundary.Close? {
        val input = lines.toList()
        val start = DisplayMathBoundary.start(input.first()) ?: return null
        return DisplayMathBoundary.close(input, 0, start)
    }

    @Test fun startsPreserveOriginalOffsets() {
        assertEquals(DisplayMathBoundary.Start(DisplayMathBoundary.Kind.BRACKET, 5),
            DisplayMathBoundary.start(" \t \\[x"))
        assertEquals(DisplayMathBoundary.Start(DisplayMathBoundary.Kind.DOLLAR, 4),
            DisplayMathBoundary.start("  \$\$x"))
        assertEquals(DisplayMathBoundary.Start(DisplayMathBoundary.Kind.DOLLAR, 2),
            DisplayMathBoundary.start("\$\$"))
    }

    @Test fun nonDisplayAndEscapedStartsStayLiteral() {
        listOf("", "  ", "text \\[x\\]", "\\(x\\)", "\$x\$", "\$\$\$x", "\$\$\$\$",
            "\\\\[", "\\\\\\[", "\\\$\$x", "`\\[x\\]`", "```\\[", "> \\[", "- \\[")
            .forEach { assertNull(it, DisplayMathBoundary.start(it)) }
    }

    @Test fun shortBracketMathAndFollowingProse() {
        assertEquals(DisplayMathBoundary.Close(0, 15), close("\\[\\frac{a}{b}=1\\]说明："))
        assertEquals(DisplayMathBoundary.Close(0, 5), close("  \\[x\\]"))
        assertEquals(DisplayMathBoundary.Close(1, 1), close("\\[", "x\\]说明："))
    }

    @Test fun alignedUnderbraceAndInternalBlanksRemainOneBlock() {
        val lines = listOf("\\[", "\\begin{aligned}", "a&=\\underbrace{x+y}_{n}\\\\", "",
            "b&=2", "\\end{aligned}", "  \\]", "说明：")
        assertEquals(DisplayMathBoundary.Close(6, 2),
            DisplayMathBoundary.close(lines, 0, DisplayMathBoundary.start(lines[0])!!))
        assertEquals(DisplayMathBoundary.Close(3, 0), close("\$\$", "x=1", "", "\$\$"))
    }

    @Test fun finalBracketCloserNeedsNoTrailingNewline() {
        assertEquals(DisplayMathBoundary.Close(1, 0), close("\\[x", "\\]"))
    }

    @Test fun escapedBracketCloseDoesNotEndFormula() {
        assertEquals(DisplayMathBoundary.Close(2, 2), close("\\[", "x\\\\]literal", "y \\]"))
        assertNull(close("\\[x\\\\]"))
        // Two literal slashes before the delimiter slash leave the third slash unescaped.
        assertEquals(DisplayMathBoundary.Close(1, 3), close("\\[", "x\\\\\\]"))
    }

    @Test fun dollarsSupportPairedSingleAndMultilineMath() {
        assertEquals(DisplayMathBoundary.Close(0, 10), close("\$\$E = mc^2\$\$"))
        assertEquals(DisplayMathBoundary.Close(0, 3), close("\$\$x\$\$  "))
        assertEquals(DisplayMathBoundary.Close(1, 5), close("\$\$", "x=1  \$\$"))
        assertEquals(DisplayMathBoundary.Close(2, 2), close("\$\$", "x", "  \$\$  "))
    }

    @Test fun unmatchedMoneyAndAmbiguousDollarClosersStayLiteral() {
        assertNull(close("\$\$ money", "说明："))
        assertNull(close("\$\$10", "# heading", "\$\$"))
        assertNull(close("\$\$x\$\$ prose"))
        assertNull(close("\$\$x\$\$\$"))
        assertNull(close("\$\$x\\\$\$"))
        assertNull(close("\$\$...\$\$"))
    }

    @Test fun escapedDollarPairCanBePassedBeforeRealClose() {
        val line = "\$\$x\\\$\$ + y\$\$"
        assertEquals(DisplayMathBoundary.Close(0, line.length - 2), close(line))
    }

    @Test fun inlineBackticksShieldBothDelimiterKinds() {
        assertEquals(DisplayMathBoundary.Close(0, 7), close("\\[`\\]`x\\]"))
        assertEquals(DisplayMathBoundary.Close(0, 9), close("\$\$`\$\$`x+y\$\$"))
        assertNull(close("\\[`\\]"))
        assertEquals(DisplayMathBoundary.Close(2, 3), close("\\[`", "\\]", "` x\\]"))
        assertEquals(DisplayMathBoundary.Close(0, 11), close("\\[``a`\\]``x\\]"))
    }

    @Test fun everyUnmistakableNewBlockStopsRecovery() {
        listOf("```kotlin", "~~~", "# heading", "###### heading", "- item", "+ item",
            "* item", "1. item", "2) item", "> quote", "   ```", "  - item").forEach { boundary ->
            assertTrue(boundary, DisplayMathBoundary.isRecoveryBoundary(boundary))
            assertNull(boundary, close("\\[x", boundary, "\\]"))
            assertNull(boundary, close("\$\$x", boundary, "\$\$"))
        }
    }

    @Test fun texOperatorsAndBlanksAreNotRecoveryBoundaries() {
        listOf("", "  ", "x=1", "-x+y", "+x", "*x", "1.5x", "#1", "\\begin{aligned}",
            "\\underbrace{x}_{n}", "a &> b").forEach {
            assertFalse(it, DisplayMathBoundary.isRecoveryBoundary(it))
        }
    }

    @Test fun newOpenersCannotBeStolenAsLaterClosers() {
        assertNull(close("\\[x", "\\[y", "\\]"))
        assertNull(close("\\[x", "\$\$y\$\$", "\\]"))
        assertNull(close("\$\$x", "\\[y\\]", "\$\$"))
        assertNull(close("\$\$x", "\$\$y\$\$"))
    }

    @Test fun absoluteLineAndOffsetAreReturned() {
        val lines = listOf("说明：", " \t\\[x", "   \\]", "# heading")
        assertEquals(DisplayMathBoundary.Close(2, 3),
            DisplayMathBoundary.close(lines, 1, DisplayMathBoundary.start(lines[1])!!))
    }

    @Test fun malformedArgumentsAreSafe() {
        val start = DisplayMathBoundary.Start(DisplayMathBoundary.Kind.BRACKET, 2)
        assertNull(DisplayMathBoundary.close(emptyList(), 0, start))
        assertNull(DisplayMathBoundary.close(listOf("\\["), -1, start))
        assertNull(DisplayMathBoundary.close(listOf("\\["), 1, start))
        assertNull(DisplayMathBoundary.close(listOf("\\[x\\]"), 0, start.copy(bodyStart = 99)))
        assertNull(DisplayMathBoundary.close(listOf("\\[x\\]"), 0,
            DisplayMathBoundary.Start(DisplayMathBoundary.Kind.DOLLAR, 2)))
    }

    @Test fun lineLimitIncludesOpeningAndClosingLines() {
        val accepted = listOf("\\[") + List(510) { "x" } + "\\]"
        val rejected = listOf("\\[") + List(511) { "x" } + "\\]"
        assertEquals(DisplayMathBoundary.Close(511, 0),
            DisplayMathBoundary.close(accepted, 0, DisplayMathBoundary.start(accepted[0])!!))
        assertNull(DisplayMathBoundary.close(rejected, 0, DisplayMathBoundary.start(rejected[0])!!))
    }

    @Test fun characterLimitIncludesDelimitersAndLineSeparators() {
        val accepted = "\\[" + "x".repeat(65532) + "\\]"
        assertEquals(DisplayMathBoundary.Close(0, 65534), close(accepted))
        assertNull(close("\\[" + "x".repeat(65533) + "\\]"))
        assertEquals(DisplayMathBoundary.Close(1, 0), close("\\[" + "x".repeat(65531), "\\]"))
        assertNull(close("\\[" + "x".repeat(65532), "\\]"))
    }

    @Test fun incompleteStreamingPrefixesDoNotInventClosers() {
        val complete = "\\[\\underbrace{x+y}_{n}\\]"
        for (cut in 0 until complete.length) {
            assertNull("cut=$cut", close(complete.substring(0, cut)))
        }
        assertEquals(DisplayMathBoundary.Close(0, complete.length - 2), close(complete))
    }
}
