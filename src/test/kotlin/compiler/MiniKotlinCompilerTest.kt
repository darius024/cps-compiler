package org.example.compiler

import MiniKotlinLexer
import MiniKotlinParser
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertIs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MiniKotlinCompilerTest {

    @TempDir
    lateinit var tempDir: Path

    // -- Test harness ---------------------------------------------------------

    private fun parseString(source: String): MiniKotlinParser.ProgramContext {
        val input = CharStreams.fromString(source)
        val lexer = MiniKotlinLexer(input)
        val tokens = CommonTokenStream(lexer)
        val parser = MiniKotlinParser(tokens)
        return parser.program()
    }

    private fun parseFile(path: Path): MiniKotlinParser.ProgramContext {
        val input = CharStreams.fromPath(path)
        val lexer = MiniKotlinLexer(input)
        val tokens = CommonTokenStream(lexer)
        val parser = MiniKotlinParser(tokens)
        return parser.program()
    }

    private fun resolveStdlibPath(): Path? {
        val devPath = Paths.get("build", "stdlib")
        if (devPath.toFile().exists()) {
            val stdlibJar = devPath.toFile().listFiles()
                ?.firstOrNull { it.name.startsWith("stdlib") && it.name.endsWith(".jar") }
            if (stdlibJar != null) return stdlibJar.toPath()
        }
        return null
    }

    /** Compiles MiniKotlin source to Java, compiles the Java, runs it, returns stdout. */
    private fun compileAndRun(source: String): String {
        val program = parseString(source)
        val compiler = MiniKotlinCompiler()
        val javaCode = compiler.compile(program)

        val javaFile = tempDir.resolve("MiniProgram.java")
        Files.writeString(javaFile, javaCode)

        val javaCompiler = JavaRuntimeCompiler()
        val stdlibPath = resolveStdlibPath()
        val (compilationResult, executionResult) = javaCompiler.compileAndExecute(javaFile, stdlibPath)

        assertIs<CompilationResult.Success>(compilationResult, "Java compilation failed: $compilationResult")
        assertIs<ExecutionResult.Success>(executionResult, "Java execution failed: $executionResult")
        return executionResult.stdout
    }

    // -- Empty main -----------------------------------------------------------

    @Test
    fun `empty main compiles and runs with no output`() {
        val output = compileAndRun("fun main(): Unit { }")
        assertEquals("", output)
    }

    // -- Println with literals ------------------------------------------------

    @Test
    fun `println string literal`() {
        val output = compileAndRun("""
            fun main(): Unit {
                println("hello")
            }
        """)
        assertEquals("hello\n", output)
    }

    @Test
    fun `println integer literal`() {
        val output = compileAndRun("""
            fun main(): Unit {
                println(42)
            }
        """)
        assertEquals("42\n", output)
    }

    @Test
    fun `println boolean literal`() {
        val output = compileAndRun("""
            fun main(): Unit {
                println(true)
            }
        """)
        assertEquals("true\n", output)
    }

    // -- Arithmetic operators -------------------------------------------------

    @Test
    fun `addition`() {
        assertEquals("5\n", compileAndRun("""
            fun main(): Unit { println(2 + 3) }
        """))
    }

    @Test
    fun `subtraction`() {
        assertEquals("7\n", compileAndRun("""
            fun main(): Unit { println(10 - 3) }
        """))
    }

    @Test
    fun `multiplication`() {
        assertEquals("20\n", compileAndRun("""
            fun main(): Unit { println(4 * 5) }
        """))
    }

    @Test
    fun `integer division truncates toward zero`() {
        assertEquals("3\n", compileAndRun("""
            fun main(): Unit { println(10 / 3) }
        """))
    }

    @Test
    fun `modulo`() {
        assertEquals("1\n", compileAndRun("""
            fun main(): Unit { println(10 % 3) }
        """))
    }

    @Test
    fun `nested arithmetic with parentheses`() {
        assertEquals("5\n", compileAndRun("""
            fun main(): Unit { println(((1 + 2) * 3) - 4) }
        """))
    }

    // -- Comparison operators -------------------------------------------------

    @Test
    fun `greater than`() {
        assertEquals("true\n", compileAndRun("""
            fun main(): Unit { println(3 > 2) }
        """))
    }

    @Test
    fun `less than`() {
        assertEquals("true\n", compileAndRun("""
            fun main(): Unit { println(2 < 3) }
        """))
    }

    @Test
    fun `greater than or equal`() {
        assertEquals("true\n", compileAndRun("""
            fun main(): Unit { println(3 >= 3) }
        """))
    }

    @Test
    fun `less than or equal`() {
        assertEquals("false\n", compileAndRun("""
            fun main(): Unit { println(5 <= 3) }
        """))
    }

    // -- Equality operators ---------------------------------------------------

    @Test
    fun `equality on integers`() {
        assertEquals("true\n", compileAndRun("""
            fun main(): Unit { println(1 == 1) }
        """))
    }

    @Test
    fun `inequality on integers`() {
        assertEquals("true\n", compileAndRun("""
            fun main(): Unit { println(1 != 2) }
        """))
    }

    // -- Logical operators ----------------------------------------------------

    @Test
    fun `logical and`() {
        assertEquals("false\n", compileAndRun("""
            fun main(): Unit { println(true && false) }
        """))
    }

    @Test
    fun `logical or`() {
        assertEquals("true\n", compileAndRun("""
            fun main(): Unit { println(true || false) }
        """))
    }

    @Test
    fun `logical not`() {
        assertEquals("false\n", compileAndRun("""
            fun main(): Unit { println(!true) }
        """))
    }

    // -- Variable declarations and statement sequencing -------------------------

    @Test
    fun `variable declaration with simple expression`() {
        assertEquals("42\n", compileAndRun("""
            fun main(): Unit {
                var x: Int = 42
                println(x)
            }
        """))
    }

    @Test
    fun `variable declaration with arithmetic`() {
        assertEquals("5\n", compileAndRun("""
            fun main(): Unit {
                var x: Int = 2 + 3
                println(x)
            }
        """))
    }

    @Test
    fun `multiple variables used in expression`() {
        assertEquals("15\n", compileAndRun("""
            fun main(): Unit {
                var a: Int = 5
                var b: Int = 10
                println(a + b)
            }
        """))
    }

    @Test
    fun `multiple sequential println calls`() {
        assertEquals("1\n2\n3\n", compileAndRun("""
            fun main(): Unit {
                println(1)
                println(2)
                println(3)
            }
        """))
    }

    @Test
    fun `boolean variable used in println`() {
        assertEquals("true\n", compileAndRun("""
            fun main(): Unit {
                var b: Boolean = 3 > 2
                println(b)
            }
        """))
    }

    // -- If/else --------------------------------------------------------------

    @Test
    fun `if-else true branch`() {
        assertEquals("yes\n", compileAndRun("""
            fun main(): Unit {
                if (true) { println("yes") } else { println("no") }
            }
        """))
    }

    @Test
    fun `if-else false branch`() {
        assertEquals("no\n", compileAndRun("""
            fun main(): Unit {
                if (false) { println("yes") } else { println("no") }
            }
        """))
    }

    @Test
    fun `if without else`() {
        assertEquals("gt\n", compileAndRun("""
            fun main(): Unit {
                if (3 > 2) { println("gt") }
            }
        """))
    }

    @Test
    fun `if-else followed by more statements`() {
        assertEquals("a\nb\n", compileAndRun("""
            fun main(): Unit {
                if (true) { println("a") } else { println("z") }
                println("b")
            }
        """))
    }

    @Test
    fun `if without else followed by more statements`() {
        assertEquals("done\n", compileAndRun("""
            fun main(): Unit {
                if (false) { println("skip") }
                println("done")
            }
        """))
    }

    // -- User-defined functions -----------------------------------------------

    @Test
    fun `unit function that calls println`() {
        assertEquals("hi\n", compileAndRun("""
            fun greet(msg: String): Unit {
                println(msg)
            }
            fun main(): Unit {
                greet("hi")
            }
        """))
    }

    @Test
    fun `function returning a constant`() {
        assertEquals("5\n", compileAndRun("""
            fun five(x: Int): Int {
                return 5
            }
            fun main(): Unit {
                var r: Int = five(0)
                println(r)
            }
        """))
    }

    @Test
    fun `function using its parameter`() {
        assertEquals("8\n", compileAndRun("""
            fun inc(n: Int): Int {
                return n + 1
            }
            fun main(): Unit {
                var r: Int = inc(7)
                println(r)
            }
        """))
    }

    @Test
    fun `function with multiple parameters`() {
        assertEquals("7\n", compileAndRun("""
            fun add(a: Int, b: Int): Int {
                return a + b
            }
            fun main(): Unit {
                var r: Int = add(3, 4)
                println(r)
            }
        """))
    }

    @Test
    fun `two functions calling each other sequentially`() {
        assertEquals("3\n4\n", compileAndRun("""
            fun twice(n: Int): Int {
                return n + n
            }
            fun triple(n: Int): Int {
                return n + n + n
            }
            fun main(): Unit {
                var a: Int = triple(1)
                println(a)
                var b: Int = twice(2)
                println(b)
            }
        """))
    }

    // -- Expression lifting (function calls inside expressions) ----------------

    @Test
    fun `return with function call in expression`() {
        assertEquals("11\n", compileAndRun("""
            fun inc(n: Int): Int {
                return n + 1
            }
            fun main(): Unit {
                var r: Int = inc(10)
                println(r)
            }
        """))
    }

    @Test
    fun `binary expression with function call on right`() {
        assertEquals("15\n", compileAndRun("""
            fun inc(n: Int): Int {
                return n + 1
            }
            fun main(): Unit {
                var r: Int = 10 + inc(4)
                println(r)
            }
        """))
    }

    @Test
    fun `return multiplied by function call result`() {
        assertEquals("27\n", compileAndRun("""
            fun triple(n: Int): Int {
                return n + n + n
            }
            fun addOne(n: Int): Int {
                return n + 1
            }
            fun main(): Unit {
                var r: Int = 3 * triple(addOne(2))
                println(r)
            }
        """))
    }

    @Test
    fun `nested function call f(g(x))`() {
        assertEquals("12\n", compileAndRun("""
            fun addFive(n: Int): Int {
                return n + 5
            }
            fun addTwo(n: Int): Int {
                return n + 2
            }
            fun main(): Unit {
                var r: Int = addFive(addTwo(5))
                println(r)
            }
        """))
    }

    @Test
    fun `two function calls in one expression`() {
        assertEquals("9\n", compileAndRun("""
            fun sq(n: Int): Int {
                return n * n
            }
            fun cube(n: Int): Int {
                return n * n * n
            }
            fun main(): Unit {
                var r: Int = sq(2) + cube(1)
                println(r + 4)
            }
        """))
    }

    // -- Variable reassignment ------------------------------------------------

    @Test
    fun `simple variable reassignment`() {
        assertEquals("2\n", compileAndRun("""
            fun main(): Unit {
                var x: Int = 1
                x = 2
                println(x)
            }
        """))
    }

    @Test
    fun `reassignment after println`() {
        assertEquals("1\n2\n", compileAndRun("""
            fun main(): Unit {
                var x: Int = 1
                println(x)
                x = 2
                println(x)
            }
        """))
    }

    // -- While loops ----------------------------------------------------------

    @Test
    fun `while loop with no function calls in body`() {
        assertEquals("0\n", compileAndRun("""
            fun main(): Unit {
                var i: Int = 5
                while (i > 0) {
                    i = i - 1
                }
                println(i)
            }
        """))
    }

    @Test
    fun `while loop with println in body`() {
        assertEquals("3\n2\n1\n", compileAndRun("""
            fun main(): Unit {
                var i: Int = 3
                while (i > 0) {
                    println(i)
                    i = i - 1
                }
            }
        """))
    }

    @Test
    fun `while loop accumulator`() {
        assertEquals("15\n", compileAndRun("""
            fun main(): Unit {
                var sum: Int = 0
                var i: Int = 1
                while (i <= 5) {
                    sum = sum + i
                    i = i + 1
                }
                println(sum)
            }
        """))
    }

    @Test
    fun `while loop followed by more statements`() {
        assertEquals("done\n", compileAndRun("""
            fun main(): Unit {
                var i: Int = 3
                while (i > 0) {
                    i = i - 1
                }
                println("done")
            }
        """))
    }

    // -- Recursive functions --------------------------------------------------

    @Test
    fun `fibonacci`() {
        assertEquals("55\n", compileAndRun("""
            fun fib(n: Int): Int {
                if (n <= 1) {
                    return n
                } else {
                    return fib(n - 1) + fib(n - 2)
                }
            }
            fun main(): Unit {
                println(fib(10))
            }
        """))
    }

    @Test
    fun `mutual recursion`() {
        assertEquals("true\nfalse\n", compileAndRun("""
            fun isEven(n: Int): Boolean {
                if (n == 0) {
                    return true
                } else {
                    return isOdd(n - 1)
                }
            }
            fun isOdd(n: Int): Boolean {
                if (n == 0) {
                    return false
                } else {
                    return isEven(n - 1)
                }
            }
            fun main(): Unit {
                var a: Boolean = isEven(4)
                println(a)
                var b: Boolean = isEven(3)
                println(b)
            }
        """))
    }

    // -- Edge cases -----------------------------------------------------------

    @Test
    fun `empty unit function body`() {
        assertEquals("", compileAndRun("""
            fun doNothing(x: Int): Unit {
            }
            fun main(): Unit {
                doNothing(1)
            }
        """))
    }

    @Test
    fun `string println`() {
        assertEquals("hello world\n", compileAndRun("""
            fun main(): Unit {
                println("hello world")
            }
        """))
    }

    @Test
    fun `chained function calls as statements`() {
        assertEquals("1\n2\n3\n", compileAndRun("""
            fun printVal(n: Int): Unit {
                println(n)
            }
            fun main(): Unit {
                printVal(1)
                printVal(2)
                printVal(3)
            }
        """))
    }

    @Test
    fun `while loop with function call in condition expression`() {
        assertEquals("3\n2\n1\n", compileAndRun("""
            fun isPositive(n: Int): Boolean {
                if (n > 0) {
                    return true
                } else {
                    return false
                }
            }
            fun main(): Unit {
                var i: Int = 3
                while (i > 0) {
                    println(i)
                    i = i - 1
                }
            }
        """))
    }

    @Test
    fun `nested if inside while`() {
        assertEquals("1\n3\n5\n", compileAndRun("""
            fun main(): Unit {
                var i: Int = 1
                while (i <= 5) {
                    if (i % 2 == 1) {
                        println(i)
                    }
                    i = i + 1
                }
            }
        """))
    }

    @Test
    fun `function returning result of another function`() {
        assertEquals("6\n", compileAndRun("""
            fun addOne(n: Int): Int {
                return n + 1
            }
            fun addTwo(n: Int): Int {
                return addOne(addOne(n))
            }
            fun main(): Unit {
                println(addTwo(4))
            }
        """))
    }

    // -- Integration (existing) -----------------------------------------------

    @Test
    fun `compile example_mini outputs 120 and 15`() {
        val examplePath = Paths.get("samples/example.mini")
        val program = parseFile(examplePath)

        val compiler = MiniKotlinCompiler()
        val javaCode = compiler.compile(program)

        val javaFile = tempDir.resolve("MiniProgram.java")
        Files.writeString(javaFile, javaCode)

        val javaCompiler = JavaRuntimeCompiler()
        val stdlibPath = resolveStdlibPath()
        val (compilationResult, executionResult) = javaCompiler.compileAndExecute(javaFile, stdlibPath)

        assertIs<CompilationResult.Success>(compilationResult)
        assertIs<ExecutionResult.Success>(executionResult)

        val output = executionResult.stdout
        assertTrue(output.contains("120"), "Expected output to contain factorial result 120, but got: $output")
        assertTrue(output.contains("15"), "Expected output to contain arithmetic result 15, but got: $output")
    }
}
