/*
 * MiniKotlin to Java CPS Compiler
 *
 * Translates a MiniKotlin program (parsed by ANTLR) into Java source code
 * where every function call uses continuation-passing style. The generated
 * code depends on the stdlib's Continuation<T> interface and Prelude class.
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
        val body = buildString {
            appendLine("public class $className {")
            for (fn in program.functionDeclaration()) {
                appendLine()
                compileFunction(fn, indent = 1, this)
            }
            appendLine("}")
        }
        return body
    }

    // -- Function compilation -------------------------------------------------

    private fun compileFunction(fn: FunctionDeclarationContext, indent: Int, out: StringBuilder) {
        val name = fn.IDENTIFIER().text
        val pad = "  ".repeat(indent)

        if (name == "main") {
            out.appendLine("${pad}public static void main(String[] args) {")
            compileStatements(fn.block().statement(), indent + 1, out)
            out.appendLine("${pad}}")
        } else {
            TODO("non-main functions")
        }
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
        val pad = "  ".repeat(indent)

        val varDecl = stmt.variableDeclaration()
        if (varDecl != null) {
            val type = mapType(varDecl.type())
            val name = varDecl.IDENTIFIER().text
            val value = compileExpression(varDecl.expression())
            out.appendLine("${pad}$type $name = $value;")
            compileStatements(rest, indent, out)
            return
        }

        val expr = stmt.expression()
        if (expr != null && expr is FunctionCallExprContext) {
            compileFunctionCallStatement(expr, rest, indent, out)
            return
        }

        TODO("statement type: ${stmt.text}")
    }

    // -- Function call statements ---------------------------------------------

    private fun compileFunctionCallStatement(
        call: FunctionCallExprContext,
        rest: List<StatementContext>,
        indent: Int,
        out: StringBuilder,
    ) {
        val name = call.IDENTIFIER().text
        val args = call.argumentList().expression()
        val pad = "  ".repeat(indent)

        if (name == "println") {
            val arg = compileExpression(args[0])
            val contParam = freshArg()
            out.appendLine("${pad}Prelude.println($arg, ($contParam) -> {")
            compileStatements(rest, indent + 1, out)
            out.appendLine("${pad}});")
        } else {
            TODO("call to user function: $name")
        }
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
        is AddSubExprContext -> {
            val op = if (expr.PLUS() != null) "+" else "-"
            "(${compileExpression(expr.expression(0))} $op ${compileExpression(expr.expression(1))})"
        }
        is MulDivExprContext -> {
            val op = when {
                expr.MULT() != null -> "*"
                expr.DIV() != null  -> "/"
                else                -> "%"
            }
            "(${compileExpression(expr.expression(0))} $op ${compileExpression(expr.expression(1))})"
        }
        is ComparisonExprContext -> {
            val op = when {
                expr.LT() != null -> "<"
                expr.GT() != null -> ">"
                expr.LE() != null -> "<="
                else              -> ">="
            }
            "(${compileExpression(expr.expression(0))} $op ${compileExpression(expr.expression(1))})"
        }
        is EqualityExprContext -> {
            val left = compileExpression(expr.expression(0))
            val right = compileExpression(expr.expression(1))
            if (expr.EQ() != null)
                "java.util.Objects.equals($left, $right)"
            else
                "!java.util.Objects.equals($left, $right)"
        }
        is AndExprContext ->
            "(${compileExpression(expr.expression(0))} && ${compileExpression(expr.expression(1))})"
        is OrExprContext ->
            "(${compileExpression(expr.expression(0))} || ${compileExpression(expr.expression(1))})"
        is NotExprContext ->
            "(!${compileExpression(expr.expression())})"
        else -> TODO("expression type: ${expr::class.simpleName}")
    }

    private fun compilePrimary(primary: PrimaryContext): String = when (primary) {
        is IntLiteralContext -> primary.INTEGER_LITERAL().text
        is StringLiteralContext -> primary.STRING_LITERAL().text
        is BoolLiteralContext -> primary.BOOLEAN_LITERAL().text
        is IdentifierExprContext -> primary.IDENTIFIER().text
        is ParenExprContext -> "(${compileExpression(primary.expression())})"
        else -> TODO("primary type: ${primary::class.simpleName}")
    }
}
