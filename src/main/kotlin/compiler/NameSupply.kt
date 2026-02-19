package org.example.compiler

/** Generates unique names for continuation parameters and loop variables. */
internal class NameSupply {
    private var argCounter = 0
    private var loopCounter = 0

    /** Returns a fresh name for a continuation lambda parameter (e.g. `arg0`, `arg1`). */
    fun freshArg(): String = "arg${argCounter++}"

    /** Returns a fresh name for a loop continuation variable (e.g. `__loop_0`). */
    fun freshLoop(): String = "__loop_${loopCounter++}"

    /** Resets all counters. Called at the start of each compilation. */
    fun reset() {
        argCounter = 0
        loopCounter = 0
    }
}
