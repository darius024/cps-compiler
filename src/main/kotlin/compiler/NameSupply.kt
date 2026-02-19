package org.example.compiler

/** Generates unique names for continuation parameters and loop variables. */
internal class NameSupply {
    private var argCounter = 0
    private var loopCounter = 0

    fun freshArg(): String = "arg${argCounter++}"

    fun freshLoop(): String = "__loop_${loopCounter++}"

    fun reset() {
        argCounter = 0
        loopCounter = 0
    }
}
