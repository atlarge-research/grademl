package science.atlarge.grademl.query.analysis

import science.atlarge.grademl.query.language.*
import science.atlarge.grademl.query.language.Type.*
import science.atlarge.grademl.query.model.Column

object TypeChecking {

    fun analyzeTypes(expression: Expression, columns: List<Column>) {
        expression.accept(Visitor(columns))
    }

    private class Visitor(private val columns: List<Column>) : ExpressionVisitor {

        private fun Expression.checkType(): Type {
            accept(this@Visitor)
            return type
        }

        override fun visit(e: BooleanLiteral) {
            e.type = BOOLEAN
        }

        override fun visit(e: NumericLiteral) {
            e.type = NUMERIC
        }

        override fun visit(e: StringLiteral) {
            e.type = STRING
        }

        override fun visit(e: ColumnLiteral) {
            require(e.columnIndex in columns.indices) { "Column index not defined" }
            e.type = columns[e.columnIndex].type
        }

        override fun visit(e: UnaryExpression) {
            val eType = e.expr.checkType()
            e.type = when (e.op) {
                UnaryOp.NOT -> {
                    require(eType == BOOLEAN) { "NOT expression requires a BOOLEAN input" }
                    eType
                }
            }
        }

        override fun visit(e: BinaryExpression) {
            val lType = e.lhs.checkType()
            val rType = e.rhs.checkType()
            e.type = when (e.op) {
                BinaryOp.ADD, BinaryOp.SUBTRACT, BinaryOp.MULTIPLY, BinaryOp.DIVIDE -> {
                    require(lType == NUMERIC && rType == NUMERIC) {
                        "${e.op} expression requires NUMERIC inputs"
                    }
                    NUMERIC
                }
                BinaryOp.AND, BinaryOp.OR -> {
                    require(lType == BOOLEAN && rType == BOOLEAN) {
                        "${e.op} expression requires BOOLEAN inputs"
                    }
                    BOOLEAN
                }
                BinaryOp.EQUAL, BinaryOp.NOT_EQUAL -> {
                    require(lType == rType) {
                        "${e.op} expression requires inputs of identical types"
                    }
                    BOOLEAN
                }
                BinaryOp.GREATER, BinaryOp.GREATER_EQUAL, BinaryOp.SMALLER, BinaryOp.SMALLER_EQUAL -> {
                    require(lType == NUMERIC && rType == NUMERIC) {
                        "${e.op} expression requires NUMERIC inputs"
                    }
                    BOOLEAN
                }
                BinaryOp.APPROX_EQUAL, BinaryOp.NOT_APPROX_EQUAL -> {
                    require(lType == STRING && rType == STRING) {
                        "${e.op} expression requires STRING inputs"
                    }
                    BOOLEAN
                }
            }
        }

        override fun visit(e: FunctionCallExpression) {
            val argTypes = e.arguments.map { it.checkType() }
            e.functionDefinition.checkArgumentTypes(argTypes)
            e.type = e.functionDefinition.resolveType(argTypes)
        }

        override fun visit(e: AbstractExpression) {
            e.arguments.forEach { it.checkType() }
            e.type = e.originalExpression.checkType()
        }

    }

}

