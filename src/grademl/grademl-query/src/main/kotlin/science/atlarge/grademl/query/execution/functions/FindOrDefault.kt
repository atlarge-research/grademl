package science.atlarge.grademl.query.execution.functions

import science.atlarge.grademl.query.execution.*
import science.atlarge.grademl.query.language.FunctionDefinition
import science.atlarge.grademl.query.language.Type
import science.atlarge.grademl.query.model.BuiltinFunctions
import science.atlarge.grademl.query.model.Row

object FindOrDefault : AggregatingFunctionImplementation {

    override val definition: FunctionDefinition = BuiltinFunctions.FIND_OR_DEFAULT

    override fun newAggregator(
        argumentExpressions: List<PhysicalExpression>,
        argumentTypes: List<Type>
    ): Aggregator {
        return when (argumentTypes[1]) {
            Type.UNDEFINED -> throw IllegalArgumentException()
            Type.BOOLEAN -> BooleanFindOrDefault(
                argumentExpressions[0] as BooleanPhysicalExpression,
                argumentExpressions[1] as BooleanPhysicalExpression,
                argumentExpressions[2] as BooleanPhysicalExpression
            )
            Type.NUMERIC -> NumericFindOrDefault(
                argumentExpressions[0] as BooleanPhysicalExpression,
                argumentExpressions[1] as NumericPhysicalExpression,
                argumentExpressions[2] as NumericPhysicalExpression
            )
            Type.STRING -> StringFindOrDefault(
                argumentExpressions[0] as BooleanPhysicalExpression,
                argumentExpressions[1] as StringPhysicalExpression,
                argumentExpressions[2] as StringPhysicalExpression
            )
        }
    }

    private class BooleanFindOrDefault(
        private val condition: BooleanPhysicalExpression,
        private val valueExpr: BooleanPhysicalExpression,
        private val defaultExpr: BooleanPhysicalExpression
    ) : Aggregator {
        private var valueFound = false
        private var firstRow = true
        private var value = false

        override fun reset() {
            valueFound = false
            firstRow = true
            value = false
        }

        override fun addRow(row: Row) {
            if (!valueFound && condition.evaluateAsBoolean(row)) {
                value = valueExpr.evaluateAsBoolean(row)
                valueFound = true
            } else if (!valueFound && firstRow) {
                value = defaultExpr.evaluateAsBoolean(row)
                firstRow = false
            }
        }

        override fun getBooleanResult(): Boolean {
            return value
        }
    }

    private class NumericFindOrDefault(
        private val condition: BooleanPhysicalExpression,
        private val valueExpr: NumericPhysicalExpression,
        private val defaultExpr: NumericPhysicalExpression
    ) : Aggregator {
        private var valueFound = false
        private var firstRow = true
        private var value = Double.NaN

        override fun reset() {
            valueFound = false
            firstRow = true
            value = Double.NaN
        }

        override fun addRow(row: Row) {
            if (!valueFound && condition.evaluateAsBoolean(row)) {
                value = valueExpr.evaluateAsNumeric(row)
                valueFound = true
            } else if (!valueFound && firstRow) {
                value = defaultExpr.evaluateAsNumeric(row)
                firstRow = false
            }
        }

        override fun getNumericResult(): Double {
            return value
        }
    }

    private class StringFindOrDefault(
        private val condition: BooleanPhysicalExpression,
        private val valueExpr: StringPhysicalExpression,
        private val defaultExpr: StringPhysicalExpression
    ) : Aggregator {
        private var valueFound = false
        private var firstRow = true
        private var value: String? = null

        override fun reset() {
            valueFound = false
            firstRow = true
            value = null
        }

        override fun addRow(row: Row) {
            if (!valueFound && condition.evaluateAsBoolean(row)) {
                value = valueExpr.evaluateAsString(row)
                valueFound = true
            } else if (!valueFound && firstRow) {
                value = defaultExpr.evaluateAsString(row)
                firstRow = false
            }
        }

        override fun getStringResult(): String {
            return value!!
        }
    }

}