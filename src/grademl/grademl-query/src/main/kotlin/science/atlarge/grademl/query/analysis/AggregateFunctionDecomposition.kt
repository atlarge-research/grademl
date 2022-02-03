package science.atlarge.grademl.query.analysis

import science.atlarge.grademl.query.analysis.ASTUtils.traverse
import science.atlarge.grademl.query.language.*
import science.atlarge.grademl.query.model.Column

object AggregateFunctionDecomposition {

    fun decompose(expressions: List<Expression>, inputColumns: List<Column>): Result {
        val visitor = Visitor(inputColumns)
        val rewrittenExpressions = expressions.map { e ->
            e.accept(visitor)
            visitor.rewrittenExpression
        }
        return Result(
            rewrittenExpressions,
            visitor.aggregateFunctions,
            visitor.aggregateFunctionTypes,
            visitor.aggregateFunctionArguments,
            visitor.aggregateColumns
        )
    }

    private class Visitor(private val inputColumns: List<Column>) : ExpressionVisitor {

        val aggregateFunctions = mutableListOf<FunctionDefinition>()
        val aggregateFunctionTypes = mutableListOf<Type>()
        val aggregateFunctionArguments = mutableListOf<List<Expression>>()
        val aggregateColumns = mutableListOf<Column>()

        lateinit var rewrittenExpression: Expression
            private set

        private fun Expression.rewrite(): Expression {
            accept(this@Visitor)
            return rewrittenExpression
        }

        override fun visit(e: BooleanLiteral) {
            rewrittenExpression = e
        }

        override fun visit(e: NumericLiteral) {
            rewrittenExpression = e
        }

        override fun visit(e: StringLiteral) {
            rewrittenExpression = e
        }

        override fun visit(e: ColumnLiteral) {
            rewrittenExpression = e
        }

        override fun visit(e: UnaryExpression) {
            val inner = e.expr.rewrite()
            rewrittenExpression = if (inner === e.expr) e else e.copy(newExpr = inner)
        }

        override fun visit(e: BinaryExpression) {
            val l = e.lhs.rewrite()
            val r = e.rhs.rewrite()
            rewrittenExpression = if (l === e.lhs && r === e.rhs) e else e.copy(newLhs = l, newRhs = r)
        }

        override fun visit(e: FunctionCallExpression) {
            rewrittenExpression = if (e.functionDefinition.isAggregatingFunction) {
                // Rewrite aggregating functions
                // First, check for unsupported nested aggregations
                val args = e.arguments
                for (arg in args) {
                    val hasNestedAggregateFunction = arg.traverse().filterIsInstance<FunctionCallExpression>()
                        .any { it.functionDefinition.isAggregatingFunction }
                    require(!hasNestedAggregateFunction) {
                        "Nested aggregate functions are not supported"
                    }
                }

                val newColumnIndex = inputColumns.size + aggregateFunctions.size
                aggregateFunctions.add(e.functionDefinition)
                aggregateFunctionTypes.add(e.type)
                aggregateFunctionArguments.add(args)
                val column = Column("__AGG_${e.functionName}_$newColumnIndex", e.type, false)
                aggregateColumns.add(column)
                ColumnLiteral(column.identifier).apply {
                    type = e.type
                    columnIndex = newColumnIndex
                }
            } else {
                val args = e.arguments.map { it.rewrite() }
                if (args.indices.all { i -> args[i] === e.arguments[i] }) e else e.copy(newArguments = args)
            }
        }

        override fun visit(e: AbstractExpression) {
            // Rewrite the original expression
            val rewrittenOriginalExpression = e.originalExpression.rewrite()
            // If the original expression changed, optimize it and substitute it for the AbstractExpression
            rewrittenExpression = if (rewrittenOriginalExpression !== e.originalExpression) {
                ASTAnalysis.analyzeExpression(rewrittenOriginalExpression, inputColumns + aggregateColumns)
            } else {
                e
            }
        }

    }

    class Result(
        val rewrittenExpressions: List<Expression>,
        val aggregateFunctions: List<FunctionDefinition>,
        val aggregateFunctionTypes: List<Type>,
        val aggregateFunctionArguments: List<List<Expression>>,
        val aggregateColumns: List<Column>
    )

}