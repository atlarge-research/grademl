package science.atlarge.grademl.query.execution.functions

import science.atlarge.grademl.query.execution.*
import science.atlarge.grademl.query.language.Type
import science.atlarge.grademl.query.model.BuiltinFunctions
import science.atlarge.grademl.query.model.Row

object MaxOf : MappingFunctionImplementation {

    override val definition = BuiltinFunctions.MAX_OF

    override fun toPhysicalExpression(
        arguments: List<PhysicalExpression>,
        argumentTypes: List<Type>
    ): PhysicalExpression {
        require(arguments.isNotEmpty()) { "MAX_OF must have at least one argument" }
        val type = argumentTypes[0]
        require(argumentTypes.all { it == type }) { "All arguments of MAX_OF must have the same type" }

        return when (type) {
            Type.BOOLEAN -> object : BooleanPhysicalExpression {
                private val typedArguments = arguments.map { it as BooleanPhysicalExpression }.toTypedArray()
                override fun evaluateAsBoolean(row: Row): Boolean {
                    return typedArguments.any { it.evaluateAsBoolean(row) }
                }
            }
            Type.NUMERIC -> object : NumericPhysicalExpression {
                private val typedArguments = arguments.map { it as NumericPhysicalExpression }.toTypedArray()
                override fun evaluateAsNumeric(row: Row): Double {
                    var maxValue = typedArguments[0].evaluateAsNumeric(row)
                    for (i in 1 until typedArguments.size) {
                        maxValue = maxOf(maxValue, typedArguments[i].evaluateAsNumeric(row))
                    }
                    return maxValue
                }
            }
            Type.STRING -> object : StringPhysicalExpression {
                private val typedArguments = arguments.map { it as StringPhysicalExpression }.toTypedArray()
                override fun evaluateAsString(row: Row): String {
                    var maxValue = typedArguments[0].evaluateAsString(row)
                    for (i in 1 until typedArguments.size) {
                        maxValue = maxOf(maxValue, typedArguments[i].evaluateAsString(row))
                    }
                    return maxValue
                }
            }
            else -> throw IllegalArgumentException("MaxOf does not support $type arguments")
        }
    }

}