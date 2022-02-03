package science.atlarge.grademl.query.analysis

import science.atlarge.grademl.query.language.ConcreteFunctionDefinition
import science.atlarge.grademl.query.language.Expression
import science.atlarge.grademl.query.language.FunctionCallExpression
import science.atlarge.grademl.query.language.VirtualFunctionDefinition
import science.atlarge.grademl.query.model.BuiltinFunctions

object FunctionResolution {

    fun resolveFunctionCalls(expression: Expression): Expression {
        return Visitor.rewriteExpression(expression)
    }

    private object Visitor : ExpressionRewritePass() {

        override fun rewrite(e: FunctionCallExpression): Expression {
            // Lookup the function definition
            val definition = BuiltinFunctions.find { it.functionName.uppercase() == e.functionName.uppercase().trim() }
            requireNotNull(definition) { "Function with name ${e.functionName} not found" }
            definition.checkArgumentCount(e.arguments.size)

            return when (definition) {
                is ConcreteFunctionDefinition -> {
                    // Keep concrete function calls
                    val rewrittenArgs = e.arguments.map { it.rewrite() }
                    e.functionDefinition = definition
                    if (e.arguments.indices.all { i -> rewrittenArgs[i] === e.arguments[i] }) e
                    else e.copy(newArguments = rewrittenArgs)
                }
                is VirtualFunctionDefinition -> {
                    // Rewrite virtual function calls
                    rewriteExpression(definition.rewrite(e.arguments))
                }
            }
        }

    }

}