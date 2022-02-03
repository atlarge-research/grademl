package science.atlarge.grademl.query.execution.functions

import science.atlarge.grademl.query.execution.AggregatingFunctionImplementation
import science.atlarge.grademl.query.execution.Aggregator
import science.atlarge.grademl.query.execution.NumericPhysicalExpression
import science.atlarge.grademl.query.execution.PhysicalExpression
import science.atlarge.grademl.query.language.FunctionDefinition
import science.atlarge.grademl.query.language.Type
import science.atlarge.grademl.query.model.BuiltinFunctions
import science.atlarge.grademl.query.model.Row

object Sum : AggregatingFunctionImplementation {

    override val definition: FunctionDefinition = BuiltinFunctions.SUM

    override fun newAggregator(
        argumentExpressions: List<PhysicalExpression>,
        argumentTypes: List<Type>
    ) = object : Aggregator {
        private val expr = argumentExpressions[0] as NumericPhysicalExpression
        private var sum = 0.0
        override fun reset() {
            sum = 0.0
        }

        override fun addRow(row: Row) {
            sum += expr.evaluateAsNumeric(row)
        }

        override fun getNumericResult(): Double {
            return sum
        }
    }

}