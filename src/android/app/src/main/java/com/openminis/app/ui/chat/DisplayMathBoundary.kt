package com.openminis.app.ui.chat

/**
 * Conservative, context-free display-math boundaries shared by block parsing and streaming.
 * Offsets are UTF-16 indices in the original (not trimmed) lines. Callers must mask fenced
 * code and strip quote/list prefixes before calling [start]; this is not a Markdown parser.
 * A start is only a candidate: never consume a display block unless [close] succeeds.
 */
internal object DisplayMathBoundary {
    enum class Kind(val open: String, val close: String) {
        BRACKET("\\[", "\\]"), DOLLAR("\$\$", "\$\$")
    }

    data class Start(val kind: Kind, val bodyStart: Int)
    data class Close(val line: Int, val offset: Int)

    private const val MAX_LINES = 512
    private const val MAX_CHARS = 64 * 1024

    /** Only line-leading delimiters are recognized; $$$ and escaped openers stay literal. */
    fun start(line: String): Start? {
        val first = line.indexOfFirst { !it.isWhitespace() }
        if (first < 0) return null
        return when {
            line.startsWith(Kind.BRACKET.open, first) -> Start(Kind.BRACKET, first + 2)
            isDollarPair(line, first) -> Start(Kind.DOLLAR, first + 2)
            else -> null
        }
    }

    /**
     * Search at most 512 whole lines / 64 KiB of UTF-16 characters (including LF separators).
     * Blank lines do NOT terminate paired math. Fences, headings, list items, quotes and new
     * openers do; null means leave the candidate as literal text, not an unclosed math block.
     * Inline backtick spans shield delimiters, including across lines. A dangling backtick
     * conservatively shields the remaining search. Dollar closers must be standalone or a
     * trailing pair after a plausible math body; bracket closers may have following prose.
     */
    fun close(lines: List<String>, from: Int, start: Start): Close? {
        if (from !in lines.indices) return null
        val opening = lines[from]
        if (opening.length > MAX_CHARS || start(opening) != start) return null
        var remaining = MAX_CHARS
        var codeRun = 0
        var plausibleDollarBody = false
        val end = minOf(lines.size.toLong(), from.toLong() + MAX_LINES).toInt()
        for (lineIndex in from until end) {
            val line = lines[lineIndex]
            val cost = line.length.toLong() + if (lineIndex == from) 0 else 1
            if (cost > remaining) return null
            remaining -= cost.toInt()
            var offset = if (lineIndex == from) start.bodyStart else 0
            if (lineIndex != from) {
                // Structural boundaries win even if a malformed inline-code span is pending.
                if (isRecoveryBoundary(line)) return null
                val next = start(line)
                if (next != null && !(start.kind == Kind.DOLLAR &&
                        next.kind == Kind.DOLLAR && line.substring(next.bodyStart).isBlank())) {
                    return null
                }
            }
            while (offset < line.length) {
                val ch = line[offset]
                if (ch == '`' && !isEscaped(line, offset)) {
                    var runEnd = offset + 1
                    while (runEnd < line.length && line[runEnd] == '`') runEnd++
                    val run = runEnd - offset
                    if (codeRun == 0) codeRun = run else if (run == codeRun) codeRun = 0
                    offset = runEnd
                    continue
                }
                if (codeRun != 0) {
                    offset++
                    continue
                }
                if (line.startsWith(start.kind.close, offset) && !isEscaped(line, offset)) {
                    if (start.kind == Kind.BRACKET && line.startsWith(Kind.BRACKET.close, offset)) {
                        return Close(lineIndex, offset)
                    }
                    if (start.kind == Kind.DOLLAR && isDollarPair(line, offset)) {
                        val standalone = line.substring(0, offset).isBlank()
                        val trailing = line.substring(offset + 2).isBlank()
                        if (trailing && (standalone || plausibleDollarBody)) {
                            return Close(lineIndex, offset)
                        }
                        // A non-trailing pair is ambiguous: do not bridge it to a later block.
                        return null
                    }
                }
                if (!ch.isWhitespace() && (ch.isLetterOrDigit() || ch in "\\=+-*/^_{}<>|")) {
                    plausibleDollarBody = true
                }
                offset++
            }
        }
        return null
    }

    /** Unmistakable block starts only, not generic prose or blank lines inside equations. */
    fun isRecoveryBoundary(line: String): Boolean {
        val text = line.trimStart()
        if (text.startsWith("```") || text.startsWith("~~~")) return true
        if (text.startsWith('>')) return true
        var i = 0
        while (i < text.length && text[i] == '#') i++
        if (i in 1..6 && (i == text.length || text[i].isWhitespace())) return true
        if (text.isNotEmpty() && text[0] in "-+*" &&
            (text.length == 1 || text[1].isWhitespace())) return true
        i = 0
        while (i < text.length && i < 9 && text[i] in '0'..'9') i++
        return i > 0 && i < text.length && text[i] in ".)" &&
            (i + 1 == text.length || text[i + 1].isWhitespace())
    }

    private fun isDollarPair(line: String, offset: Int): Boolean =
        line.startsWith(Kind.DOLLAR.open, offset) &&
            (offset == 0 || line[offset - 1] != '$') &&
            (offset + 2 == line.length || line[offset + 2] != '$') &&
            !isEscaped(line, offset)

    private fun isEscaped(line: String, offset: Int): Boolean {
        var i = offset - 1
        while (i >= 0 && line[i] == '\\') i--
        return (offset - 1 - i) % 2 != 0
    }
}
