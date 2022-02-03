package science.atlarge.grademl.query.analysis

import science.atlarge.grademl.query.language.*
import science.atlarge.grademl.query.model.Column

object ColumnResolution {

    fun resolveColumns(expression: Expression, columns: List<Column>) {
        val columnsByName = columns.mapIndexed { index, column -> column.identifier to index }.toMap()
        expression.accept(Visitor(columnsByName))
    }

    private class Visitor(private val columnIndicesByName: Map<String, Int>) : ExpressionVisitor {
        override fun visit(e: BooleanLiteral) {}
        override fun visit(e: NumericLiteral) {}
        override fun visit(e: StringLiteral) {}

        override fun visit(e: ColumnLiteral) {
            require(e.columnPath in columnIndicesByName) { "Column with name ${e.columnPath} not found" }
            e.columnIndex = columnIndicesByName[e.columnPath]!!
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
            e.originalExpression.accept(this)
        }
    }

}