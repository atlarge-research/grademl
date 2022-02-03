package science.atlarge.grademl.query.execution.functions

import science.atlarge.grademl.query.PathUtils
import science.atlarge.grademl.query.execution.MappingFunctionImplementation
import science.atlarge.grademl.query.execution.PhysicalExpression
import science.atlarge.grademl.query.execution.StringPhysicalExpression
import science.atlarge.grademl.query.language.Type
import science.atlarge.grademl.query.model.BuiltinFunctions
import science.atlarge.grademl.query.model.Row

object ParentOf : MappingFunctionImplementation {

    override val definition = BuiltinFunctions.PARENT_OF

    override fun toPhysicalExpression(
        arguments: List<PhysicalExpression>,
        argumentTypes: List<Type>
    ): StringPhysicalExpression {
        require(arguments.size == 1) { "PARENT_OF takes one argument" }
        require(argumentTypes[0] == Type.STRING) { "PARENT_OF takes one STRING argument" }

        return object : StringPhysicalExpression {
            private val expr = arguments[0] as StringPhysicalExpression
            override fun evaluateAsString(row: Row): String {
                val path = expr.evaluateAsString(row)
                val lastSeparator = path.lastIndexOfAny(PathUtils.SEPARATORS)
                return if (lastSeparator > 0) path.substring(0, lastSeparator) else ""
            }
        }
    }

}