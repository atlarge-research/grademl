package science.atlarge.grademl.query.execution.functions

import science.atlarge.grademl.query.PathUtils
import science.atlarge.grademl.query.execution.MappingFunctionImplementation
import science.atlarge.grademl.query.execution.NumericPhysicalExpression
import science.atlarge.grademl.query.execution.PhysicalExpression
import science.atlarge.grademl.query.execution.StringPhysicalExpression
import science.atlarge.grademl.query.language.Type
import science.atlarge.grademl.query.model.BuiltinFunctions
import science.atlarge.grademl.query.model.Row
import kotlin.math.roundToInt

object PathSuffix : MappingFunctionImplementation {

    override val definition = BuiltinFunctions.PATH_SUFFIX

    override fun toPhysicalExpression(
        arguments: List<PhysicalExpression>,
        argumentTypes: List<Type>
    ): StringPhysicalExpression {
        definition.checkArgumentCount(arguments.size)
        definition.checkArgumentTypes(argumentTypes)

        return object : StringPhysicalExpression {
            private val expr = arguments[0] as StringPhysicalExpression
            private val count = arguments[1] as NumericPhysicalExpression
            override fun evaluateAsString(row: Row): String {
                val path = expr.evaluateAsString(row)
                val count = count.evaluateAsNumeric(row).roundToInt()
                when {
                    count == 0 -> return ""
                    count > 0 -> {
                        var separators = 0
                        for (index in path.length - 2 downTo 0) {
                            if (path[index] in PathUtils.SEPARATORS) {
                                separators++
                                if (separators == count) {
                                    return path.substring(index + 1, path.length)
                                }
                            }
                        }
                        return path
                    }
                    else -> {
                        var separators = 0
                        for (index in 1 until path.length) {
                            if (path[index] in PathUtils.SEPARATORS) {
                                separators--
                                if (separators == count) {
                                    return path.substring(index + 1, path.length)
                                }
                            }
                        }
                        return ""
                    }
                }
            }
        }
    }

}