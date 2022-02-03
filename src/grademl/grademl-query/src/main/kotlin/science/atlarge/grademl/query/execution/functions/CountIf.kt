package science.atlarge.grademl.query.execution.functions

import science.atlarge.grademl.query.execution.AggregatingFunctionImplementation
import science.atlarge.grademl.query.execution.Aggregator
import science.atlarge.grademl.query.execution.BooleanPhysicalExpression
import science.atlarge.grademl.query.execution.PhysicalExpression
import science.atlarge.grademl.query.language.FunctionDefinition
import science.atlarge.grademl.query.language.Type
import science.atlarge.grademl.query.model.BuiltinFunctions
import science.atlarge.grademl.query.model.Row

object CountIf : AggregatingFunctionImplementation {

    override val definition: FunctionDefinition = BuiltinFunctions.COUNT_IF

    override fun newAggregator(
        argumentExpressions: List<PhysicalExpression>,
        argumentTypes: List<Type>
    ) = object : Aggregator {
        private val condition = argumentExpressions[0] as BooleanPhysicalExpression
        private var count = 0
        override fun reset() {
            count = 0
        }

        override fun addRow(row: Row) {
            if (condition.evaluateAsBoolean(row)) count++
        }

        override fun getNumericResult(): Double {
            return count.toDouble()
        }
    }

}