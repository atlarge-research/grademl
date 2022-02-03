package science.atlarge.grademl.query.analysis

import science.atlarge.grademl.query.language.*

abstract class ExpressionRewritePass(
    private val rewriteOriginalOfCustomExpression: Boolean = false
) : ExpressionVisitor {

    private lateinit var lastRewritten: Expression

    fun rewriteExpression(e: Expression): Expression {
        e.accept(this)
        return lastRewritten
    }

    protected fun Expression.rewrite() = rewriteExpression(this)

    protected open fun rewrite(e: BooleanLiteral): Expression {
        return e
    }

    protected open fun rewrite(e: NumericLiteral): Expression {
        return e
    }

    protected open fun rewrite(e: StringLiteral): Expression {
        return e
    }

    protected open fun rewrite(e: ColumnLiteral): Expression {
        return e
    }

    protected open fun rewrite(e: UnaryExpression): Expression {
        val rewritten = e.expr.rewrite()
        return if (rewritten === e.expr) e else e.copy(newExpr = rewritten)
    }

    protected open fun rewrite(e: BinaryExpression): Expression {
        val lRewritten = e.lhs.rewrite()
        val rRewritten = e.rhs.rewrite()
        return if (lRewritten === e.lhs && rRewritten === e.rhs) e
        else e.copy(newLhs = lRewritten, newRhs = rRewritten)
    }

    protected open fun rewrite(e: FunctionCallExpression): Expression {
        val rewrittenArgs = e.arguments.map { it.rewrite() }
        return if (e.arguments.indices.all { i -> rewrittenArgs[i] === e.arguments[i] }) e
        else e.copy(newArguments = rewrittenArgs)
    }

    protected open fun rewrite(e: AbstractExpression): Expression {
        val rewrittenOriginalExpression =
            if (rewriteOriginalOfCustomExpression) e.originalExpression.rewrite()
            else e.originalExpression
        return if (rewrittenOriginalExpression !== e.originalExpression) rewrittenOriginalExpression else e
    }

    override fun visit(e: BooleanLiteral) {
        lastRewritten = rewrite(e)
    }

    override fun visit(e: NumericLiteral) {
        lastRewritten = rewrite(e)
    }

    override fun visit(e: StringLiteral) {
        lastRewritten = rewrite(e)
    }

    override fun visit(e: ColumnLiteral) {
        lastRewritten = rewrite(e)
    }

    override fun visit(e: UnaryExpression) {
        lastRewritten = rewrite(e)
    }

    override fun visit(e: BinaryExpression) {
        lastRewritten = rewrite(e)
    }

    override fun visit(e: FunctionCallExpression) {
        lastRewritten = rewrite(e)
    }

    override fun visit(e: AbstractExpression) {
        lastRewritten = rewrite(e)
    }

}