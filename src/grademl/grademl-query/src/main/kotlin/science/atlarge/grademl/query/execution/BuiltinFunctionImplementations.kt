package science.atlarge.grademl.query.execution

import science.atlarge.grademl.query.execution.functions.*
import science.atlarge.grademl.query.language.FunctionDefinition

object BuiltinFunctionImplementations {

    private val allImplementations = listOf(
        // Common aggregation functions
        Count, CountIf, Min, Max, Sum, Average, WeightedAverage, MinOf, MaxOf,
        // Data reshaping functions
        FindOrDefault, AsNumeric,
        // Helper functions for traversing hierarchical models
        IsParentOf, IsChildOf, IsAncestorOf, IsDescendantOf, ParentOf,
        PathPrefix, PathSuffix
    ).associateBy { it.definition }

    fun from(definition: FunctionDefinition): FunctionImplementation {
        return allImplementations[definition] ?: throw IllegalArgumentException(
            "No implementation defined for function ${definition.functionName}"
        )
    }

}