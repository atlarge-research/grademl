package science.atlarge.grademl.query.execution.functions

import science.atlarge.grademl.query.execution.AggregatingFunctionImplementation
import science.atlarge.grademl.query.execution.Aggregator
import science.atlarge.grademl.query.execution.PhysicalExpression
import science.atlarge.grademl.query.language.FunctionDefinition
import science.atlarge.grademl.query.language.Type
import science.atlarge.grademl.query.model.BuiltinFunctions
import science.atlarge.grademl.query.model.Row

object Count : AggregatingFunctionImplementation {

    override val definition: FunctionDefinition = BuiltinFunctions.COUNT

    override fun newAggregator(
        argumentExpressions: List<PhysicalExpression>,
        argumentTypes: List<Type>
    ) = object : Aggregator {
        private var count = 0
        override fun reset() {
            count = 0
        }

        override fun addRow(row: Row) {
            count++
        }

        override fun getNumericResult(): Double {
            return count.toDouble()
        }
    }

}