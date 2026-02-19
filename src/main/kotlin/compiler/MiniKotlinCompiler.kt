/*
 * MiniKotlin to Java CPS Compiler
 *
 * Translates a MiniKotlin program (parsed by ANTLR) into Java source code
 * where every function call uses continuation-passing style. The generated
 * code depends on the stdlib's Continuation<T> interface and Prelude class.
 *
 * The core idea: after every function call the remaining computation moves
 * into a continuation lambda, turning sequential code into nested callbacks.
 */

package org.example.compiler

import MiniKotlinBaseVisitor
import MiniKotlinParser.*

/** Maps a MiniKotlin type to its boxed Java equivalent (needed for generics). */
internal fun toJavaType(type: TypeContext): String = when {
    type.INT_TYPE() != null     -> "Integer"
    type.STRING_TYPE() != null  -> "String"
    type.BOOLEAN_TYPE() != null -> "Boolean"
    type.UNIT_TYPE() != null    -> "Void"
    else -> error("unknown type: ${type.text}")
}

/**
 * Collects names of all variables that appear as assignment targets anywhere
 * in a function body, including inside nested if/while blocks.
 */
internal fun collectReassignedVariables(statements: List<StatementContext>): Set<String> {
    val result = mutableSetOf<String>()
    fun walk(statements: List<StatementContext>) {
        for (statement in statements) {
            statement.variableAssignment()?.let { result.add(it.IDENTIFIER().text) }
            statement.ifStatement()?.let { ifStmt ->
                for (block in ifStmt.block()) walk(block.statement())
            }
            statement.whileStatement()?.let { walk(it.block().statement()) }
        }
    }
    walk(statements)
    return result
}

class MiniKotlinCompiler : MiniKotlinBaseVisitor<String>() {

    private val names = NameSupply()
    private val expressions = SimpleExpressionCompiler(::isReassigned)
    private val lifter = ExpressionLifter(expressions, names)

    private var reassignedVariables = emptySet<String>()

    private fun isReassigned(name: String): Boolean = name in reassignedVariables

    // -- Entry point ----------------------------------------------------------

    /** Compiles a complete MiniKotlin program into a single Java class with CPS-transformed functions. */
    fun compile(program: ProgramContext, className: String = "MiniProgram"): String {
        names.reset()
        val writer = CodeWriter()
        writer.block("public class $className") {
            for (function in program.functionDeclaration()) {
                writer.blankLine()
                compileFunction(function, writer)
            }
        }
        return writer.toString()
    }

    // -- Function compilation -------------------------------------------------

    /**
     * Compiles a single function declaration. `main` becomes Java's standard
     * entry point; all other functions receive an extra [Continuation] parameter
     * and use it to deliver their return value instead of returning directly.
     */
    private fun compileFunction(function: FunctionDeclarationContext, writer: CodeWriter) {
        val name = function.IDENTIFIER().text

        reassignedVariables = collectReassignedVariables(function.block().statement())

        val header = if (name == "main") {
            "public static void main(String[] args)"
        } else {
            val returnType = toJavaType(function.type())
            val parameters = buildList {
                function.parameterList()?.parameter()?.forEach { param ->
                    add("${toJavaType(param.type())} ${param.IDENTIFIER().text}")
                }
                add("Continuation<$returnType> __continuation")
            }
            "public static void $name(${parameters.joinToString(", ")})"
        }

        // Non-main functions that fall through without a return must still call their continuation.
        val implicitReturn: (() -> Unit)? = if (name != "main") {
            { writer.line("__continuation.accept(null);") }
        } else null

        writer.block(header) { compileStatements(function.block().statement(), writer, implicitReturn) }
    }

    // -- Statement compilation ------------------------------------------------

    /**
     * Compiles a list of statements in CPS style. Each statement that involves
     * a function call nests the remaining statements inside its continuation.
     *
     * [onEmpty] is invoked when the statement list is exhausted. Used by while
     * loops to emit the loop-back call at the innermost nesting level.
     */
    private fun compileStatements(
        statements: List<StatementContext>,
        writer: CodeWriter,
        onEmpty: (() -> Unit)? = null,
    ) {
        if (statements.isEmpty()) {
            onEmpty?.invoke()
            return
        }

        val statement = statements.first()
        val rest = statements.subList(1, statements.size)

        when {
            statement.variableDeclaration() != null ->
                compileVariableDeclaration(statement.variableDeclaration(), rest, writer, onEmpty)
            statement.variableAssignment() != null ->
                compileAssignment(statement.variableAssignment(), rest, writer, onEmpty)
            statement.returnStatement() != null ->
                compileReturn(statement.returnStatement(), writer)
            statement.ifStatement() != null ->
                compileIf(statement.ifStatement(), rest, writer, onEmpty)
            statement.whileStatement() != null ->
                compileWhile(statement.whileStatement(), rest, writer, onEmpty)
            statement.expression() != null ->
                compileExpressionStatement(statement.expression(), rest, writer, onEmpty)
            else ->
                error("unsupported statement: ${statement.text}")
        }
    }

    // -- Variable declarations ------------------------------------------------

    /**
     * Compiles a `var` declaration. If the variable is reassigned elsewhere in
     * the function, it is emitted as a single-element array to allow mutation
     * from inside Java lambdas.
     */
    private fun compileVariableDeclaration(
        declaration: VariableDeclarationContext,
        rest: List<StatementContext>,
        writer: CodeWriter,
        onEmpty: (() -> Unit)?,
    ) {
        val type = toJavaType(declaration.type())
        val name = declaration.IDENTIFIER().text
        val initializer = declaration.expression()

        lifter.liftExpression(initializer, writer) { value ->
            if (isReassigned(name)) {
                writer.line("$type[] $name = {$value};")
            } else {
                writer.line("$type $name = $value;")
            }
            compileStatements(rest, writer, onEmpty)
        }
    }

    // -- Variable assignment --------------------------------------------------

    /** Compiles a variable reassignment, writing to `name[0]` for wrapped variables. */
    private fun compileAssignment(
        assignment: VariableAssignmentContext,
        rest: List<StatementContext>,
        writer: CodeWriter,
        onEmpty: (() -> Unit)?,
    ) {
        val name = assignment.IDENTIFIER().text
        val newValue = assignment.expression()

        lifter.liftExpression(newValue, writer) { value ->
            if (isReassigned(name)) {
                writer.line("$name[0] = $value;")
            } else {
                writer.line("$name = $value;")
            }
            compileStatements(rest, writer, onEmpty)
        }
    }

    // -- Return ---------------------------------------------------------------

    /** Compiles a return statement by passing the value to `__continuation`. Subsequent statements are dead code. */
    private fun compileReturn(returnStmt: ReturnStatementContext, writer: CodeWriter) {
        val expr = returnStmt.expression()
        if (expr == null) {
            writer.line("__continuation.accept(null);")
            writer.line("return;")
            return
        }

        lifter.liftExpression(expr, writer) { value ->
            writer.line("__continuation.accept($value);")
            writer.line("return;")
        }
    }

    // -- If/else --------------------------------------------------------------

    /**
     * Both branches must eventually reach the rest of the enclosing statement
     * list, so the rest is compiled into each branch (and the implicit empty
     * else when no else block is present).
     */
    private fun compileIf(
        ifStmt: IfStatementContext,
        rest: List<StatementContext>,
        writer: CodeWriter,
        onEmpty: (() -> Unit)?,
    ) {
        val condition = ifStmt.expression()
        val blocks = ifStmt.block()

        lifter.liftExpression(condition, writer) { compiledCondition ->
            writer.block("if ($compiledCondition)") {
                compileStatements(blocks[0].statement() + rest, writer, onEmpty)
            }

            val elseBody = if (blocks.size > 1) blocks[1].statement() + rest else rest
            writer.block("else") { compileStatements(elseBody, writer, onEmpty) }
        }
    }

    // -- While loops ----------------------------------------------------------

    /**
     * Compiles a while loop using a recursive continuation. A self-referencing
     * Continuation array is used because Java lambdas cannot reference
     * themselves directly.
     */
    private fun compileWhile(
        whileStmt: WhileStatementContext,
        rest: List<StatementContext>,
        writer: CodeWriter,
        onEmpty: (() -> Unit)?,
    ) {
        val loopVar = names.freshLoop()
        val loopParameter = names.freshArg()
        val condition = whileStmt.expression()
        val body = whileStmt.block().statement()

        writer.line("Continuation<Void>[] $loopVar = new Continuation[1];")
        writer.line("$loopVar[0] = ($loopParameter) -> {")

        writer.indented {
            lifter.liftExpression(condition, writer) { compiledCondition ->
                writer.block("if ($compiledCondition)") {
                    compileStatements(body, writer) { writer.line("$loopVar[0].accept(null);") }
                }
                writer.block("else") { compileStatements(rest, writer, onEmpty) }
            }
        }

        writer.line("};")
        writer.line("$loopVar[0].accept(null);")
    }

    // -- Expression statements ------------------------------------------------

    /** Compiles a bare expression used as a statement (e.g. a standalone function call). */
    private fun compileExpressionStatement(
        expr: ExpressionContext,
        rest: List<StatementContext>,
        writer: CodeWriter,
        onEmpty: (() -> Unit)?,
    ) {
        if (lifter.containsFunctionCall(expr)) {
            lifter.liftExpression(expr, writer) { _ ->
                compileStatements(rest, writer, onEmpty)
            }
        } else {
            writer.line("${expressions.compileExpression(expr)};")
            compileStatements(rest, writer, onEmpty)
        }
    }
}
