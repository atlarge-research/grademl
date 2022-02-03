package science.atlarge.grademl.query.execution

import science.atlarge.grademl.query.language.FunctionDefinition
import science.atlarge.grademl.query.language.Type
import science.atlarge.grademl.query.model.Row

sealed interface FunctionImplementation {
    val definition: FunctionDefinition
}

interface MappingFunctionImplementation : FunctionImplementation {
    fun toPhysicalExpression(arguments: List<PhysicalExpression>, argumentTypes: List<Type>): PhysicalExpression
}

interface AggregatingFunctionImplementation : FunctionImplementation {
    fun newAggregator(argumentExpressions: List<PhysicalExpression>, argumentTypes: List<Type>): Aggregator
}

interface Aggregator {
    fun reset()
    fun addRow(row: Row)
    fun getBooleanResult(): Boolean {
        throw UnsupportedOperationException("Aggregator does not produce a BOOLEAN value")
    }

    fun getNumericResult(): Double {
        throw UnsupportedOperationException("Aggregator does not produce a NUMERIC value")
    }

    fun getStringResult(): String {
        throw UnsupportedOperationException("Aggregator does not produce a STRING value")
    }
}