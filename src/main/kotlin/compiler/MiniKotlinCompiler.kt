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
import MiniKotlinParser
import MiniKotlinParser.*

class MiniKotlinCompiler : MiniKotlinBaseVisitor<String>() {

    /** Counter for generating unique continuation parameter names (arg0, arg1, …). */
    private var argCounter = 0

    /** Counter for generating unique loop continuation names (__loop_0, __loop_1, …). */
    private var loopCounter = 0

    /**
     * Variables in the current function that are targets of assignment statements.
     * These must be wrapped in single-element arrays so that Java lambdas can
     * mutate them (Java requires captured locals to be effectively final).
     */
    private var reassignedVariables = emptySet<String>()

    private fun freshArg(): String = "arg${argCounter++}"

    private fun freshLoop(): String = "__loop_${loopCounter++}"

    // -- Entry point ----------------------------------------------------------

    /** Compiles a complete MiniKotlin program into a single Java class with CPS-transformed functions. */
    fun compile(program: ProgramContext, className: String = "MiniProgram"): String {
        argCounter = 0
        loopCounter = 0
        val w = CodeWriter()
        w.line("public class $className {")
        w.indented {
            for (function in program.functionDeclaration()) {
                w.blankLine()
                compileFunction(function, w)
            }
        }
        w.line("}")
        return w.toString()
    }

    // -- Function compilation -------------------------------------------------

    /**
     * Compiles a single function declaration. `main` becomes Java's standard
     * entry point; all other functions receive an extra [Continuation] parameter
     * and use it to deliver their return value instead of returning directly.
     */
    private fun compileFunction(function: FunctionDeclarationContext, w: CodeWriter) {
        val name = function.IDENTIFIER().text

        reassignedVariables = collectReassignedVariables(function.block().statement())

        if (name == "main") {
            w.line("public static void main(String[] args) {")
        } else {
            val returnType = toJavaType(function.type())
            val params = buildList {
                function.parameterList()?.parameter()?.forEach { p ->
                    add("${toJavaType(p.type())} ${p.IDENTIFIER().text}")
                }
                add("Continuation<$returnType> __continuation")
            }
            w.line("public static void $name(${params.joinToString(", ")}) {")
        }

        // Non-main functions that fall through without a return must still call their continuation.
        val implicitReturn: (() -> Unit)? = if (name != "main") {
            { w.line("__continuation.accept(null);") }
        } else null

        w.indented { compileStatements(function.block().statement(), w, implicitReturn) }
        w.line("}")
    }

    /**
     * Collects names of all variables that appear as assignment targets anywhere
     * in the function body, including inside nested if/while blocks.
     */
    private fun collectReassignedVariables(stmts: List<StatementContext>): Set<String> {
        val result = mutableSetOf<String>()
        fun walk(stmts: List<StatementContext>) {
            for (stmt in stmts) {
                stmt.variableAssignment()?.let { result.add(it.IDENTIFIER().text) }
                stmt.ifStatement()?.let { ifStmt ->
                    for (block in ifStmt.block()) walk(block.statement())
                }
                stmt.whileStatement()?.let { walk(it.block().statement()) }
            }
        }
        walk(stmts)
        return result
    }

    private fun isReassigned(name: String): Boolean = name in reassignedVariables

    // -- Statement compilation ------------------------------------------------

    /**
     * Compiles a list of statements in CPS style. Each statement that involves
     * a function call nests the remaining statements inside its continuation.
     *
     * [onEmpty] is invoked when the statement list is exhausted. Used by while
     * loops to emit the loop-back call at the innermost nesting level.
     */
    private fun compileStatements(
        stmts: List<StatementContext>,
        w: CodeWriter,
        onEmpty: (() -> Unit)? = null,
    ) {
        if (stmts.isEmpty()) {
            onEmpty?.invoke()
            return
        }

        val stmt = stmts.first()
        val rest = stmts.subList(1, stmts.size)

        when {
            stmt.variableDeclaration() != null ->
                compileVariableDeclaration(stmt.variableDeclaration(), rest, w, onEmpty)
            stmt.variableAssignment() != null ->
                compileAssignment(stmt.variableAssignment(), rest, w, onEmpty)
            stmt.returnStatement() != null ->
                compileReturn(stmt.returnStatement(), w)
            stmt.ifStatement() != null ->
                compileIf(stmt.ifStatement(), rest, w, onEmpty)
            stmt.whileStatement() != null ->
                compileWhile(stmt.whileStatement(), rest, w, onEmpty)
            stmt.expression() != null ->
                compileExpressionStatement(stmt.expression(), rest, w, onEmpty)
            else ->
                error("unsupported statement: ${stmt.text}")
        }
    }

    // -- Variable declarations ------------------------------------------------

    /**
     * Compiles a `var` declaration. If the variable is reassigned elsewhere in
     * the function, it is emitted as a single-element array to allow mutation
     * from inside Java lambdas.
     */
    private fun compileVariableDeclaration(
        decl: VariableDeclarationContext,
        rest: List<StatementContext>,
        w: CodeWriter,
        onEmpty: (() -> Unit)?,
    ) {
        val type = toJavaType(decl.type())
        val name = decl.IDENTIFIER().text
        val rhs = decl.expression()

        liftExpression(rhs, w) { value ->
            if (isReassigned(name)) {
                w.line("$type[] $name = {$value};")
            } else {
                w.line("$type $name = $value;")
            }
            compileStatements(rest, w, onEmpty)
        }
    }

    // -- Variable assignment --------------------------------------------------

    /** Compiles a variable reassignment, writing to `name[0]` for wrapped variables. */
    private fun compileAssignment(
        assign: VariableAssignmentContext,
        rest: List<StatementContext>,
        w: CodeWriter,
        onEmpty: (() -> Unit)?,
    ) {
        val name = assign.IDENTIFIER().text
        val rhs = assign.expression()

        liftExpression(rhs, w) { value ->
            if (isReassigned(name)) {
                w.line("$name[0] = $value;")
            } else {
                w.line("$name = $value;")
            }
            compileStatements(rest, w, onEmpty)
        }
    }

    // -- Return ---------------------------------------------------------------

    /** Compiles a return statement by passing the value to `__continuation`. Subsequent statements are dead code. */
    private fun compileReturn(ret: ReturnStatementContext, w: CodeWriter) {
        val expr = ret.expression()
        if (expr == null) {
            w.line("__continuation.accept(null);")
            w.line("return;")
            return
        }

        liftExpression(expr, w) { value ->
            w.line("__continuation.accept($value);")
            w.line("return;")
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
        w: CodeWriter,
        onEmpty: (() -> Unit)?,
    ) {
        val condition = ifStmt.expression()
        val blocks = ifStmt.block()

        liftExpression(condition, w) { cond ->
            w.line("if ($cond) {")
            w.indented { compileStatements(blocks[0].statement() + rest, w, onEmpty) }
            w.line("}")

            val elseBody = if (blocks.size > 1) blocks[1].statement() + rest else rest
            w.line("else {")
            w.indented { compileStatements(elseBody, w, onEmpty) }
            w.line("}")
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
        w: CodeWriter,
        onEmpty: (() -> Unit)?,
    ) {
        val loopVar = freshLoop()
        val loopParameter = freshArg()
        val condition = whileStmt.expression()
        val body = whileStmt.block().statement()

        w.line("Continuation<Void>[] $loopVar = new Continuation[1];")
        w.line("$loopVar[0] = ($loopParameter) -> {")

        w.indented {
            liftExpression(condition, w) { cond ->
                w.line("if ($cond) {")
                w.indented {
                    compileStatements(body, w) { w.line("$loopVar[0].accept(null);") }
                }
                w.line("}")
                w.line("else {")
                w.indented { compileStatements(rest, w, onEmpty) }
                w.line("}")
            }
        }

        w.line("};")
        w.line("$loopVar[0].accept(null);")
    }

    // -- Expression statements ------------------------------------------------

    /** Compiles a bare expression used as a statement (e.g. a standalone function call). */
    private fun compileExpressionStatement(
        expr: ExpressionContext,
        rest: List<StatementContext>,
        w: CodeWriter,
        onEmpty: (() -> Unit)?,
    ) {
        if (containsFunctionCall(expr)) {
            liftExpression(expr, w) { _ ->
                compileStatements(rest, w, onEmpty)
            }
        } else {
            w.line("${compileExpression(expr)};")
            compileStatements(rest, w, onEmpty)
        }
    }

    // -- Expression lifting ---------------------------------------------------
    //
    // When an expression contains function calls, we cannot compile it as a
    // single Java expression. Instead we "lift" calls out: each call becomes
    // a CPS invocation whose continuation receives the result in a fresh
    // variable, and the rest of the expression is rebuilt using those variables.
    //
    // liftExpression walks the expression tree. For sub-trees without calls it
    // compiles directly. For calls it emits CPS code and passes the temp
    // variable into the [then] callback.

    /** Recursively checks whether an expression tree contains any function call. */
    private fun containsFunctionCall(expr: ExpressionContext): Boolean = when (expr) {
        is FunctionCallExprContext -> true
        is PrimaryExprContext -> {
            val p = expr.primary()
            p is ParenExprContext && containsFunctionCall(p.expression())
        }
        is NotExprContext -> containsFunctionCall(expr.expression())
        is AddSubExprContext,
        is MulDivExprContext,
        is ComparisonExprContext,
        is EqualityExprContext,
        is AndExprContext,
        is OrExprContext -> {
            val children = expr.children
                .filterIsInstance<ExpressionContext>()
            children.any { containsFunctionCall(it) }
        }
        else -> false
    }

    /**
     * Lifts function calls out of [expr] into CPS calls, then invokes [then]
     * with a simple Java expression string (no remaining calls).
     */
    private fun liftExpression(
        expr: ExpressionContext,
        w: CodeWriter,
        then: (simpleExpr: String) -> Unit,
    ) {
        if (!containsFunctionCall(expr)) {
            then(compileExpression(expr))
            return
        }

        when (expr) {
            is FunctionCallExprContext -> {
                val name = expr.IDENTIFIER().text
                val args = expr.argumentList().expression()
                liftExpressionList(args, w) { liftedArgs ->
                    val resultParam = freshArg()
                    if (name == "println") {
                        w.line("Prelude.println(${liftedArgs[0]}, ($resultParam) -> {")
                    } else {
                        w.line("$name(${liftedArgs.joinToString(", ")}, ($resultParam) -> {")
                    }
                    w.indented { then(resultParam) }
                    w.line("});")
                }
            }
            is AddSubExprContext ->
                liftBinaryOperation(expr.expression(0), expr.expression(1), operatorOf(expr), w, then)
            is MulDivExprContext ->
                liftBinaryOperation(expr.expression(0), expr.expression(1), operatorOf(expr), w, then)
            is ComparisonExprContext ->
                liftBinaryOperation(expr.expression(0), expr.expression(1), operatorOf(expr), w, then)
            is EqualityExprContext -> {
                liftExpression(expr.expression(0), w) { left ->
                    liftExpression(expr.expression(1), w) { right ->
                        val result = if (expr.EQ() != null)
                            "java.util.Objects.equals($left, $right)"
                        else
                            "!java.util.Objects.equals($left, $right)"
                        then(result)
                    }
                }
            }
            // Short-circuit: branch to avoid eagerly evaluating the RHS when it has calls.
            is AndExprContext -> {
                val leftExpr = expr.expression(0)
                val rightExpr = expr.expression(1)
                if (containsFunctionCall(rightExpr)) {
                    liftExpression(leftExpr, w) { leftVal ->
                        w.line("if ($leftVal) {")
                        w.indented { liftExpression(rightExpr, w, then) }
                        w.line("} else {")
                        w.indented { then("false") }
                        w.line("}")
                    }
                } else {
                    liftBinaryOperation(leftExpr, rightExpr, "&&", w, then)
                }
            }
            is OrExprContext -> {
                val leftExpr = expr.expression(0)
                val rightExpr = expr.expression(1)
                if (containsFunctionCall(rightExpr)) {
                    liftExpression(leftExpr, w) { leftVal ->
                        w.line("if ($leftVal) {")
                        w.indented { then("true") }
                        w.line("} else {")
                        w.indented { liftExpression(rightExpr, w, then) }
                        w.line("}")
                    }
                } else {
                    liftBinaryOperation(leftExpr, rightExpr, "||", w, then)
                }
            }
            is NotExprContext -> {
                liftExpression(expr.expression(), w) { inner -> then("(!$inner)") }
            }
            is PrimaryExprContext -> {
                val p = expr.primary()
                if (p is ParenExprContext) {
                    liftExpression(p.expression(), w) { inner -> then("($inner)") }
                } else {
                    then(compilePrimary(p))
                }
            }
            else -> then(compileExpression(expr))
        }
    }

    /** Lifts both operands of a binary operator left-to-right, then combines them. */
    private fun liftBinaryOperation(
        left: ExpressionContext,
        right: ExpressionContext,
        op: String,
        w: CodeWriter,
        then: (String) -> Unit,
    ) {
        liftExpression(left, w) { leftValue ->
            liftExpression(right, w) { rightValue ->
                then("($leftValue $op $rightValue)")
            }
        }
    }

    /** Lifts a list of expressions left-to-right, collecting simple results. */
    private fun liftExpressionList(
        exprs: List<ExpressionContext>,
        w: CodeWriter,
        then: (liftedExprs: List<String>) -> Unit,
    ) {
        fun go(index: Int, acc: List<String>) {
            if (index >= exprs.size) {
                then(acc)
            } else {
                liftExpression(exprs[index], w) { lifted ->
                    go(index + 1, acc + lifted)
                }
            }
        }
        go(0, emptyList())
    }

    // -- Operator mapping -----------------------------------------------------

    /** Maps an arithmetic or comparison expression node to its Java operator string. */
    private fun operatorOf(expr: ExpressionContext): String = when (expr) {
        is AddSubExprContext -> if (expr.PLUS() != null) "+" else "-"
        is MulDivExprContext -> when {
            expr.MULT() != null -> "*"
            expr.DIV() != null  -> "/"
            else                -> "%"
        }
        is ComparisonExprContext -> when {
            expr.LT() != null -> "<"
            expr.GT() != null -> ">"
            expr.LE() != null -> "<="
            else              -> ">="
        }
        else -> error("not a binary operator: ${expr::class.simpleName}")
    }

    // -- Type mapping ---------------------------------------------------------

    /** Maps a MiniKotlin type to its boxed Java equivalent (needed for generics). */
    private fun toJavaType(type: TypeContext): String = when {
        type.INT_TYPE() != null     -> "Integer"
        type.STRING_TYPE() != null  -> "String"
        type.BOOLEAN_TYPE() != null -> "Boolean"
        type.UNIT_TYPE() != null    -> "Void"
        else -> error("unknown type: ${type.text}")
    }

    // -- Expression compilation (simple, no function calls) -------------------

    /**
     * Compiles an expression that is guaranteed to contain no function calls
     * into a single Java expression string. Throws if a function call is
     * encountered — use [liftExpression] for expressions that may contain calls.
     */
    private fun compileExpression(expr: ExpressionContext): String = when (expr) {
        is PrimaryExprContext -> compilePrimary(expr.primary())
        is AddSubExprContext ->
            compileBinaryOperation(expr.expression(0), expr.expression(1), operatorOf(expr))
        is MulDivExprContext ->
            compileBinaryOperation(expr.expression(0), expr.expression(1), operatorOf(expr))
        is ComparisonExprContext ->
            compileBinaryOperation(expr.expression(0), expr.expression(1), operatorOf(expr))
        is EqualityExprContext -> {
            val left = compileExpression(expr.expression(0))
            val right = compileExpression(expr.expression(1))
            if (expr.EQ() != null)
                "java.util.Objects.equals($left, $right)"
            else
                "!java.util.Objects.equals($left, $right)"
        }
        is AndExprContext  -> compileBinaryOperation(expr.expression(0), expr.expression(1), "&&")
        is OrExprContext   -> compileBinaryOperation(expr.expression(0), expr.expression(1), "||")
        is NotExprContext  -> "(!${compileExpression(expr.expression())})"
        is FunctionCallExprContext -> error("function call in simple expression context: ${expr.text}")
        else -> error("unsupported expression: ${expr::class.simpleName}")
    }

    /** Compiles a simple binary operation into a parenthesized Java expression. */
    private fun compileBinaryOperation(left: ExpressionContext, right: ExpressionContext, op: String): String =
        "(${compileExpression(left)} $op ${compileExpression(right)})"

    /** Compiles a primary expression (literal, identifier, or parenthesized sub-expression). */
    private fun compilePrimary(primary: PrimaryContext): String = when (primary) {
        is IntLiteralContext -> primary.INTEGER_LITERAL().text
        is StringLiteralContext -> primary.STRING_LITERAL().text
        is BoolLiteralContext -> primary.BOOLEAN_LITERAL().text
        is IdentifierExprContext -> {
            val name = primary.IDENTIFIER().text
            if (isReassigned(name)) "$name[0]" else name
        }
        is ParenExprContext -> "(${compileExpression(primary.expression())})"
        else -> error("unsupported primary: ${primary::class.simpleName}")
    }
}

/** Encapsulates indented Java source code emission. */
private class CodeWriter {
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

    /** Runs [block] with the indentation depth increased by one. */
    fun indented(block: () -> Unit) {
        depth++
        block()
        depth--
    }

    override fun toString(): String = buffer.toString()
}
