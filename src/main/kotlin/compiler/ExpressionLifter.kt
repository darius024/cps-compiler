package org.example.compiler

import MiniKotlinParser.*

// -- Expression lifting ---------------------------------------------------
//
// When an expression contains function calls, we cannot compile it as a
// single Java expression. Instead we "lift" calls out: each call becomes
// a CPS invocation whose continuation receives the result in a fresh
// variable, and the rest of the expression is rebuilt using those variables.
//
// ExpressionLifter walks the expression tree. For sub-trees without calls
// it delegates to SimpleExpressionCompiler. For calls it emits CPS code
// and passes the temp variable into the [then] callback.

/** Maps an arithmetic or comparison expression node to its Java operator string. */
internal fun operatorOf(expr: ExpressionContext): String = when (expr) {
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

/**
 * Lifts function calls out of expressions into CPS-style invocations.
 *
 * Depends on [expressions] for compiling call-free sub-trees, and
 * [names] for generating fresh continuation parameter names.
 */
internal class ExpressionLifter(
    private val expressions: SimpleExpressionCompiler,
    private val names: NameSupply,
) {

    /** Recursively checks whether an expression tree contains any function call. */
    fun containsFunctionCall(expr: ExpressionContext): Boolean = when (expr) {
        is FunctionCallExprContext -> true
        is PrimaryExprContext -> {
            val primary = expr.primary()
            primary is ParenExprContext && containsFunctionCall(primary.expression())
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
    fun liftExpression(
        expr: ExpressionContext,
        writer: CodeWriter,
        then: (simpleExpr: String) -> Unit,
    ) {
        if (!containsFunctionCall(expr)) {
            then(expressions.compileExpression(expr))
            return
        }

        when (expr) {
            is FunctionCallExprContext -> {
                val functionName = expr.IDENTIFIER().text
                val arguments = expr.argumentList().expression()
                liftExpressionList(arguments, writer) { liftedArgs ->
                    val resultParam = names.freshArg()
                    if (functionName == "println") {
                        writer.line("Prelude.println(${liftedArgs[0]}, ($resultParam) -> {")
                    } else {
                        writer.line("$functionName(${liftedArgs.joinToString(", ")}, ($resultParam) -> {")
                    }
                    writer.indented { then(resultParam) }
                    writer.line("});")
                }
            }
            is AddSubExprContext ->
                liftBinaryOperation(expr.expression(0), expr.expression(1), operatorOf(expr), writer, then)
            is MulDivExprContext ->
                liftBinaryOperation(expr.expression(0), expr.expression(1), operatorOf(expr), writer, then)
            is ComparisonExprContext ->
                liftBinaryOperation(expr.expression(0), expr.expression(1), operatorOf(expr), writer, then)
            is EqualityExprContext -> {
                liftExpression(expr.expression(0), writer) { left ->
                    liftExpression(expr.expression(1), writer) { right ->
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
                    liftExpression(leftExpr, writer) { leftVal ->
                        writer.block("if ($leftVal)") { liftExpression(rightExpr, writer, then) }
                        writer.block("else") { then("false") }
                    }
                } else {
                    liftBinaryOperation(leftExpr, rightExpr, "&&", writer, then)
                }
            }
            is OrExprContext -> {
                val leftExpr = expr.expression(0)
                val rightExpr = expr.expression(1)
                if (containsFunctionCall(rightExpr)) {
                    liftExpression(leftExpr, writer) { leftVal ->
                        writer.block("if ($leftVal)") { then("true") }
                        writer.block("else") { liftExpression(rightExpr, writer, then) }
                    }
                } else {
                    liftBinaryOperation(leftExpr, rightExpr, "||", writer, then)
                }
            }
            is NotExprContext -> {
                liftExpression(expr.expression(), writer) { inner -> then("(!$inner)") }
            }
            is PrimaryExprContext -> {
                val primary = expr.primary()
                if (primary is ParenExprContext) {
                    liftExpression(primary.expression(), writer) { inner -> then("($inner)") }
                } else {
                    then(expressions.compilePrimary(primary))
                }
            }
            else -> then(expressions.compileExpression(expr))
        }
    }

    /** Lifts both operands of a binary operator left-to-right, then combines them. */
    private fun liftBinaryOperation(
        left: ExpressionContext,
        right: ExpressionContext,
        operatorSymbol: String,
        writer: CodeWriter,
        then: (String) -> Unit,
    ) {
        liftExpression(left, writer) { leftValue ->
            liftExpression(right, writer) { rightValue ->
                then("($leftValue $operatorSymbol $rightValue)")
            }
        }
    }

    /** Lifts a list of expressions left-to-right, collecting simple results. */
    private fun liftExpressionList(
        exprs: List<ExpressionContext>,
        writer: CodeWriter,
        then: (liftedExprs: List<String>) -> Unit,
    ) {
        fun liftAt(index: Int, results: List<String>) {
            if (index >= exprs.size) {
                then(results)
            } else {
                liftExpression(exprs[index], writer) { lifted ->
                    liftAt(index + 1, results + lifted)
                }
            }
        }
        liftAt(0, emptyList())
    }
}
