package science.atlarge.grademl.query.plan.physical

import science.atlarge.grademl.query.analysis.ASTAnalysis
import science.atlarge.grademl.query.analysis.ColumnAliasingPass
import science.atlarge.grademl.query.analysis.FilterConditionSeparation
import science.atlarge.grademl.query.language.*

object PushDownFilterOptimization : OptimizationStrategy, PhysicalQueryPlanRewriter {

    override fun optimize(physicalQueryPlan: PhysicalQueryPlan): PhysicalQueryPlan? {
        return physicalQueryPlan.accept(this)
    }

    override fun visit(filterPlan: FilterPlan): PhysicalQueryPlan? {
        // Pushing down a filter operation is possible from some types of input operations
        return when (filterPlan.input) {
            is FilterPlan -> mergeFilters(filterPlan.filterCondition, filterPlan.input)
            is LinearTableScanPlan -> pushIntoLinearTableScan(filterPlan.filterCondition, filterPlan.input)
            is ProjectPlan -> pushPastProject(filterPlan.filterCondition, filterPlan.input)
            is SortedAggregatePlan -> pushPastSortedAggregate(filterPlan.filterCondition, filterPlan.input)
            is SortedTemporalAggregatePlan ->
                pushPastSortedTemporalAggregate(filterPlan.filterCondition, filterPlan.input)
            is SortedTemporalJoinPlan -> pushPastSortedTemporalJoin(filterPlan.filterCondition, filterPlan.input)
            is SortPlan -> pushPastSort(filterPlan.filterCondition, filterPlan.input)
            else -> null
        } ?: super.visit(filterPlan)
    }

    private fun mergeFilters(filterCondition: Expression, filterPlan: FilterPlan): PhysicalQueryPlan {
        return optimizeOrReturn(
            PhysicalQueryPlanBuilder.filter(
                filterPlan.input,
                ASTAnalysis.analyzeExpression(
                    BinaryExpression(filterCondition, filterPlan.filterCondition, BinaryOp.AND),
                    filterPlan.input.schema.columns
                )
            )
        )
    }

    private fun pushIntoLinearTableScan(
        filterCondition: Expression,
        linearTableScanPlan: LinearTableScanPlan
    ): PhysicalQueryPlan? {
        // Return if the input table cannot be filtered
        if (linearTableScanPlan.filterableColumns.isEmpty()) return null
        // Separate from the filter condition a part using only filterableColumns
        val separatedFilter = FilterConditionSeparation.splitFilterConditionByColumns(
            filterCondition, listOf(
                linearTableScanPlan.filterableColumns.map { column ->
                    linearTableScanPlan.schema.indexOfColumn(column)!!
                }.toSet()
            )
        )
        val pushDownFilter = separatedFilter.filterExpressionPerSplit[0]
        val remainingFilter = separatedFilter.remainingFilterExpression
        // Return if no condition can be pushed into the table scan
        if (pushDownFilter == null) return null
        // Apply (part of) the filter to the table scan
        val filteredTableScan = PhysicalQueryPlanBuilder.linearScan(
            linearTableScanPlan.table,
            linearTableScanPlan.tableName,
            FilterConditionSeparation.mergeExpressions(
                listOfNotNull(
                    pushDownFilter,
                    linearTableScanPlan.filterCondition
                )
            )
        )
        // Apply the remaining filter, if any
        return if (remainingFilter != null) {
            PhysicalQueryPlanBuilder.filter(filteredTableScan, remainingFilter)
        } else {
            filteredTableScan
        }
    }

    private fun pushPastProject(filterCondition: Expression, projectPlan: ProjectPlan): PhysicalQueryPlan? {
        // Identify columns in the projection that are simple aliases of the projection's input
        val aliasingColumns = projectPlan.namedColumnExpressions.mapIndexedNotNull { index, namedExpression ->
            if (namedExpression.expr is ColumnLiteral) index to namedExpression else null
        }
        // Split the filter condition into a part using only aliased columns and a part using any column
        val separatedFilter = FilterConditionSeparation.splitFilterConditionByColumns(
            filterCondition, listOf(aliasingColumns.map { it.first }.toSet())
        )
        val pushDownFilter = separatedFilter.filterExpressionPerSplit[0]
        val remainingFilter = separatedFilter.remainingFilterExpression
        // Return if no condition can be pushed down
        if (pushDownFilter == null) return null
        // Translate the filter expression to be pushed down
        val reverseAliases = aliasingColumns.associate {
            it.second.name to (it.second.expr as ColumnLiteral).columnPath
        }
        val rewrittenPushDownFilter = ColumnAliasingPass.aliasColumns(pushDownFilter, reverseAliases)
        // Create a new filter input to the projection and optimize it
        val innerFilter = optimizeOrReturn(PhysicalQueryPlanBuilder.filter(projectPlan.input, rewrittenPushDownFilter))
        // Create a projection of the filtered input
        val newProject = PhysicalQueryPlanBuilder.project(innerFilter, projectPlan.namedColumnExpressions)
        // Add an outer filter operation with part of the filter condition that could not be pushed down
        return if (remainingFilter != null) {
            PhysicalQueryPlanBuilder.filter(newProject, remainingFilter)
        } else {
            newProject
        }
    }

    private fun pushPastSortedAggregate(
        filterCondition: Expression,
        sortedAggregatePlan: SortedAggregatePlan
    ): PhysicalQueryPlan? {
        // Try to push down filter condition
        val (innerFilter, remainingFilter) = createInnerAndRemainingFilterForAggregate(
            filterCondition,
            sortedAggregatePlan.input,
            sortedAggregatePlan.groupByColumns,
            sortedAggregatePlan.namedColumnExpressions
        )
        if (innerFilter == null) return null
        // Create an aggregation of the filtered input
        val newAggregate = PhysicalQueryPlanBuilder.sortedAggregate(
            innerFilter,
            sortedAggregatePlan.groupByColumns,
            sortedAggregatePlan.namedColumnExpressions
        )
        // Add an outer filter operation with part of the filter condition that could not be pushed down
        return if (remainingFilter != null) {
            PhysicalQueryPlanBuilder.filter(newAggregate, remainingFilter)
        } else {
            newAggregate
        }
    }

    private fun pushPastSortedTemporalAggregate(
        filterCondition: Expression,
        sortedTemporalAggregatePlan: SortedTemporalAggregatePlan
    ): PhysicalQueryPlan? {
        // Try to push down filter condition
        val (innerFilter, remainingFilter) = createInnerAndRemainingFilterForAggregate(
            filterCondition,
            sortedTemporalAggregatePlan.input,
            sortedTemporalAggregatePlan.groupByColumns,
            sortedTemporalAggregatePlan.namedColumnExpressions
        )
        if (innerFilter == null) return null
        // Create an aggregation of the filtered input
        val newAggregate = PhysicalQueryPlanBuilder.sortedTemporalAggregate(
            innerFilter,
            sortedTemporalAggregatePlan.groupByColumns,
            sortedTemporalAggregatePlan.namedColumnExpressions
        )
        // Add an outer filter operation with part of the filter condition that could not be pushed down
        return if (remainingFilter != null) {
            PhysicalQueryPlanBuilder.filter(newAggregate, remainingFilter)
        } else {
            newAggregate
        }
    }

    private fun createInnerAndRemainingFilterForAggregate(
        filterCondition: Expression,
        aggregationInput: PhysicalQueryPlan,
        groupByColumns: List<String>,
        namedColumnExpressions: List<NamedExpression>
    ): Pair<PhysicalQueryPlan?, Expression?> {
        // Only filters on group-by columns can be safely pushed down
        // Determine which output columns are aliases of input group-by columns
        val aliasingColumns = namedColumnExpressions.mapIndexedNotNull { index, namedExpression ->
            if (namedExpression.expr is ColumnLiteral) index to namedExpression else null
        }
        val aliasingGroupByColumns = aliasingColumns.filter {
            val inputColumn = (it.second.expr as ColumnLiteral).columnPath
            inputColumn in groupByColumns
        }
        // Split the filter condition into a part using only aliased grouped columns and a part using any column
        val separatedFilter = FilterConditionSeparation.splitFilterConditionByColumns(
            filterCondition, listOf(aliasingGroupByColumns.map { it.first }.toSet())
        )
        val pushDownFilter = separatedFilter.filterExpressionPerSplit[0]
        val remainingFilter = separatedFilter.remainingFilterExpression
        // Return if no condition can be pushed down
        if (pushDownFilter == null) return null to null
        // Translate the filter expression to be pushed down
        val reverseAliases = aliasingColumns.associate {
            it.second.name to (it.second.expr as ColumnLiteral).columnPath
        }
        val rewrittenPushDownFilter = ColumnAliasingPass.aliasColumns(pushDownFilter, reverseAliases)
        // Create a new filter input to the aggregation and optimize it
        val innerFilter = optimizeOrReturn(
            PhysicalQueryPlanBuilder.filter(aggregationInput, rewrittenPushDownFilter)
        )
        return innerFilter to remainingFilter
    }

    private fun pushPastSortedTemporalJoin(
        filterCondition: Expression,
        sortedTemporalJoinPlan: SortedTemporalJoinPlan
    ): PhysicalQueryPlan? {
        // Split the filter condition into a left-only condition, a right-only condition, and a joint condition
        val leftColumns = sortedTemporalJoinPlan.schema.columns.withIndex()
            .filter { !it.value.isReserved }
            .filter { sortedTemporalJoinPlan.leftInput.schema.column(it.value.identifier) != null }
        val rightColumns = sortedTemporalJoinPlan.schema.columns.withIndex()
            .filter { !it.value.isReserved }
            .filter { sortedTemporalJoinPlan.rightInput.schema.column(it.value.identifier) != null }
        val separatedFilter = FilterConditionSeparation.splitFilterConditionByColumns(
            filterCondition, listOf(leftColumns.map { it.index }.toSet(), rightColumns.map { it.index }.toSet())
        )
        val leftFilter = separatedFilter.filterExpressionPerSplit[0]
        val rightFilter = separatedFilter.filterExpressionPerSplit[1]
        val remainingFilter = separatedFilter.remainingFilterExpression
        // Return if no condition can be pushed down
        if (leftFilter == null && rightFilter == null) return null
        // Push down the left and right filter conditions
        val filteredLeft = if (leftFilter != null) {
            optimizeOrReturn(PhysicalQueryPlanBuilder.filter(sortedTemporalJoinPlan.leftInput, leftFilter))
        } else {
            sortedTemporalJoinPlan.leftInput
        }
        val filteredRight = if (rightFilter != null) {
            optimizeOrReturn(PhysicalQueryPlanBuilder.filter(sortedTemporalJoinPlan.rightInput, rightFilter))
        } else {
            sortedTemporalJoinPlan.rightInput
        }
        // Join the filtered inputs
        val newJoin = PhysicalQueryPlanBuilder.sortedTemporalJoin(
            filteredLeft,
            filteredRight,
            sortedTemporalJoinPlan.leftJoinColumns,
            sortedTemporalJoinPlan.rightJoinColumns,
            sortedTemporalJoinPlan.leftDropColumns,
            sortedTemporalJoinPlan.rightDropColumns
        )
        // Add an outer filter operation with part of the filter condition that could not be pushed down
        return if (remainingFilter != null) {
            PhysicalQueryPlanBuilder.filter(newJoin, remainingFilter)
        } else {
            newJoin
        }
    }

    private fun pushPastSort(filterCondition: Expression, sortPlan: SortPlan): PhysicalQueryPlan {
        return PhysicalQueryPlanBuilder.sort(
            optimizeOrReturn(
                PhysicalQueryPlanBuilder.filter(sortPlan.input, filterCondition)
            ),
            sortPlan.sortByColumns
        )
    }

}