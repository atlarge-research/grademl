package science.atlarge.grademl.query.language

sealed interface FunctionDefinition {
    val functionName: String
    val isAggregatingFunction: Boolean
    val isDeterministic: Boolean

    fun checkArgumentCount(argCount: Int)
    fun checkArgumentTypes(argTypes: List<Type>)
    fun resolveType(argTypes: List<Type>): Type
}

abstract class ConcreteFunctionDefinition(
    override val functionName: String,
    override val isAggregatingFunction: Boolean,
    override val isDeterministic: Boolean
) : FunctionDefinition

abstract class VirtualFunctionDefinition(
    override val functionName: String,
    override val isAggregatingFunction: Boolean,
    override val isDeterministic: Boolean
) : FunctionDefinition {
    abstract fun rewrite(arguments: List<Expression>): Expression
}