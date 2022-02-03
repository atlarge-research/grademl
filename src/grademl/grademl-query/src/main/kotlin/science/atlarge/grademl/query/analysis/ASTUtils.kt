package science.atlarge.grademl.query.analysis

import science.atlarge.grademl.query.language.*

object ASTUtils {

    fun findColumnLiterals(e: Expression): List<ColumnLiteral> {
        val columnLiterals = mutableListOf<ColumnLiteral>()
        val visitor = object : ExpressionVisitor {
            override fun visit(e: BooleanLiteral) {}
            override fun visit(e: NumericLiteral) {}
            override fun visit(e: StringLiteral) {}

            override fun visit(e: ColumnLiteral) {
                columnLiterals.add(e)
            }

            override fun visit(e: UnaryExpression) {
                e.expr.accept(this)
            }

            override fun visit(e: BinaryExpression) {
                e.lhs.accept(this)
                e.rhs.accept(this)
            }

            override fun visit(e: FunctionCallExpression) {
                e.arguments.forEach { it.accept(this) }
            }

            override fun visit(e: AbstractExpression) {
                e.arguments.forEach { it.accept(this) }
            }
        }
        e.accept(visitor)
        return columnLiterals
    }

    fun findFunctionCalls(e: Expression): List<FunctionCallExpression> {
        val functionCalls = mutableListOf<FunctionCallExpression>()
        val visitor = object : ExpressionVisitor {
            override fun visit(e: BooleanLiteral) {}
            override fun visit(e: NumericLiteral) {}
            override fun visit(e: StringLiteral) {}
            override fun visit(e: ColumnLiteral) {}

            override fun visit(e: UnaryExpression) {
                e.expr.accept(this)
            }

            override fun visit(e: BinaryExpression) {
                e.lhs.accept(this)
                e.rhs.accept(this)
            }

            override fun visit(e: FunctionCallExpression) {
                functionCalls.add(e)
                e.arguments.forEach { it.accept(this) }
            }

            override fun visit(e: AbstractExpression) {
                e.arguments.forEach { it.accept(this) }
            }
        }
        e.accept(visitor)
        return functionCalls
    }

    fun Expression.traverse(): List<Expression> {
        val allExpressions = mutableListOf<Expression>()
        val visitor = object : ExpressionVisitor {
            override fun visit(e: BooleanLiteral) {
                allExpressions.add(e)
            }

            override fun visit(e: NumericLiteral) {
                allExpressions.add(e)
            }

            override fun visit(e: StringLiteral) {
                allExpressions.add(e)
            }

            override fun visit(e: ColumnLiteral) {
                allExpressions.add(e)
            }

            override fun visit(e: UnaryExpression) {
                allExpressions.add(e)
                e.expr.accept(this)
            }

            override fun visit(e: BinaryExpression) {
                allExpressions.add(e)
                e.lhs.accept(this)
                e.rhs.accept(this)
            }

            override fun visit(e: FunctionCallExpression) {
                allExpressions.add(e)
                e.arguments.forEach { it.accept(this) }
            }

            override fun visit(e: AbstractExpression) {
                allExpressions.add(e)
                e.arguments.forEach { it.accept(this) }
            }
        }
        accept(visitor)
        return allExpressions
    }

}