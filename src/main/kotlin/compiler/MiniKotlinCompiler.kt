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

    private fun freshArg(): String = "arg${argCounter++}"

    // -- Entry point ----------------------------------------------------------

    fun compile(program: ProgramContext, className: String = "MiniProgram"): String {
        argCounter = 0
        return buildString {
            appendLine("public class $className {")
            for (fn in program.functionDeclaration()) {
                appendLine()
                compileFunction(fn, indent = 1, this)
            }
            appendLine("}")
        }
    }

    // -- Function compilation -------------------------------------------------

    private fun compileFunction(fn: FunctionDeclarationContext, indent: Int, out: StringBuilder) {
        val name = fn.IDENTIFIER().text
        val pad = "  ".repeat(indent)

        if (name == "main") {
            out.appendLine("${pad}public static void main(String[] args) {")
        } else {
            val returnType = mapType(fn.type())
            val params = buildList {
                fn.parameterList()?.parameter()?.forEach { p ->
                    add("${mapType(p.type())} ${p.IDENTIFIER().text}")
                }
                add("Continuation<$returnType> __continuation")
            }
            out.appendLine("${pad}public static void $name(${params.joinToString(", ")}) {")
        }

        compileStatements(fn.block().statement(), indent + 1, out)
        out.appendLine("${pad}}")
    }

    // -- Statement compilation ------------------------------------------------

    /**
     * Compiles a list of statements in CPS style. Each statement that involves
     * a function call nests the remaining statements inside its continuation.
     */
    private fun compileStatements(
        stmts: List<StatementContext>,
        indent: Int,
        out: StringBuilder,
    ) {
        if (stmts.isEmpty()) return

        val stmt = stmts.first()
        val rest = stmts.subList(1, stmts.size)

        when {
            stmt.variableDeclaration() != null ->
                compileVarDecl(stmt.variableDeclaration(), rest, indent, out)
            stmt.returnStatement() != null ->
                compileReturn(stmt.returnStatement(), indent, out)
            stmt.ifStatement() != null ->
                compileIf(stmt.ifStatement(), rest, indent, out)
            stmt.expression() != null ->
                compileExpressionStmt(stmt.expression(), rest, indent, out)
            else ->
                TODO("statement type: ${stmt.text}")
        }
    }

    // -- Variable declarations ------------------------------------------------

    private fun compileVarDecl(
        decl: VariableDeclarationContext,
        rest: List<StatementContext>,
        indent: Int,
        out: StringBuilder,
    ) {
        val type = mapType(decl.type())
        val name = decl.IDENTIFIER().text
        val rhs = decl.expression()

        liftExpression(rhs, indent, out) { value, innerIndent ->
            val pad = "  ".repeat(innerIndent)
            out.appendLine("${pad}$type $name = $value;")
            compileStatements(rest, innerIndent, out)
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
    ) {
        val pad = "  ".repeat(indent)
        val cond = compileExpression(ifStmt.expression())
        val blocks = ifStmt.block()

        out.appendLine("${pad}if ($cond) {")
        compileStatements(blocks[0].statement() + rest, indent + 1, out)
        out.appendLine("${pad}}")

        val elseBody = if (blocks.size > 1) blocks[1].statement() + rest else rest
        out.appendLine("${pad}else {")
        compileStatements(elseBody, indent + 1, out)
        out.appendLine("${pad}}")
    }

    // -- Expression statements ------------------------------------------------

    private fun compileExpressionStmt(
        expr: ExpressionContext,
        rest: List<StatementContext>,
        indent: Int,
        out: StringBuilder,
    ) {
        if (containsFunctionCall(expr)) {
            liftExpression(expr, indent, out) { _, innerIndent ->
                compileStatements(rest, innerIndent, out)
            }
        } else {
            val pad = "  ".repeat(indent)
            out.appendLine("${pad}${compileExpression(expr)};")
            compileStatements(rest, indent, out)
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
        is AddSubExprContext ->
            containsFunctionCall(expr.expression(0)) || containsFunctionCall(expr.expression(1))
        is MulDivExprContext ->
            containsFunctionCall(expr.expression(0)) || containsFunctionCall(expr.expression(1))
        is ComparisonExprContext ->
            containsFunctionCall(expr.expression(0)) || containsFunctionCall(expr.expression(1))
        is EqualityExprContext ->
            containsFunctionCall(expr.expression(0)) || containsFunctionCall(expr.expression(1))
        is AndExprContext ->
            containsFunctionCall(expr.expression(0)) || containsFunctionCall(expr.expression(1))
        is OrExprContext ->
            containsFunctionCall(expr.expression(0)) || containsFunctionCall(expr.expression(1))
        is NotExprContext ->
            containsFunctionCall(expr.expression())
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
                liftExprList(args, indent, out) { liftedArgs, argsIndent ->
                    val contParam = freshArg()
                    val pad = "  ".repeat(argsIndent)
                    if (name == "println") {
                        out.appendLine("${pad}Prelude.println(${liftedArgs[0]}, ($contParam) -> {")
                    } else {
                        out.appendLine("${pad}$name(${liftedArgs.joinToString(", ")}, ($contParam) -> {")
                    }
                    then(contParam, argsIndent + 1)
                    out.appendLine("${pad}});")
                }
            }
            is AddSubExprContext -> liftBinaryOp(expr.expression(0), expr.expression(1),
                if (expr.PLUS() != null) "+" else "-", indent, out, then)
            is MulDivExprContext -> liftBinaryOp(expr.expression(0), expr.expression(1), when {
                expr.MULT() != null -> "*"
                expr.DIV() != null  -> "/"
                else                -> "%"
            }, indent, out, then)
            is ComparisonExprContext -> liftBinaryOp(expr.expression(0), expr.expression(1), when {
                expr.LT() != null -> "<"
                expr.GT() != null -> ">"
                expr.LE() != null -> "<="
                else              -> ">="
            }, indent, out, then)
            is EqualityExprContext -> {
                liftExpression(expr.expression(0), indent, out) { left, li ->
                    liftExpression(expr.expression(1), li, out) { right, ri ->
                        val result = if (expr.EQ() != null)
                            "java.util.Objects.equals($left, $right)"
                        else
                            "!java.util.Objects.equals($left, $right)"
                        then(result, ri)
                    }
                }
            }
            is AndExprContext  -> liftBinaryOp(expr.expression(0), expr.expression(1), "&&", indent, out, then)
            is OrExprContext   -> liftBinaryOp(expr.expression(0), expr.expression(1), "||", indent, out, then)
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

    private fun liftBinaryOp(
        left: ExpressionContext,
        right: ExpressionContext,
        op: String,
        indent: Int,
        out: StringBuilder,
        then: (String, Int) -> Unit,
    ) {
        liftExpression(left, indent, out) { l, li ->
            liftExpression(right, li, out) { r, ri ->
                then("($l $op $r)", ri)
            }
        }
    }

    /** Lifts a list of expressions left-to-right, collecting simple results. */
    private fun liftExprList(
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

    private fun mapType(type: TypeContext): String = when {
        type.INT_TYPE() != null     -> "Integer"
        type.STRING_TYPE() != null  -> "String"
        type.BOOLEAN_TYPE() != null -> "Boolean"
        type.UNIT_TYPE() != null    -> "Void"
        else -> error("unknown type: ${type.text}")
    }

    // -- Expression compilation (simple, no function calls) -------------------

    private fun compileExpression(expr: ExpressionContext): String = when (expr) {
        is PrimaryExprContext -> compilePrimary(expr.primary())
        is AddSubExprContext -> compileBinaryOp(expr.expression(0), expr.expression(1),
            if (expr.PLUS() != null) "+" else "-")
        is MulDivExprContext -> compileBinaryOp(expr.expression(0), expr.expression(1), when {
            expr.MULT() != null -> "*"
            expr.DIV() != null  -> "/"
            else                -> "%"
        })
        is ComparisonExprContext -> compileBinaryOp(expr.expression(0), expr.expression(1), when {
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
        is AndExprContext  -> compileBinaryOp(expr.expression(0), expr.expression(1), "&&")
        is OrExprContext   -> compileBinaryOp(expr.expression(0), expr.expression(1), "||")
        is NotExprContext  -> "(!${compileExpression(expr.expression())})"
        else -> TODO("expression type: ${expr::class.simpleName}")
    }

    private fun compileBinaryOp(left: ExpressionContext, right: ExpressionContext, op: String): String =
        "(${compileExpression(left)} $op ${compileExpression(right)})"

    private fun compilePrimary(primary: PrimaryContext): String = when (primary) {
        is IntLiteralContext -> primary.INTEGER_LITERAL().text
        is StringLiteralContext -> primary.STRING_LITERAL().text
        is BoolLiteralContext -> primary.BOOLEAN_LITERAL().text
        is IdentifierExprContext -> primary.IDENTIFIER().text
        is ParenExprContext -> "(${compileExpression(primary.expression())})"
        else -> TODO("primary type: ${primary::class.simpleName}")
    }
}
