package science.atlarge.grademl.query.execution.functions

import science.atlarge.grademl.query.execution.*
import science.atlarge.grademl.query.language.FunctionDefinition
import science.atlarge.grademl.query.language.Type
import science.atlarge.grademl.query.model.BuiltinFunctions
import science.atlarge.grademl.query.model.Row

object Min : AggregatingFunctionImplementation {

    override val definition: FunctionDefinition = BuiltinFunctions.MIN

    override fun newAggregator(
        argumentExpressions: List<PhysicalExpression>,
        argumentTypes: List<Type>
    ): Aggregator {
        return when (argumentTypes[0]) {
            Type.UNDEFINED -> throw IllegalArgumentException()
            Type.BOOLEAN -> BooleanMin(argumentExpressions[0] as BooleanPhysicalExpression)
            Type.NUMERIC -> NumericMin(argumentExpressions[0] as NumericPhysicalExpression)
            Type.STRING -> StringMin(argumentExpressions[0] as StringPhysicalExpression)
        }
    }

    private class BooleanMin(private val expr: BooleanPhysicalExpression) : Aggregator {
        private var minValue = true
        override fun reset() {
            minValue = true
        }

        override fun addRow(row: Row) {
            if (minValue) minValue = expr.evaluateAsBoolean(row)
        }

        override fun getBooleanResult(): Boolean {
            return minValue
        }
    }

    private class NumericMin(private val expr: NumericPhysicalExpression) : Aggregator {
        private var minValue = Double.POSITIVE_INFINITY
        override fun reset() {
            minValue = Double.POSITIVE_INFINITY
        }

        override fun addRow(row: Row) {
            minValue = minOf(minValue, expr.evaluateAsNumeric(row))
        }

        override fun getNumericResult(): Double {
            return minValue
        }
    }

    private class StringMin(private val expr: StringPhysicalExpression) : Aggregator {
        private var minValue: String? = null
        override fun reset() {
            minValue = null
        }

        override fun addRow(row: Row) {
            val valueOfRow = expr.evaluateAsString(row)
            minValue = if (minValue == null) valueOfRow else minOf(minValue!!, valueOfRow)
        }

        override fun getStringResult(): String {
            return minValue!!
        }
    }

}