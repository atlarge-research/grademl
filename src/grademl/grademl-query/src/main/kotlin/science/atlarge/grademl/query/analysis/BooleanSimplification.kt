package science.atlarge.grademl.query.analysis

import science.atlarge.grademl.query.language.*

object BooleanSimplification {

    fun simplify(e: Expression): Expression = object : ExpressionRewritePass() {
        override fun rewrite(e: UnaryExpression): Expression {
            return when (e.op) {
                UnaryOp.NOT -> {
                    val invertedInner = tryInvert(e.expr)
                    invertedInner?.rewrite() ?: super.rewrite(e)
                }
            }
        }

        private fun tryInvert(e: Expression): Expression? {
            return when (e) {
                is BooleanLiteral -> BooleanLiteral(!e.value).apply { type = Type.BOOLEAN }
                is UnaryExpression -> when (e.op) {
                    UnaryOp.NOT -> e.expr
                }
                is BinaryExpression -> when (e.op) {
                    BinaryOp.AND -> {
                        BinaryExpression(
                            UnaryExpression(e.lhs, UnaryOp.NOT).apply { type = Type.BOOLEAN },
                            UnaryExpression(e.rhs, UnaryOp.NOT).apply { type = Type.BOOLEAN },
                            BinaryOp.OR
                        )
                    }
                    BinaryOp.OR -> {
                        BinaryExpression(
                            UnaryExpression(e.lhs, UnaryOp.NOT).apply { type = Type.BOOLEAN },
                            UnaryExpression(e.rhs, UnaryOp.NOT).apply { type = Type.BOOLEAN },
                            BinaryOp.AND
                        )
                    }
                    BinaryOp.EQUAL -> BinaryExpression(e.lhs, e.rhs, BinaryOp.NOT_EQUAL)
                    BinaryOp.NOT_EQUAL -> BinaryExpression(e.lhs, e.rhs, BinaryOp.EQUAL)
                    BinaryOp.APPROX_EQUAL -> BinaryExpression(e.lhs, e.rhs, BinaryOp.NOT_APPROX_EQUAL)
                    BinaryOp.NOT_APPROX_EQUAL -> BinaryExpression(e.lhs, e.rhs, BinaryOp.APPROX_EQUAL)
                    BinaryOp.GREATER -> BinaryExpression(e.lhs, e.rhs, BinaryOp.SMALLER_EQUAL)
                    BinaryOp.GREATER_EQUAL -> BinaryExpression(e.lhs, e.rhs, BinaryOp.SMALLER)
                    BinaryOp.SMALLER -> BinaryExpression(e.lhs, e.rhs, BinaryOp.GREATER_EQUAL)
                    BinaryOp.SMALLER_EQUAL -> BinaryExpression(e.lhs, e.rhs, BinaryOp.GREATER)
                    else -> null
                }
                else -> null
            }
        }
    }.rewriteExpression(e)

}