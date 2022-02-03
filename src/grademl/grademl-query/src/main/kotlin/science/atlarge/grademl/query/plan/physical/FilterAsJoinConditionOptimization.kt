package science.atlarge.grademl.query.plan.physical

import science.atlarge.grademl.query.analysis.ASTUtils
import science.atlarge.grademl.query.analysis.ColumnReplacementPass
import science.atlarge.grademl.query.analysis.FilterConditionSeparation
import science.atlarge.grademl.query.execution.SortColumn
import science.atlarge.grademl.query.language.*
import science.atlarge.grademl.query.model.Column
import science.atlarge.grademl.query.model.TableSchema

object FilterAsJoinConditionOptimization : OptimizationStrategy, PhysicalQueryPlanRewriter {

    override fun optimize(physicalQueryPlan: PhysicalQueryPlan): PhysicalQueryPlan? {
        return physicalQueryPlan.accept(this)
    }

    override fun visit(filterPlan: FilterPlan): PhysicalQueryPlan? {
        // Match on filters directly after a join, or filter after join and project
        return when (filterPlan.input) {
            is SortedTemporalJoinPlan -> collapseFilterIntoJoin(filterPlan.filterCondition, filterPlan.input)
            is ProjectPlan -> {
                val projectPlan = filterPlan.input
                if (projectPlan.input is SortedTemporalJoinPlan) {
                    tryCollapseFilterAndProjectIntoJoin(
                        filterPlan.filterCondition,
                        projectPlan.namedColumnExpressions,
                        projectPlan.input
                    )
                } else {
                    super.visit(filterPlan)
                }
            }
            else -> super.visit(filterPlan)
        }
    }

    private fun tryCollapseFilterAndProjectIntoJoin(
        filterCondition: Expression,
        projections: List<NamedExpression>,
        sortedTemporalJoinPlan: SortedTemporalJoinPlan
    ): PhysicalQueryPlan? {
        // Substitute in all projections for ColumnLiterals in the filter condition, then try to push down the filter
        val substitutedFilter = ColumnReplacementPass.replaceColumnLiterals(filterCondition) { col ->
            projections.find { it.name == col.columnPath }!!.expr
        }
        val collapsedFilterJoin = collapseFilterIntoJoin(substitutedFilter, sortedTemporalJoinPlan) ?: return null
        // Apply the original projections after the pushed down filter and join
        return PhysicalQueryPlanBuilder.project(collapsedFilterJoin, projections)
    }

    private fun collapseFilterIntoJoin(
        filterCondition: Expression,
        sortedTemporalJoinPlan: SortedTemporalJoinPlan
    ): PhysicalQueryPlan? {
        // Find equality checks in the filter condition that compare one input to the other
        val (leftRightEqualities, remainingFilter) = extractLeftRightEqualityTerms(
            filterCondition,
            sortedTemporalJoinPlan.leftInput.schema.columns.filter { !it.isReserved }.map { it.identifier }.toSet(),
            sortedTemporalJoinPlan.rightInput.schema.columns.filter { !it.isReserved }.map { it.identifier }.toSet()
        )
        // Return early if there are no equalities that can be used as join conditions
        if (leftRightEqualities.isEmpty()) return null
        // Convert all complex expressions into concrete columns on the left and right inputs
        val (newColumns, columnEqualities) = convertLeftRightExpressionsToColumns(leftRightEqualities)
        // Rewrite the left and right inputs with added columns
        var rewrittenLeft = projectInput(sortedTemporalJoinPlan.leftInput, newColumns.first)
        var rewrittenRight = projectInput(sortedTemporalJoinPlan.rightInput, newColumns.second)
        // Determine equality sets: sets of left and right columns that must all be equal
        val existingJoinEqualities = sortedTemporalJoinPlan.leftJoinColumns.zip(sortedTemporalJoinPlan.rightJoinColumns)
            .map { it.first.column.columnPath to it.second.column.columnPath }
        val equalitySets = identifyEqualitySets(existingJoinEqualities + columnEqualities)
        // For each equality set: pick a left and right column to join on
        // and create equality conditions for the remaining columns
        val (leftJoinColumns, rightJoinColumns) =
            selectColumnsFromEqualitySets(equalitySets, rewrittenLeft.schema, rewrittenRight.schema).unzip()
        val (leftFilter, rightFilter) = createFilterConditionsFromEqualitySets(
            equalitySets, rewrittenLeft.schema, rewrittenRight.schema
        )
        // Push down the join conditions on the left and right inputs
        if (leftFilter != null) rewrittenLeft = PhysicalQueryPlanBuilder.filter(rewrittenLeft, leftFilter)
        if (rightFilter != null) rewrittenRight = PhysicalQueryPlanBuilder.filter(rewrittenRight, rightFilter)
        // Order the join columns to make sorting inputs as efficient as possible
        val (orderedLeftJoinColumns, orderedRightJoinColumns) = orderJoinColumns(leftJoinColumns, rightJoinColumns)
        // Sort the left and right inputs on the common join column order
        rewrittenLeft = PhysicalQueryPlanBuilder.sort(rewrittenLeft, orderedLeftJoinColumns)
        rewrittenRight = PhysicalQueryPlanBuilder.sort(rewrittenRight, orderedRightJoinColumns)
        // Join the sorted inputs
        val newJoin = PhysicalQueryPlanBuilder.sortedTemporalJoin(
            leftInput = rewrittenLeft,
            rightInput = rewrittenRight,
            leftJoinColumns = orderedLeftJoinColumns,
            rightJoinColumns = orderedRightJoinColumns,
            leftDropColumns = newColumns.first.map { it.name }.toSet() + sortedTemporalJoinPlan.leftDropColumns,
            rightDropColumns = newColumns.second.map { it.name }.toSet() + sortedTemporalJoinPlan.rightDropColumns
        )
        // Add an outer filter operation with part of the filter condition that could not be pushed down
        return if (remainingFilter != null) {
            PhysicalQueryPlanBuilder.filter(newJoin, remainingFilter)
        } else {
            newJoin
        }
    }

    private fun extractLeftRightEqualityTerms(
        filterCondition: Expression,
        leftColumns: Set<String>,
        rightColumns: Set<String>
    ): Pair<List<Pair<Expression, Expression>>, Expression?> {
        // Break the filter condition (an AND expression with 0 or more ANDs) into terms
        val terms = FilterConditionSeparation.collectAndExpressionTerms(filterCondition)
        // Find all terms checking for equality
        val (equalityTerms, nonEqualityTerms) = terms.partition { it is BinaryExpression && it.op == BinaryOp.EQUAL }
        // Determine for each equality if it compares an expression on the left join input
        // with an expression on the right join input
        val leftRightEqualityPairs = mutableListOf<Pair<Expression, Expression>>()
        val unmatchedEqualityTerms = mutableListOf<Expression>()
        for (expr in equalityTerms) {
            expr as BinaryExpression
            // Extract column literals from both inputs to the equality check
            val leftTermColumnLiterals = ASTUtils.findColumnLiterals(expr.lhs)
            val rightTermColumnLiterals = ASTUtils.findColumnLiterals(expr.rhs)
            // Determine for each set of literals if they use the left join input, right join input, or both
            val leftUsesOnlyLeft = leftTermColumnLiterals.all { it.columnPath in leftColumns }
            val leftUsesOnlyRight = leftTermColumnLiterals.all { it.columnPath in rightColumns }
            val rightUsesOnlyLeft = rightTermColumnLiterals.all { it.columnPath in leftColumns }
            val rightUsesOnlyRight = rightTermColumnLiterals.all { it.columnPath in rightColumns }
            // Return this equality if both input terms use a different join input
            if (leftUsesOnlyLeft && rightUsesOnlyRight) {
                leftRightEqualityPairs.add(expr.lhs to expr.rhs)
            } else if (leftUsesOnlyRight && rightUsesOnlyLeft) {
                leftRightEqualityPairs.add(expr.rhs to expr.lhs)
            } else {
                unmatchedEqualityTerms.add(expr)
            }
        }
        // Combine any unmatched equality terms into a single AND expression
        val remainingCondition = FilterConditionSeparation.mergeExpressions(
            nonEqualityTerms + unmatchedEqualityTerms
        )
        return leftRightEqualityPairs to remainingCondition
    }

    private fun identifyEqualitySets(columnEqualities: List<Pair<String, String>>): List<Set<String>> {
        if (columnEqualities.isEmpty()) return emptyList()
        // Track for each column which equality set it belongs to
        val columnNameToEqualitySet = mutableMapOf<String, Int>()
        val equalitySets = mutableListOf<MutableSet<String>>()
        // Go over each pair of equal columns and add them to an equality set
        for ((left, right) in columnEqualities) {
            // Check if either the left or right column is already in a set
            val leftSet = columnNameToEqualitySet[left]
            val rightSet = columnNameToEqualitySet[right]
            when {
                leftSet != null && rightSet != null -> {
                    // Merge sets if they are different
                    if (leftSet != rightSet) {
                        val setToMerge = maxOf(leftSet, rightSet)
                        val setToMergeInto = minOf(leftSet, rightSet)
                        equalitySets[setToMergeInto].addAll(equalitySets[setToMerge])
                        for (c in equalitySets[setToMerge]) {
                            columnNameToEqualitySet[c] = setToMergeInto
                        }
                        equalitySets[setToMerge].clear()
                    }
                }
                leftSet != null -> {
                    // Add right column to existing equality set for left column
                    equalitySets[leftSet].add(right)
                    columnNameToEqualitySet[right] = leftSet
                }
                rightSet != null -> {
                    // Add left column to existing equality set for right column
                    equalitySets[rightSet].add(left)
                    columnNameToEqualitySet[left] = rightSet
                }
                else -> {
                    // Create new equality set
                    val newSetId = equalitySets.size
                    equalitySets.add(mutableSetOf(left, right))
                    columnNameToEqualitySet[left] = newSetId
                    columnNameToEqualitySet[right] = newSetId
                }
            }
        }
        return equalitySets.filter { it.isNotEmpty() }
    }

    private fun selectColumnsFromEqualitySets(
        equalitySets: List<Set<String>>,
        leftSchema: TableSchema,
        rightSchema: TableSchema
    ): List<Pair<Column, Column>> {
        return equalitySets.map { equalitySet ->
            // Separate equality set into left and right columns
            val leftColumns = equalitySet.mapNotNull { leftSchema.column(it) }
            val rightColumns = equalitySet.mapNotNull { rightSchema.column(it) }
            // Select from each side the "optimal" column to join/sort on:
            // - Prefer a key column to avoid sorting on a value column
            // - Prefer a lower-indexed column for more likely joining on an already sorted column
            // TODO: Track for each query node which columns its input/output is sorted by
            val leftSelectedColumn = leftColumns.sortedBy { leftSchema.indexOfColumn(it) }.let { sortedColumns ->
                sortedColumns.firstOrNull { it.isKey } ?: sortedColumns.first()
            }
            val rightSelectedColumn = rightColumns.sortedBy { rightSchema.indexOfColumn(it) }.let { sortedColumns ->
                sortedColumns.firstOrNull { it.isKey } ?: sortedColumns.first()
            }
            leftSelectedColumn to rightSelectedColumn
        }
    }

    private fun createFilterConditionsFromEqualitySets(
        equalitySets: List<Set<String>>,
        leftSchema: TableSchema,
        rightSchema: TableSchema
    ): Pair<Expression?, Expression?> {
        val leftConditions = mutableListOf<Expression>()
        val rightConditions = mutableListOf<Expression>()
        for (equalitySet in equalitySets) {
            // Separate equality set into left and right columns
            val leftColumns = equalitySet.filter { leftSchema.column(it) != null }
            val rightColumns = equalitySet.filter { rightSchema.column(it) != null }
            // Create equality conditions for any set with two or more equal columns
            if (leftColumns.size >= 2) {
                val first = ColumnLiteral(leftColumns[0])
                for (i in 1 until leftColumns.size) {
                    leftConditions.add(BinaryExpression(first, ColumnLiteral(leftColumns[i]), BinaryOp.EQUAL))
                }
            }
            if (rightColumns.size >= 2) {
                val first = ColumnLiteral(rightColumns[0])
                for (i in 1 until rightColumns.size) {
                    rightConditions.add(BinaryExpression(first, ColumnLiteral(rightColumns[i]), BinaryOp.EQUAL))
                }
            }
        }
        // Merge equality checks into two conditions on the left and right table respectively
        return FilterConditionSeparation.mergeExpressions(leftConditions) to
                FilterConditionSeparation.mergeExpressions(rightConditions)
    }

    private fun convertLeftRightExpressionsToColumns(
        leftRightEqualities: List<Pair<Expression, Expression>>
    ): Pair<Pair<List<NamedExpression>, List<NamedExpression>>, List<Pair<String, String>>> {
        val (addedLeftColumns, leftNames) = convertExpressionsToColumns(leftRightEqualities.map { it.first })
        val (addedRightColumns, rightNames) = convertExpressionsToColumns(leftRightEqualities.map { it.second })
        return (addedLeftColumns to addedRightColumns) to leftNames.zip(rightNames)
    }

    private fun convertExpressionsToColumns(
        expressions: List<Expression>
    ): Pair<List<NamedExpression>, List<String>> {
        val addedColumns = mutableListOf<NamedExpression>()
        val columnNames = expressions.map { expr ->
            if (expr !is ColumnLiteral) {
                val existingColumn = addedColumns.firstOrNull { it.expr.isEquivalent(expr) }
                if (existingColumn == null) {
                    val newColumnName = PhysicalQueryPlanBuilder.generateColumnName("__join_")
                    addedColumns.add(NamedExpression(expr, newColumnName))
                    newColumnName
                } else {
                    existingColumn.name
                }
            } else {
                expr.columnPath
            }
        }
        return addedColumns to columnNames
    }

    private fun projectInput(input: PhysicalQueryPlan, newColumns: List<NamedExpression>): PhysicalQueryPlan {
        return if (newColumns.isNotEmpty()) {
            val namedInputColumns = input.schema.columns.map {
                NamedExpression(ColumnLiteral(it.identifier), it.identifier)
            }
            PhysicalQueryPlanBuilder.project(input, namedInputColumns + newColumns)
        } else {
            input
        }
    }

    private fun orderJoinColumns(
        leftColumns: List<Column>,
        rightColumns: List<Column>
    ): Pair<List<SortColumn>, List<SortColumn>> {
        // TODO: Find the optimal sort order across both inputs
        // TODO: Add information about how a plan is sorted as a property to PhysicalQueryPlan
        return leftColumns.map {
            SortColumn(ColumnLiteral(it.identifier), true)
        } to rightColumns.map {
            SortColumn(ColumnLiteral(it.identifier), true)
        }
    }

}