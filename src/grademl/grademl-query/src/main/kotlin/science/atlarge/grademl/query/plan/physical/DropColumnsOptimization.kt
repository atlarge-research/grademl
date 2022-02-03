package science.atlarge.grademl.query.plan.physical

import science.atlarge.grademl.query.analysis.ASTAnalysis
import science.atlarge.grademl.query.analysis.ASTUtils
import science.atlarge.grademl.query.language.ColumnLiteral
import science.atlarge.grademl.query.language.NamedExpression
import science.atlarge.grademl.query.model.Columns

object DropColumnsOptimization : OptimizationStrategy {

    override fun optimize(physicalQueryPlan: PhysicalQueryPlan): PhysicalQueryPlan? {
        val outputColumns = physicalQueryPlan.schema.columns.map { it.identifier }.toSet()
        return physicalQueryPlan.accept(DropColumnsRewriter(outputColumns))
    }

}

private class DropColumnsRewriter(
    outputColumns: Set<String>
) : PhysicalQueryPlanVisitor<PhysicalQueryPlan?> {

    private var requiredColumns = outputColumns

    private fun PhysicalQueryPlan.recurse(columnsToKeep: Set<String>): PhysicalQueryPlan? {
        // Recursively rewrite a query plan, dropping unnecessary columns where possible
        val oldRequiredColumns = requiredColumns
        requiredColumns = columnsToKeep
        val rewrittenPlan = accept(this@DropColumnsRewriter)
        requiredColumns = oldRequiredColumns
        return rewrittenPlan
    }

    private fun PhysicalQueryPlan.recurseAndDropColumns(columnsToKeep: Set<String>): PhysicalQueryPlan? {
        // Recursively rewrite a query plan, dropping unnecessary columns where possible
        var rewrittenPlan = recurse(columnsToKeep) ?: this
        // Determine if there are any excess columns in the rewritten query plan
        if (rewrittenPlan.schema.columns.size > columnsToKeep.size) {
            // If so, create a projection to select only required columns
            val orderedColumnsToKeep = rewrittenPlan.schema.columns.filter { it.identifier in columnsToKeep }
            val columnExpressions = orderedColumnsToKeep.map { column ->
                NamedExpression(
                    ASTAnalysis.analyzeExpression(ColumnLiteral(column.identifier), rewrittenPlan.schema.columns),
                    column.identifier
                )
            }
            rewrittenPlan = PhysicalQueryPlanBuilder.project(rewrittenPlan, columnExpressions)
        }
        // Return the rewritten plan
        return if (rewrittenPlan !== this) rewrittenPlan else null
    }

    override fun visit(filterPlan: FilterPlan): PhysicalQueryPlan? {
        // Do not use a projection to drop any columns directly before a filter, always filter then project
        // Recurse into the filter's input to attempt dropping columns further down

        // Determine which columns are needed as input to the filter
        val filteredInputColumns = ASTUtils.findColumnLiterals(filterPlan.filterCondition).map { it.columnPath }
        val allRequiredColumns = requiredColumns + filteredInputColumns
        // Rewrite the input to drop any columns not required for this filter operation or its output
        val rewrittenInput = filterPlan.input.recurse(allRequiredColumns)
        // Apply a filter to the rewritten input
        return if (rewrittenInput != null) {
            PhysicalQueryPlanBuilder.filter(rewrittenInput, filterPlan.filterCondition)
        } else {
            null
        }
    }

    override fun visit(intervalMergingPlan: IntervalMergingPlan): PhysicalQueryPlan? {
        val rewrittenInput = intervalMergingPlan.input.recurseAndDropColumns(requiredColumns)
        return if (rewrittenInput != null) PhysicalQueryPlanBuilder.intervalMerging(rewrittenInput) else null
    }

    override fun visit(linearTableScanPlan: LinearTableScanPlan): PhysicalQueryPlan? {
        return null
    }

    override fun visit(projectPlan: ProjectPlan): PhysicalQueryPlan? {
        // Determine which projection expressions to keep and which input columns they need
        val requiredProjections = projectPlan.namedColumnExpressions.filter { it.name in requiredColumns }
        val requiredInputs = requiredProjections.flatMap { ASTUtils.findColumnLiterals(it.expr) }
            .map { it.columnPath }
            .toSet()
        // Rewrite the input to drop any columns not required for this projection
        val rewrittenInput = projectPlan.input.recurse(requiredInputs)
        // Don't rewrite this projection if the input hasn't changed and no output columns can be dropped
        if (rewrittenInput == null && requiredProjections.size == projectPlan.namedColumnExpressions.size) return null
        return PhysicalQueryPlanBuilder.project(rewrittenInput ?: projectPlan.input, requiredProjections)
    }

    override fun visit(sortedAggregatePlan: SortedAggregatePlan): PhysicalQueryPlan? {
        // Determine which aggregation/projection expressions to keep and which input columns they need
        val requiredAggregations = sortedAggregatePlan.namedColumnExpressions.filter { it.name in requiredColumns }
        val requiredInputs = setOf(Columns.START_TIME.identifier, Columns.END_TIME.identifier) +
                sortedAggregatePlan.groupByColumns +
                requiredAggregations.flatMap { ASTUtils.findColumnLiterals(it.expr) }.map { it.columnPath }
        // Rewrite the input to drop any columns not required for this aggregation
        val rewrittenInput = sortedAggregatePlan.input.recurse(requiredInputs)
        // Don't rewrite this aggregation if the input hasn't changed and no output columns can be dropped
        if (rewrittenInput == null && requiredAggregations.size == sortedAggregatePlan.namedColumnExpressions.size)
            return null
        return PhysicalQueryPlanBuilder.sortedAggregate(
            rewrittenInput ?: sortedAggregatePlan.input,
            sortedAggregatePlan.groupByColumns,
            requiredAggregations
        )
    }

    override fun visit(sortedTemporalAggregatePlan: SortedTemporalAggregatePlan): PhysicalQueryPlan? {
        // Determine which aggregation/projection expressions to keep and which input columns they need
        val requiredAggregations = sortedTemporalAggregatePlan.namedColumnExpressions
            .filter { it.name in requiredColumns }
        val requiredInputs = setOf(Columns.START_TIME.identifier, Columns.END_TIME.identifier) +
                sortedTemporalAggregatePlan.groupByColumns +
                requiredAggregations.flatMap { ASTUtils.findColumnLiterals(it.expr) }.map { it.columnPath }
        // Rewrite the input to drop any columns not required for this aggregation
        val rewrittenInput = sortedTemporalAggregatePlan.input.recurse(requiredInputs)
        // Don't rewrite this aggregation if the input hasn't changed and no output columns can be dropped
        if (rewrittenInput == null && requiredAggregations.size == sortedTemporalAggregatePlan.namedColumnExpressions.size)
            return null
        return PhysicalQueryPlanBuilder.sortedTemporalAggregate(
            rewrittenInput ?: sortedTemporalAggregatePlan.input,
            sortedTemporalAggregatePlan.groupByColumns,
            requiredAggregations
        )
    }

    override fun visit(sortedTemporalJoinPlan: SortedTemporalJoinPlan): PhysicalQueryPlan? {
        // Determine which inputs are needed from the left and right input tables
        val requiredInputs = setOf(Columns.START_TIME.identifier, Columns.END_TIME.identifier) + requiredColumns
        val leftJoinColumns = sortedTemporalJoinPlan.leftJoinColumns.map { it.column.columnPath }.toSet()
        val requiredLeftInputs = sortedTemporalJoinPlan.leftInput.schema.columns
            .filter { column ->
                column.identifier in requiredInputs || column.identifier in leftJoinColumns
            }
            .map { it.identifier }
            .toSet()
        val rightJoinColumns = sortedTemporalJoinPlan.rightJoinColumns.map { it.column.columnPath }.toSet()
        val requiredRightInputs = sortedTemporalJoinPlan.rightInput.schema.columns
            .filter { column ->
                column.identifier in requiredInputs || column.identifier in rightJoinColumns
            }
            .map { it.identifier }
            .toSet()
        // Rewrite both inputs
        val rewrittenLeftInput = sortedTemporalJoinPlan.leftInput.recurseAndDropColumns(requiredLeftInputs)
        val rewrittenRightInput = sortedTemporalJoinPlan.rightInput.recurseAndDropColumns(requiredRightInputs)
        // Determine which join inputs can be dropped after the join
        val leftColumnsToDrop = leftJoinColumns - requiredInputs
        val rightColumnsToDrop = rightJoinColumns - requiredInputs
        // If either input is rewritten, create a new join
        if (rewrittenLeftInput == null && rewrittenRightInput == null) return null
        return PhysicalQueryPlanBuilder.sortedTemporalJoin(
            rewrittenLeftInput ?: sortedTemporalJoinPlan.leftInput,
            rewrittenRightInput ?: sortedTemporalJoinPlan.rightInput,
            sortedTemporalJoinPlan.leftJoinColumns,
            sortedTemporalJoinPlan.rightJoinColumns,
            leftColumnsToDrop + sortedTemporalJoinPlan.leftDropColumns,
            rightColumnsToDrop + sortedTemporalJoinPlan.rightDropColumns
        )
    }

    override fun visit(sortPlan: SortPlan): PhysicalQueryPlan? {
        // Determine which columns are needed as input to the sort
        val sortedInputColumns = sortPlan.sortByColumns.map { it.column.columnPath }
        val allRequiredColumns = requiredColumns + sortedInputColumns
        // Rewrite the input to drop any columns not required for this filter operation or its output
        val rewrittenInput = sortPlan.input.recurseAndDropColumns(allRequiredColumns)
        // Apply a sort to the rewritten input
        return if (rewrittenInput != null) {
            PhysicalQueryPlanBuilder.sort(rewrittenInput, sortPlan.sortByColumns)
        } else {
            null
        }
    }
}