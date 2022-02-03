package science.atlarge.grademl.query.plan.physical

import science.atlarge.grademl.query.analysis.ASTUtils
import science.atlarge.grademl.query.language.ColumnLiteral
import science.atlarge.grademl.query.language.NamedExpression
import science.atlarge.grademl.query.model.Column
import science.atlarge.grademl.query.model.TableSchema

object PushDownProjectOptimization : OptimizationStrategy, PhysicalQueryPlanRewriter {

    override fun optimize(physicalQueryPlan: PhysicalQueryPlan): PhysicalQueryPlan? {
        return physicalQueryPlan.accept(this)
    }

    override fun visit(projectPlan: ProjectPlan): PhysicalQueryPlan? {
        // Determine if there are any expressions to push down
        // Pushing down column selection is done through DropColumnsOptimization
        val shouldPushDown = projectPlan.namedColumnExpressions.any {
            it.expr !is ColumnLiteral
        }
        if (!shouldPushDown) return super.visit(projectPlan)
        // Attempt to push down projections
        return when (projectPlan.input) {
            is FilterPlan -> pushPastFilter(projectPlan.namedColumnExpressions, projectPlan.input)
            is SortedTemporalJoinPlan -> pushPastSortedTemporalJoin(
                projectPlan.namedColumnExpressions, projectPlan.input
            )
            is SortPlan -> pushPastSort(projectPlan.namedColumnExpressions, projectPlan.input)
            else -> null
        } ?: super.visit(projectPlan)
    }

    private fun pushPastFilter(projections: List<NamedExpression>, filterPlan: FilterPlan): PhysicalQueryPlan? {
        // Determine which expressions to push down and what name to assign them
        val (inputProjections, outputProjections) =
            rewriteProjectionsToInputAndOutput(projections, filterPlan.input.schema)
        // Create projection of filter's input
        val newFilterInput = PhysicalQueryPlanBuilder.project(filterPlan.input, inputProjections)
        // Only push projections past a filter if they can be further pushed down
        val rewrittenFilterInput = optimize(newFilterInput) ?: return null
        // Apply filter
        val rewrittenFilter = PhysicalQueryPlanBuilder.filter(rewrittenFilterInput, filterPlan.filterCondition)
        // Apply final projections to ensure the output schema is identical to before the optimization
        return PhysicalQueryPlanBuilder.project(rewrittenFilter, outputProjections)
    }

    private fun pushPastSortedTemporalJoin(
        projections: List<NamedExpression>,
        sortedTemporalJoinPlan: SortedTemporalJoinPlan
    ): PhysicalQueryPlan? {
        // Determine which expressions to push down to the left or right join input
        val candidateProjections = projections.filter { it.expr !is ColumnLiteral }
        val leftColumns = sortedTemporalJoinPlan.leftInput.schema.columns
            .filter { !it.isReserved && it.identifier !in sortedTemporalJoinPlan.leftDropColumns }
            .map { it.identifier }
            .toSet()
        val rightColumns = sortedTemporalJoinPlan.rightInput.schema.columns
            .filter { !it.isReserved && it.identifier !in sortedTemporalJoinPlan.rightDropColumns }
            .map { it.identifier }
            .toSet()
        val leftProjections = mutableListOf<NamedExpression>()
        val rightProjections = mutableListOf<NamedExpression>()
        for (proj in candidateProjections) {
            val columns = ASTUtils.findColumnLiterals(proj.expr)
            if (columns.all { it.columnPath in leftColumns }) leftProjections.add(proj)
            else if (columns.all { it.columnPath in rightColumns }) rightProjections.add(proj)
        }
        // Return early if no projections can be pushed down
        if (leftProjections.isEmpty() && rightProjections.isEmpty()) return null
        // Assign a name to each projection
        val allInputColumns = sortedTemporalJoinPlan.leftInput.schema.columns +
                sortedTemporalJoinPlan.rightInput.schema.columns
        val renamedLeftProjections = renameProjections(leftProjections, allInputColumns)
        val renamedRightProjections = renameProjections(rightProjections, allInputColumns)
        // Push down left-only projections, if any
        val rewrittenLeftInput = if (renamedLeftProjections.isNotEmpty()) {
            val allLeftProjections = projectAllColumns(sortedTemporalJoinPlan.leftInput.schema) +
                    renamedLeftProjections
            PhysicalQueryPlanBuilder.project(sortedTemporalJoinPlan.leftInput, allLeftProjections)
        } else null
        // Push down right-only projections, if any
        val rewrittenRightInput = if (renamedRightProjections.isNotEmpty()) {
            val allRightProjections = projectAllColumns(sortedTemporalJoinPlan.rightInput.schema) +
                    renamedRightProjections
            PhysicalQueryPlanBuilder.project(sortedTemporalJoinPlan.rightInput, allRightProjections)
        } else null
        // Apply join
        val rewrittenJoin = PhysicalQueryPlanBuilder.sortedTemporalJoin(
            rewrittenLeftInput ?: sortedTemporalJoinPlan.leftInput,
            rewrittenRightInput ?: sortedTemporalJoinPlan.rightInput,
            sortedTemporalJoinPlan.leftJoinColumns,
            sortedTemporalJoinPlan.rightJoinColumns,
            sortedTemporalJoinPlan.leftDropColumns,
            sortedTemporalJoinPlan.rightDropColumns
        )
        // Apply final projections to ensure the output schema is identical to before the optimization
        val nameMap = leftProjections.zip(renamedLeftProjections).associate { it.first.name to it.second.name } +
                rightProjections.zip(renamedRightProjections).associate { it.first.name to it.second.name }
        val finalProjections = projections.map {
            nameMap[it.name]?.let { newName -> NamedExpression(ColumnLiteral(newName), it.name) } ?: it
        }
        return PhysicalQueryPlanBuilder.project(rewrittenJoin, finalProjections)
    }

    private fun pushPastSort(projections: List<NamedExpression>, sortPlan: SortPlan): PhysicalQueryPlan {
        // Determine which expressions to push down and what name to assign them
        val (inputProjections, outputProjections) =
            rewriteProjectionsToInputAndOutput(projections, sortPlan.input.schema)
        // TODO: Use heuristic to determine if pushing down projections is beneficial
        //       For now, always push projections past sort
        // Create projection of sort's input
        val newSortInput = PhysicalQueryPlanBuilder.project(sortPlan.input, inputProjections)
        // Apply sort
        val rewrittenSort = PhysicalQueryPlanBuilder.sort(newSortInput, sortPlan.sortByColumns)
        // Apply final projections to ensure the output schema is identical to before the optimization
        return PhysicalQueryPlanBuilder.project(rewrittenSort, outputProjections)
    }

    private fun rewriteProjectionsToInputAndOutput(
        projections: List<NamedExpression>,
        inputSchema: TableSchema
    ): Pair<List<NamedExpression>, List<NamedExpression>> {
        // Determine which expressions to push down and what name to assign them
        val projectionsToPushDown = projections.filter { it.expr !is ColumnLiteral }
        val renamedProjections = renameProjections(projectionsToPushDown, inputSchema.columns)
        val nameMap = projectionsToPushDown.zip(renamedProjections).associate { it.first.name to it.second.name }
        // Compile input projections (copy columns and add pushed down expressions)
        val inputProjections = projectAllColumns(inputSchema) + renamedProjections
        // Compile output projections
        val outputProjections = projections.map {
            if (it.expr is ColumnLiteral) it else NamedExpression(ColumnLiteral(nameMap[it.name]!!), it.name)
        }
        return inputProjections to outputProjections
    }

    private fun renameProjections(
        projections: List<NamedExpression>,
        existingColumns: List<Column>
    ): List<NamedExpression> {
        val existingNames = existingColumns.map { it.identifier }.toSet()
        return projections.map {
            it.copy(
                name = if (it.name in existingNames) {
                    PhysicalQueryPlanBuilder.generateColumnName("__proj_")
                } else {
                    it.name
                }
            )
        }
    }

    private fun projectAllColumns(schema: TableSchema): List<NamedExpression> {
        return schema.columns.map {
            NamedExpression(ColumnLiteral(it.identifier), it.identifier)
        }
    }

}