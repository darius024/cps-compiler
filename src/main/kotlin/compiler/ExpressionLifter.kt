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
// it delegates to compileExpression. For calls it emits CPS code and passes
// the temp variable into the [then] callback.

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
 * Depends on [compileExpression] and [compilePrimary] for call-free sub-trees,
 * and [names] for generating fresh continuation parameter names.
 */
internal class ExpressionLifter(
    private val compileExpression: (ExpressionContext) -> String,
    private val compilePrimary: (PrimaryContext) -> String,
    private val names: NameSupply,
) {

    /** Recursively checks whether an expression tree contains any function call. */
    fun containsFunctionCall(expr: ExpressionContext): Boolean = when (expr) {
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
    fun liftExpression(
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
                    val resultParam = names.freshArg()
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
}
