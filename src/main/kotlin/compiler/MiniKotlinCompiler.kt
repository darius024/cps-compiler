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
            stmt.expression() is FunctionCallExprContext ->
                emitCpsCall(stmt.expression() as FunctionCallExprContext, rest, indent, out)
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
        val pad = "  ".repeat(indent)
        val rhs = decl.expression()

        if (rhs is FunctionCallExprContext) {
            val contParam = freshArg()
            emitCpsCallOpen(rhs, contParam, indent, out)
            out.appendLine("${pad}  $type $name = $contParam;")
            compileStatements(rest, indent + 1, out)
            out.appendLine("${pad}});")
        } else {
            out.appendLine("${pad}$type $name = ${compileExpression(rhs)};")
            compileStatements(rest, indent, out)
        }
    }

    // -- Return ---------------------------------------------------------------

    private fun compileReturn(ret: ReturnStatementContext, indent: Int, out: StringBuilder) {
        val pad = "  ".repeat(indent)
        val expr = ret.expression()
        val value = if (expr != null) compileExpression(expr) else "null"
        out.appendLine("${pad}__continuation.accept($value);")
        out.appendLine("${pad}return;")
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

    // -- CPS function calls ---------------------------------------------------

    /**
     * Emits the opening line of a CPS call: `name(args, (contParam) -> {`
     * Handles println specially (routed through Prelude).
     */
    private fun emitCpsCallOpen(
        call: FunctionCallExprContext,
        contParam: String,
        indent: Int,
        out: StringBuilder,
    ) {
        val name = call.IDENTIFIER().text
        val args = call.argumentList().expression()
        val pad = "  ".repeat(indent)

        if (name == "println") {
            out.appendLine("${pad}Prelude.println(${compileExpression(args[0])}, ($contParam) -> {")
        } else {
            val compiledArgs = args.joinToString(", ") { compileExpression(it) }
            out.appendLine("${pad}$name($compiledArgs, ($contParam) -> {")
        }
    }

    /** Emits a complete CPS call as a statement, nesting the rest inside. */
    private fun emitCpsCall(
        call: FunctionCallExprContext,
        rest: List<StatementContext>,
        indent: Int,
        out: StringBuilder,
    ) {
        val pad = "  ".repeat(indent)
        val contParam = freshArg()
        emitCpsCallOpen(call, contParam, indent, out)
        compileStatements(rest, indent + 1, out)
        out.appendLine("${pad}});")
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
