package org.example.compiler

import MiniKotlinParser.*

/**
 * Compiles expressions that are guaranteed to contain no function calls
 * into plain Java expression strings. Throws if a function call is
 * encountered — use [ExpressionLifter] for expressions that may contain calls.
 */
internal class SimpleExpressionCompiler(
    private val isReassigned: (String) -> Boolean,
) {

    /** Compiles an [ExpressionContext] that contains no function calls into a Java expression string. */
    fun compileExpression(expr: ExpressionContext): String = when (expr) {
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

    private fun compileBinaryOperation(
        left: ExpressionContext,
        right: ExpressionContext,
        operatorSymbol: String,
    ): String =
        "(${compileExpression(left)} $operatorSymbol ${compileExpression(right)})"

    /** Compiles a [PrimaryContext] (literal, identifier, or parenthesised expression) into a Java expression string. */
    fun compilePrimary(primary: PrimaryContext): String = when (primary) {
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
