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

    private var argCounter = 0
    private var loopCounter = 0
    private var reassignedVariables = emptySet<String>()

    private fun freshArg(): String = "arg${argCounter++}"
    private fun freshLoop(): String = "__loop_${loopCounter++}"

    // -- Entry point ----------------------------------------------------------

    fun compile(program: ProgramContext, className: String = "MiniProgram"): String {
        argCounter = 0
        loopCounter = 0
        return buildString {
            appendLine("public class $className {")
            for (function in program.functionDeclaration()) {
                appendLine()
                compileFunction(function, indent = 1, this)
            }
            appendLine("}")
        }
    }

    // -- Function compilation -------------------------------------------------

    private fun compileFunction(function: FunctionDeclarationContext, indent: Int, out: StringBuilder) {
        val name = function.IDENTIFIER().text
        val pad = "  ".repeat(indent)

        reassignedVariables = collectReassignedVariables(function.block().statement())

        if (name == "main") {
            out.appendLine("${pad}public static void main(String[] args) {")
        } else {
            val returnType = toJavaType(function.type())
            val params = buildList {
                function.parameterList()?.parameter()?.forEach { p ->
                    add("${toJavaType(p.type())} ${p.IDENTIFIER().text}")
                }
                add("Continuation<$returnType> __continuation")
            }
            out.appendLine("${pad}public static void $name(${params.joinToString(", ")}) {")
        }

        val implicitReturn: ((Int) -> Unit)? = if (name != "main") { innerIndent ->
            val pad = "  ".repeat(innerIndent)
            out.appendLine("${pad}__continuation.accept(null);")
        } else null

        compileStatements(function.block().statement(), indent + 1, out, implicitReturn)
        out.appendLine("${pad}}")
    }

    /** Collects names of all variables that appear as assignment targets. */
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
        indent: Int,
        out: StringBuilder,
        onEmpty: ((indent: Int) -> Unit)? = null,
    ) {
        if (stmts.isEmpty()) {
            onEmpty?.invoke(indent)
            return
        }

        val stmt = stmts.first()
        val rest = stmts.subList(1, stmts.size)

        when {
            stmt.variableDeclaration() != null ->
                compileVariableDeclaration(stmt.variableDeclaration(), rest, indent, out, onEmpty)
            stmt.variableAssignment() != null ->
                compileAssignment(stmt.variableAssignment(), rest, indent, out, onEmpty)
            stmt.returnStatement() != null ->
                compileReturn(stmt.returnStatement(), indent, out)
            stmt.ifStatement() != null ->
                compileIf(stmt.ifStatement(), rest, indent, out, onEmpty)
            stmt.whileStatement() != null ->
                compileWhile(stmt.whileStatement(), rest, indent, out, onEmpty)
            stmt.expression() != null ->
                compileExpressionStatement(stmt.expression(), rest, indent, out, onEmpty)
            else ->
                error("unsupported statement: ${stmt.text}")
        }
    }

    // -- Variable declarations ------------------------------------------------

    private fun compileVariableDeclaration(
        decl: VariableDeclarationContext,
        rest: List<StatementContext>,
        indent: Int,
        out: StringBuilder,
        onEmpty: ((Int) -> Unit)?,
    ) {
        val type = toJavaType(decl.type())
        val name = decl.IDENTIFIER().text
        val rhs = decl.expression()

        liftExpression(rhs, indent, out) { value, innerIndent ->
            val pad = "  ".repeat(innerIndent)
            if (isReassigned(name)) {
                out.appendLine("${pad}$type[] $name = {$value};")
            } else {
                out.appendLine("${pad}$type $name = $value;")
            }
            compileStatements(rest, innerIndent, out, onEmpty)
        }
    }

    // -- Variable assignment --------------------------------------------------

    private fun compileAssignment(
        assign: VariableAssignmentContext,
        rest: List<StatementContext>,
        indent: Int,
        out: StringBuilder,
        onEmpty: ((Int) -> Unit)?,
    ) {
        val name = assign.IDENTIFIER().text
        val rhs = assign.expression()

        liftExpression(rhs, indent, out) { value, innerIndent ->
            val pad = "  ".repeat(innerIndent)
            if (isReassigned(name)) {
                out.appendLine("${pad}$name[0] = $value;")
            } else {
                out.appendLine("${pad}$name = $value;")
            }
            compileStatements(rest, innerIndent, out, onEmpty)
        }
    }

    // -- Return ---------------------------------------------------------------

    private fun compileReturn(ret: ReturnStatementContext, indent: Int, out: StringBuilder) {
        val expr = ret.expression()
        if (expr == null) {
            val pad = "  ".repeat(indent)
            out.appendLine("${pad}__continuation.accept(null);")
            out.appendLine("${pad}return;")
            return
        }

        liftExpression(expr, indent, out) { value, innerIndent ->
            val pad = "  ".repeat(innerIndent)
            out.appendLine("${pad}__continuation.accept($value);")
            out.appendLine("${pad}return;")
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
        indent: Int,
        out: StringBuilder,
        onEmpty: ((Int) -> Unit)?,
    ) {
        val condition = ifStmt.expression()
        val blocks = ifStmt.block()

        liftExpression(condition, indent, out) { cond, condIndent ->
            val pad = "  ".repeat(condIndent)
            out.appendLine("${pad}if ($cond) {")
            compileStatements(blocks[0].statement() + rest, condIndent + 1, out, onEmpty)
            out.appendLine("${pad}}")

            val elseBody = if (blocks.size > 1) blocks[1].statement() + rest else rest
            out.appendLine("${pad}else {")
            compileStatements(elseBody, condIndent + 1, out, onEmpty)
            out.appendLine("${pad}}")
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
        indent: Int,
        out: StringBuilder,
        onEmpty: ((Int) -> Unit)?,
    ) {
        val pad = "  ".repeat(indent)
        val loopVar = freshLoop()
        val loopParameter = freshArg()
        val condition = whileStmt.expression()
        val body = whileStmt.block().statement()

        out.appendLine("${pad}Continuation<Void>[] $loopVar = new Continuation[1];")
        out.appendLine("${pad}$loopVar[0] = ($loopParameter) -> {")

        liftExpression(condition, indent + 1, out) { cond, condIndent ->
            val pad = "  ".repeat(condIndent)
            out.appendLine("${pad}if ($cond) {")
            compileStatements(body, condIndent + 1, out) { loopBackIndent ->
                val pad = "  ".repeat(loopBackIndent)
                out.appendLine("${pad}$loopVar[0].accept(null);")
            }
            out.appendLine("${pad}}")
            out.appendLine("${pad}else {")
            compileStatements(rest, condIndent + 1, out, onEmpty)
            out.appendLine("${pad}}")
        }

        out.appendLine("${pad}};")
        out.appendLine("${pad}$loopVar[0].accept(null);")
    }

    // -- Expression statements ------------------------------------------------

    private fun compileExpressionStatement(
        expr: ExpressionContext,
        rest: List<StatementContext>,
        indent: Int,
        out: StringBuilder,
        onEmpty: ((Int) -> Unit)?,
    ) {
        if (containsFunctionCall(expr)) {
            liftExpression(expr, indent, out) { _, innerIndent ->
                compileStatements(rest, innerIndent, out, onEmpty)
            }
        } else {
            val pad = "  ".repeat(indent)
            out.appendLine("${pad}${compileExpression(expr)};")
            compileStatements(rest, indent, out, onEmpty)
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
    // variable (and updated indent) into the [then] callback.

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
     * with a simple Java expression string (no remaining calls) and the indent
     * level at which [then] should emit code.
     */
    private fun liftExpression(
        expr: ExpressionContext,
        indent: Int,
        out: StringBuilder,
        then: (simpleExpr: String, indent: Int) -> Unit,
    ) {
        if (!containsFunctionCall(expr)) {
            then(compileExpression(expr), indent)
            return
        }

        when (expr) {
            is FunctionCallExprContext -> {
                val name = expr.IDENTIFIER().text
                val args = expr.argumentList().expression()
                liftExpressionList(args, indent, out) { liftedArgs, argsIndent ->
                    val resultParam = freshArg()
                    val pad = "  ".repeat(argsIndent)
                    if (name == "println") {
                        out.appendLine("${pad}Prelude.println(${liftedArgs[0]}, ($resultParam) -> {")
                    } else {
                        out.appendLine("${pad}$name(${liftedArgs.joinToString(", ")}, ($resultParam) -> {")
                    }
                    then(resultParam, argsIndent + 1)
                    out.appendLine("${pad}});")
                }
            }
            is AddSubExprContext -> liftBinaryOperation(expr.expression(0), expr.expression(1),
                if (expr.PLUS() != null) "+" else "-", indent, out, then)
            is MulDivExprContext -> liftBinaryOperation(expr.expression(0), expr.expression(1), when {
                expr.MULT() != null -> "*"
                expr.DIV() != null  -> "/"
                else                -> "%"
            }, indent, out, then)
            is ComparisonExprContext -> liftBinaryOperation(expr.expression(0), expr.expression(1), when {
                expr.LT() != null -> "<"
                expr.GT() != null -> ">"
                expr.LE() != null -> "<="
                else              -> ">="
            }, indent, out, then)
            is EqualityExprContext -> {
                liftExpression(expr.expression(0), indent, out) { left, leftIndent ->
                    liftExpression(expr.expression(1), leftIndent, out) { right, rightIndent ->
                        val result = if (expr.EQ() != null)
                            "java.util.Objects.equals($left, $right)"
                        else
                            "!java.util.Objects.equals($left, $right)"
                        then(result, rightIndent)
                    }
                }
            }
            is AndExprContext -> {
                val leftExpr = expr.expression(0)
                val rightExpr = expr.expression(1)
                if (containsFunctionCall(rightExpr)) {
                    liftExpression(leftExpr, indent, out) { leftVal, leftIndent ->
                        val pad = "  ".repeat(leftIndent)
                        out.appendLine("${pad}if ($leftVal) {")
                        liftExpression(rightExpr, leftIndent + 1, out) { rightVal, rightIndent ->
                            then(rightVal, rightIndent)
                        }
                        out.appendLine("${pad}} else {")
                        then("false", leftIndent + 1)
                        out.appendLine("${pad}}")
                    }
                } else {
                    liftBinaryOperation(leftExpr, rightExpr, "&&", indent, out, then)
                }
            }
            is OrExprContext -> {
                val leftExpr = expr.expression(0)
                val rightExpr = expr.expression(1)
                if (containsFunctionCall(rightExpr)) {
                    liftExpression(leftExpr, indent, out) { leftVal, leftIndent ->
                        val pad = "  ".repeat(leftIndent)
                        out.appendLine("${pad}if ($leftVal) {")
                        then("true", leftIndent + 1)
                        out.appendLine("${pad}} else {")
                        liftExpression(rightExpr, leftIndent + 1, out) { rightVal, rightIndent ->
                            then(rightVal, rightIndent)
                        }
                        out.appendLine("${pad}}")
                    }
                } else {
                    liftBinaryOperation(leftExpr, rightExpr, "||", indent, out, then)
                }
            }
            is NotExprContext  -> {
                liftExpression(expr.expression(), indent, out) { inner, innerIndent ->
                    then("(!$inner)", innerIndent)
                }
            }
            is PrimaryExprContext -> {
                val p = expr.primary()
                if (p is ParenExprContext) {
                    liftExpression(p.expression(), indent, out) { inner, innerIndent ->
                        then("($inner)", innerIndent)
                    }
                } else {
                    then(compilePrimary(p), indent)
                }
            }
            else -> then(compileExpression(expr), indent)
        }
    }

    private fun liftBinaryOperation(
        left: ExpressionContext,
        right: ExpressionContext,
        op: String,
        indent: Int,
        out: StringBuilder,
        then: (String, Int) -> Unit,
    ) {
        liftExpression(left, indent, out) { leftValue, leftIndent ->
            liftExpression(right, leftIndent, out) { rightValue, rightIndent ->
                then("($leftValue $op $rightValue)", rightIndent)
            }
        }
    }

    /** Lifts a list of expressions left-to-right, collecting simple results. */
    private fun liftExpressionList(
        exprs: List<ExpressionContext>,
        indent: Int,
        out: StringBuilder,
        then: (liftedExprs: List<String>, indent: Int) -> Unit,
    ) {
        fun go(index: Int, acc: List<String>, currentIndent: Int) {
            if (index >= exprs.size) {
                then(acc, currentIndent)
            } else {
                liftExpression(exprs[index], currentIndent, out) { lifted, newIndent ->
                    go(index + 1, acc + lifted, newIndent)
                }
            }
        }
        go(0, emptyList(), indent)
    }

    // -- Type mapping ---------------------------------------------------------

    private fun toJavaType(type: TypeContext): String = when {
        type.INT_TYPE() != null     -> "Integer"
        type.STRING_TYPE() != null  -> "String"
        type.BOOLEAN_TYPE() != null -> "Boolean"
        type.UNIT_TYPE() != null    -> "Void"
        else -> error("unknown type: ${type.text}")
    }

    // -- Expression compilation (simple, no function calls) -------------------

    private fun compileExpression(expr: ExpressionContext): String = when (expr) {
        is PrimaryExprContext -> compilePrimary(expr.primary())
        is AddSubExprContext -> compileBinaryOperation(expr.expression(0), expr.expression(1),
            if (expr.PLUS() != null) "+" else "-")
        is MulDivExprContext -> compileBinaryOperation(expr.expression(0), expr.expression(1), when {
            expr.MULT() != null -> "*"
            expr.DIV() != null  -> "/"
            else                -> "%"
        })
        is ComparisonExprContext -> compileBinaryOperation(expr.expression(0), expr.expression(1), when {
            expr.LT() != null -> "<"
            expr.GT() != null -> ">"
            expr.LE() != null -> "<="
            else              -> ">="
        })
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

    private fun compileBinaryOperation(left: ExpressionContext, right: ExpressionContext, op: String): String =
        "(${compileExpression(left)} $op ${compileExpression(right)})"

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
