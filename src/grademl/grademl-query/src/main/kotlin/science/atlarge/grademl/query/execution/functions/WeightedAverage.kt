package science.atlarge.grademl.query.execution.functions

import science.atlarge.grademl.query.execution.AggregatingFunctionImplementation
import science.atlarge.grademl.query.execution.Aggregator
import science.atlarge.grademl.query.execution.NumericPhysicalExpression
import science.atlarge.grademl.query.execution.PhysicalExpression
import science.atlarge.grademl.query.language.FunctionDefinition
import science.atlarge.grademl.query.language.Type
import science.atlarge.grademl.query.model.BuiltinFunctions
import science.atlarge.grademl.query.model.Row

object WeightedAverage : AggregatingFunctionImplementation {

    override val definition: FunctionDefinition = BuiltinFunctions.WEIGHTED_AVG

    override fun newAggregator(
        argumentExpressions: List<PhysicalExpression>,
        argumentTypes: List<Type>
    ) = object : Aggregator {
        private val valueExpr = argumentExpressions[0] as NumericPhysicalExpression
        private val weightExpr = argumentExpressions[1] as NumericPhysicalExpression
        private var sumWeightedValues = 0.0
        private var sumWeights = 0.0
        override fun reset() {
            sumWeightedValues = 0.0
            sumWeights = 0.0
        }

        override fun addRow(row: Row) {
            val value = valueExpr.evaluateAsNumeric(row)
            val weight = weightExpr.evaluateAsNumeric(row)
            sumWeightedValues += value * weight
            sumWeights += weight
        }

        override fun getNumericResult(): Double {
            return sumWeightedValues / sumWeights
        }
    }

}