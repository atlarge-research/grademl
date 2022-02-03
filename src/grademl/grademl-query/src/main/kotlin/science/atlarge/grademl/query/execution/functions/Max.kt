package science.atlarge.grademl.query.execution.functions

import science.atlarge.grademl.query.execution.*
import science.atlarge.grademl.query.language.FunctionDefinition
import science.atlarge.grademl.query.language.Type
import science.atlarge.grademl.query.model.BuiltinFunctions
import science.atlarge.grademl.query.model.Row

object Max : AggregatingFunctionImplementation {

    override val definition: FunctionDefinition = BuiltinFunctions.MAX

    override fun newAggregator(
        argumentExpressions: List<PhysicalExpression>,
        argumentTypes: List<Type>
    ): Aggregator {
        return when (argumentTypes[0]) {
            Type.UNDEFINED -> throw IllegalArgumentException()
            Type.BOOLEAN -> BooleanMax(argumentExpressions[0] as BooleanPhysicalExpression)
            Type.NUMERIC -> NumericMax(argumentExpressions[0] as NumericPhysicalExpression)
            Type.STRING -> StringMax(argumentExpressions[0] as StringPhysicalExpression)
        }
    }

    private class BooleanMax(private val expr: BooleanPhysicalExpression) : Aggregator {
        private var maxValue = false
        override fun reset() {
            maxValue = false
        }

        override fun addRow(row: Row) {
            if (!maxValue) maxValue = expr.evaluateAsBoolean(row)
        }

        override fun getBooleanResult(): Boolean {
            return maxValue
        }
    }

    private class NumericMax(private val expr: NumericPhysicalExpression) : Aggregator {
        private var maxValue = Double.NEGATIVE_INFINITY
        override fun reset() {
            maxValue = Double.NEGATIVE_INFINITY
        }

        override fun addRow(row: Row) {
            maxValue = maxOf(maxValue, expr.evaluateAsNumeric(row))
        }

        override fun getNumericResult(): Double {
            return maxValue
        }
    }

    private class StringMax(private val expr: StringPhysicalExpression) : Aggregator {
        private var maxValue: String? = null
        override fun reset() {
            maxValue = null
        }

        override fun addRow(row: Row) {
            val valueOfRow = expr.evaluateAsString(row)
            maxValue = if (maxValue == null) valueOfRow else maxOf(maxValue!!, valueOfRow)
        }

        override fun getStringResult(): String {
            return maxValue!!
        }
    }

}