package science.atlarge.grademl.query.plan.physical

import science.atlarge.grademl.query.language.ColumnLiteral
import science.atlarge.grademl.query.model.Columns

object InsertIntervalMergingOptimization : OptimizationStrategy, PhysicalQueryPlanRewriter {

    override fun optimize(physicalQueryPlan: PhysicalQueryPlan): PhysicalQueryPlan? {
        return physicalQueryPlan.accept(this)
    }

    override fun visit(projectPlan: ProjectPlan): PhysicalQueryPlan? {
        // Merge intervals only if the projection does not select all value columns
        val selectedValueColumns = projectPlan.columnExpressions
            .filterIsInstance<ColumnLiteral>()
            .map { projectPlan.input.schema.column(it.columnPath)!! }
            .filter { !it.isKey && it.identifier !in Columns.RESERVED_COLUMN_NAMES }
            .toSet()
        val availableValueColumns = projectPlan.input.schema.columns
            .filter { !it.isKey && it.identifier !in Columns.RESERVED_COLUMN_NAMES }
        if (selectedValueColumns.size == availableValueColumns.size) return super.visit(projectPlan)
        // Interval merging requires that the input has start and end time columns
        if (projectPlan.schema.indexOfStartTimeColumn() != Columns.INDEX_START_TIME ||
            projectPlan.schema.indexOfEndTimeColumn() != Columns.INDEX_END_TIME
        ) {
            return super.visit(projectPlan)
        }
        // Recursively add interval merging operators
        val rewrittenProject = optimize(projectPlan.input)?.let {
            PhysicalQueryPlanBuilder.project(it, projectPlan.namedColumnExpressions)
        }
        return PhysicalQueryPlanBuilder.intervalMerging(rewrittenProject ?: projectPlan)
    }

    override fun visit(sortedTemporalAggregatePlan: SortedTemporalAggregatePlan): PhysicalQueryPlan {
        // Always merge intervals after a group-by-time aggregation
        return PhysicalQueryPlanBuilder.intervalMerging(
            super.visit(sortedTemporalAggregatePlan) ?: sortedTemporalAggregatePlan
        )
    }

}