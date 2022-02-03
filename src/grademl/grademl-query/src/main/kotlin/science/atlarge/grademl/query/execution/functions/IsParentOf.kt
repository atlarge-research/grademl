package science.atlarge.grademl.query.execution.functions

import science.atlarge.grademl.query.execution.BooleanPhysicalExpression
import science.atlarge.grademl.query.execution.MappingFunctionImplementation
import science.atlarge.grademl.query.execution.PhysicalExpression
import science.atlarge.grademl.query.execution.StringPhysicalExpression
import science.atlarge.grademl.query.execution.functions.IsChildOf.isChildOf
import science.atlarge.grademl.query.language.Type
import science.atlarge.grademl.query.model.BuiltinFunctions
import science.atlarge.grademl.query.model.Row

object IsParentOf : MappingFunctionImplementation {

    override val definition = BuiltinFunctions.IS_PARENT_OF

    override fun toPhysicalExpression(
        arguments: List<PhysicalExpression>,
        argumentTypes: List<Type>
    ): BooleanPhysicalExpression {
        require(arguments.size == 2) { "IS_PARENT_OF takes two arguments" }
        require(argumentTypes[0] == Type.STRING && argumentTypes[1] == Type.STRING) {
            "IS_PARENT_OF takes two STRING arguments"
        }

        return object : BooleanPhysicalExpression {
            private val leftExpr = arguments[0] as StringPhysicalExpression
            private val rightExpr = arguments[1] as StringPhysicalExpression
            override fun evaluateAsBoolean(row: Row) =
                rightExpr.evaluateAsString(row).isChildOf(leftExpr.evaluateAsString(row))
        }
    }

}