package org.example.compiler

/** Encapsulates indented Java source code emission. */
internal class CodeWriter {
    private val buffer = StringBuilder()
    private var depth = 0

    /** Emits a line of code at the current indentation depth. */
    fun line(text: String) {
        buffer.appendLine("${"  ".repeat(depth)}$text")
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

    /** Runs [block] with the indentation depth increased by one. */
    fun indented(block: () -> Unit) {
        depth++
        block()
        depth--
    }

    override fun toString(): String = buffer.toString()
}
