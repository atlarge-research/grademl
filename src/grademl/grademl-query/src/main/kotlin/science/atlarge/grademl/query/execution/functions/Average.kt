package science.atlarge.grademl.query.execution.functions

import science.atlarge.grademl.query.execution.AggregatingFunctionImplementation
import science.atlarge.grademl.query.execution.Aggregator
import science.atlarge.grademl.query.execution.NumericPhysicalExpression
import science.atlarge.grademl.query.execution.PhysicalExpression
import science.atlarge.grademl.query.language.FunctionDefinition
import science.atlarge.grademl.query.language.Type
import science.atlarge.grademl.query.model.BuiltinFunctions
import science.atlarge.grademl.query.model.Row

object Average : AggregatingFunctionImplementation {

    override val definition: FunctionDefinition = BuiltinFunctions.AVG

    override fun newAggregator(
        argumentExpressions: List<PhysicalExpression>,
        argumentTypes: List<Type>
    ) = object : Aggregator {
        private val valueExpr = argumentExpressions[0] as NumericPhysicalExpression
        private var sumValues = 0.0
        private var count = 0L
        override fun reset() {
            sumValues = 0.0
            count = 0L
        }

        override fun addRow(row: Row) {
            sumValues += valueExpr.evaluateAsNumeric(row)
            count++
        }

        override fun getNumericResult(): Double {
            return sumValues / count
        }
    }

}