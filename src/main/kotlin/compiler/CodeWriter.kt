package org.example.compiler

/** Encapsulates indented Java source code emission. */
internal class CodeWriter {
    private val buffer = StringBuilder()
    private var indentDepth = 0

    /** Emits a line of code at the current indentation depth. */
    fun line(text: String) {
        buffer.appendLine("${INDENT.repeat(indentDepth)}$text")
    }

    /** Emits a blank line. */
    fun blankLine() {
        buffer.appendLine()
    }

    /** Emits `header {`, runs [body] indented, then emits `}`. */
    fun block(header: String, body: () -> Unit) {
        line("$header {")
        indented(body)
        line("}")
    }

    /** Runs [body] with the indentation depth increased by one. */
    fun indented(body: () -> Unit) {
        indentDepth++
        body()
        indentDepth--
    }

    override fun toString(): String = buffer.toString()

    companion object {
        private const val INDENT = "  "
    }
}
