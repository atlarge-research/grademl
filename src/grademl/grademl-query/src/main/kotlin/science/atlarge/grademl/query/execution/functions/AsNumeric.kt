package science.atlarge.grademl.query.execution.functions

import science.atlarge.grademl.query.execution.BooleanPhysicalExpression
import science.atlarge.grademl.query.execution.MappingFunctionImplementation
import science.atlarge.grademl.query.execution.NumericPhysicalExpression
import science.atlarge.grademl.query.execution.PhysicalExpression
import science.atlarge.grademl.query.language.Type
import science.atlarge.grademl.query.model.BuiltinFunctions
import science.atlarge.grademl.query.model.Row

object AsNumeric : MappingFunctionImplementation {

    override val definition = BuiltinFunctions.AS_NUMERIC

    override fun toPhysicalExpression(
        arguments: List<PhysicalExpression>,
        argumentTypes: List<Type>
    ): NumericPhysicalExpression {
        require(arguments.size == 1) { "AS_NUMERIC takes one argument" }
        return when (argumentTypes[0]) {
            Type.BOOLEAN -> object : NumericPhysicalExpression {
                private val expr = arguments[0] as BooleanPhysicalExpression
                override fun evaluateAsNumeric(row: Row) = if (expr.evaluateAsBoolean(row)) 1.0 else 0.0
            }
            Type.NUMERIC -> object : NumericPhysicalExpression {
                private val expr = arguments[0] as NumericPhysicalExpression
                override fun evaluateAsNumeric(row: Row) = expr.evaluateAsNumeric(row)
            }
            else -> throw IllegalArgumentException("AS_NUMERIC cannot convert ${argumentTypes[0]} values")
        }
    }

}