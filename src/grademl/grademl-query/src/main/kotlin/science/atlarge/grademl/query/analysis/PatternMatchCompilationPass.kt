package science.atlarge.grademl.query.analysis

import science.atlarge.grademl.query.execution.BooleanPhysicalExpression
import science.atlarge.grademl.query.execution.ExpressionImplementation
import science.atlarge.grademl.query.execution.PhysicalExpression
import science.atlarge.grademl.query.execution.StringPhysicalExpression
import science.atlarge.grademl.query.language.*
import science.atlarge.grademl.query.model.Row

object PatternMatchCompilationPass {

    fun rewrite(e: Expression): Expression = object : ExpressionRewritePass() {
        override fun rewrite(e: BinaryExpression): Expression {
            if (e.op != BinaryOp.APPROX_EQUAL && e.op != BinaryOp.NOT_APPROX_EQUAL) return super.rewrite(e)
            if (e.rhs !is StringLiteral) return super.rewrite(e)

            val pattern = e.rhs.value
            val regex = pattern.split("**").joinToString(separator = ".*") { component ->
                component.split('*').joinToString(separator = "[^/]*") {
                    Regex.escape(it)
                }
            }.toRegex()
            return PatternMatchExpression(
                regex,
                invertMatch = e.op == BinaryOp.NOT_APPROX_EQUAL,
                lhs = e.lhs,
                originalExpression = e
            ).apply { type = Type.BOOLEAN }
        }
    }.rewriteExpression(e)

    private class PatternMatchExpression(
        val regex: Regex,
        val invertMatch: Boolean,
        lhs: Expression,
        originalExpression: Expression
    ) : ExpressionImplementation(listOf(lhs), originalExpression) {

        override fun toPhysicalExpression(arguments: List<PhysicalExpression>) = object : BooleanPhysicalExpression {
            private val lhsExpr = arguments[0] as StringPhysicalExpression
            override fun evaluateAsBoolean(row: Row): Boolean {
                val lhs = lhsExpr.evaluateAsString(row)
                val matches = regex.matches(lhs)
                return if (invertMatch) !matches else matches
            }
        }

        override val isDeterministic: Boolean
            get() = true

        override fun isEquivalent(other: Expression): Boolean {
            if (other !is PatternMatchExpression) return false
            return arguments[0].isEquivalent(other.arguments[0]) &&
                    originalExpression.isEquivalent(other.originalExpression)
        }

        override fun clone() = PatternMatchExpression(regex, invertMatch, arguments[0], originalExpression)

    }

}