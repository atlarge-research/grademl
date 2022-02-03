package science.atlarge.grademl.query.plan

import science.atlarge.grademl.query.analysis.ASTAnalysis
import science.atlarge.grademl.query.analysis.ASTUtils
import science.atlarge.grademl.query.execution.SortColumn
import science.atlarge.grademl.query.execution.VirtualTable
import science.atlarge.grademl.query.language.*
import science.atlarge.grademl.query.model.Columns
import science.atlarge.grademl.query.model.Table
import science.atlarge.grademl.query.plan.logical.*
import science.atlarge.grademl.query.plan.logical.FilterPlan
import science.atlarge.grademl.query.plan.logical.ProjectPlan
import science.atlarge.grademl.query.plan.logical.SortPlan
import science.atlarge.grademl.query.plan.physical.*

object QueryPlanner {

    fun createLogicalPlanFromSelect(selectStatement: SelectStatement, tables: Map<String, Table>): LogicalQueryPlan {
        return createLogicalPlanFromSelect(selectStatement, tables, LogicalQueryPlanBuilder())
    }

    private fun createLogicalPlanFromSelect(
        selectStatement: SelectStatement,
        tables: Map<String, Table>,
        builder: LogicalQueryPlanBuilder
    ): LogicalQueryPlan {
        // Parse the from clause
        require(selectStatement.from.tables.isNotEmpty()) { "Query must have at least one input" }
        require(selectStatement.from.aliases.size == 1 || selectStatement.from.aliases.none { it.isBlank() }) {
            "All inputs of a join must have an alias"
        }
        require(selectStatement.from.aliases.toSet().size == selectStatement.from.aliases.size) {
            "All inputs of a join must have a unique alias"
        }
        // Find the input data source(s)
        val inputPlans = selectStatement.from.tables.map { tableExpression ->
            when (tableExpression) {
                is TableExpression.Query ->
                    createLogicalPlanFromSelect(tableExpression.tableDefinition, tables, builder)
                is TableExpression.NamedTable -> {
                    val tableName = tableExpression.tableName
                    val table = tables[tableName] ?: throw IllegalArgumentException(
                        "Table $tableName does not exist"
                    )
                    if (table is VirtualTable) {
                        table.logicalPlan
                    } else {
                        builder.scan(table, tableName)
                    }
                }
            }
        }

        // Alias the input(s)
        val aliasesInputPlans = inputPlans.mapIndexed { index, input ->
            val alias = selectStatement.from.aliases[index]
            if (alias.isBlank()) input
            else {
                val reservedColumns = input.schema.columns.filter { it in Columns.RESERVED_COLUMNS }
                    .map { NamedExpression(ColumnLiteral(it.identifier), it.identifier) }
                val columnExpressions = input.schema.columns.map { column ->
                    NamedExpression(ColumnLiteral(column.identifier), "$alias.${column.identifier}")
                }
                builder.project(input, reservedColumns + columnExpressions)
            }
        }

        // Join the inputs (if applicable)
        val joinedInputPlan = aliasesInputPlans.reduce { leftInput, rightInput ->
            builder.temporalJoin(leftInput, rightInput)
        }

        // Apply the where clause
        val filteredInputPlan = if (selectStatement.where == null) {
            joinedInputPlan
        } else {
            val filterExpression = ASTAnalysis.analyzeExpression(
                selectStatement.where.conditionExpression, joinedInputPlan.schema.columns
            )
            builder.filter(joinedInputPlan, filterExpression)
        }

        // Parse the group by clause
        val groupByColumns = if (selectStatement.groupBy == null) emptyList() else selectStatement.groupBy.columns
        // Parse the select clause
        val resolvedSelectTerms = selectStatement.select.terms
            .flatMap {
                when (it) {
                    is SelectTerm.Anonymous -> listOf(it.expression to null)
                    is SelectTerm.Named -> listOf(it.namedExpression.expr to it.namedExpression.name)
                    SelectTerm.Wildcard -> filteredInputPlan.schema.columns.map { column ->
                        ColumnLiteral(column.identifier) to column.identifier
                    }
                }
            }
            .map {
                ASTAnalysis.analyzeExpression(it.first, filteredInputPlan.schema.columns) to it.second
            }
            .mapIndexed { index, (expression, name) ->
                // Assign names to anonymous select terms
                NamedExpression(
                    expression, when {
                        name != null -> name
                        expression is ColumnLiteral -> expression.columnPath
                        else -> "_$index"
                    }
                )
            }

        // Check for duplicate names
        require(resolvedSelectTerms.map { it.name }.toSet().size == resolvedSelectTerms.size) {
            "Found duplicate column names in SELECT: [${
                resolvedSelectTerms.groupBy { it.name }.filter { it.value.size > 1 }.keys.joinToString()
            }]"
        }

        // Check if the query has a group-by clause or any aggregating functions
        val isAggregatingSelect = groupByColumns.isNotEmpty() ||
                resolvedSelectTerms.any { ASTUtils.findFunctionCalls(it.expr).isNotEmpty() }

        // Create an AggregatePlan for an aggregating queries, or a ProjectPlan otherwise
        val projectedOutputPlan = if (isAggregatingSelect) {
            builder.aggregate(filteredInputPlan, groupByColumns, resolvedSelectTerms)
        } else {
            builder.project(filteredInputPlan, resolvedSelectTerms)
        }

        // Sort if needed, then return the compiled query plan
        return if (selectStatement.orderBy != null) {
            val sortColumns = selectStatement.orderBy.columns.zip(selectStatement.orderBy.ascending)
                .map { (col, asc) -> SortColumn(col, asc) }
            builder.sort(projectedOutputPlan, sortColumns)
        } else {
            projectedOutputPlan
        }
    }

    fun convertLogicalToPhysicalPlan(logicalQueryPlan: LogicalQueryPlan): PhysicalQueryPlan {
        val logicalPlanVisitor = object : LogicalQueryPlanVisitor {
            private lateinit var lastResult: PhysicalQueryPlan

            fun rewrite(logicalQueryPlan: LogicalQueryPlan): PhysicalQueryPlan {
                logicalQueryPlan.accept(this)
                return lastResult
            }

            override fun visit(aggregatePlan: AggregatePlan) {
                val input = rewrite(aggregatePlan.input)

                // Check if any group-by expressions are not simple columns
                val groupByProjections = mutableListOf<NamedExpression>()
                val rewrittenGroupByLiterals = aggregatePlan.groupExpressions.mapIndexed { index, expression ->
                    if (expression is ColumnLiteral) expression
                    else {
                        val projection = NamedExpression(expression, "__groupby_${aggregatePlan.nodeId}_$index")
                        groupByProjections.add(projection)
                        ColumnLiteral(projection.name).apply {
                            type = projection.expr.type
                            columnIndex = groupByProjections.lastIndex + input.schema.columns.size
                        }
                    }
                }

                // If any group-by expressions have been rewritten, add a projection node in the query plan
                val projectedInput = if (groupByProjections.isNotEmpty()) {
                    val selectInputColumns = input.schema.columns.mapIndexed { index, column ->
                        val columnLit = ColumnLiteral(column.identifier).apply {
                            type = column.type
                            columnIndex = index
                        }
                        NamedExpression(columnLit, column.identifier)
                    }
                    PhysicalQueryPlanBuilder.project(input, selectInputColumns + groupByProjections)
                } else {
                    input
                }

                // Determine if the group-by uses time columns
                val nonTemporalGroupByLiterals = rewrittenGroupByLiterals.filter {
                    it.columnPath != Columns.START_TIME.identifier && it.columnPath != Columns.END_TIME.identifier
                }
                val isTemporal = rewrittenGroupByLiterals.size != nonTemporalGroupByLiterals.size

                // Sort the input by the group-by columns
                val sortedInput = if (nonTemporalGroupByLiterals.isNotEmpty()) {
                    PhysicalQueryPlanBuilder.sort(projectedInput, nonTemporalGroupByLiterals.map {
                        SortColumn(it, true)
                    })
                } else {
                    projectedInput
                }

                // Apply the final aggregations
                lastResult = if (isTemporal) {
                    PhysicalQueryPlanBuilder.sortedTemporalAggregate(
                        sortedInput,
                        nonTemporalGroupByLiterals.map { it.columnPath },
                        aggregatePlan.aggregateExpressions
                    )
                } else {
                    PhysicalQueryPlanBuilder.sortedAggregate(
                        sortedInput,
                        nonTemporalGroupByLiterals.map { it.columnPath },
                        aggregatePlan.aggregateExpressions
                    )
                }
            }

            override fun visit(filterPlan: FilterPlan) {
                val input = rewrite(filterPlan.input)
                lastResult = PhysicalQueryPlanBuilder.filter(input, filterPlan.condition)
            }

            override fun visit(projectPlan: ProjectPlan) {
                val input = rewrite(projectPlan.input)
                val namedExpressions = projectPlan.schema.columns.mapIndexed { index, column ->
                    NamedExpression(projectPlan.columnExpressions[index], column.identifier)
                }
                lastResult = PhysicalQueryPlanBuilder.project(input, namedExpressions)
            }

            override fun visit(scanTablePlan: ScanTablePlan) {
                lastResult = PhysicalQueryPlanBuilder.linearScan(scanTablePlan.table, scanTablePlan.tableName)
            }

            override fun visit(sortPlan: SortPlan) {
                val input = rewrite(sortPlan.input)
                lastResult = PhysicalQueryPlanBuilder.sort(input, sortPlan.sortByColumns)
            }

            override fun visit(temporalJoinPlan: TemporalJoinPlan) {
                val leftInput = rewrite(temporalJoinPlan.leftInput)
                val rightInput = rewrite(temporalJoinPlan.rightInput)
                lastResult = PhysicalQueryPlanBuilder.sortedTemporalJoin(
                    leftInput, rightInput, emptyList(), emptyList(), emptySet(), emptySet()
                )
            }
        }
        return logicalPlanVisitor.rewrite(logicalQueryPlan)
    }

    private val defaultOptimizationStrategies = listOf(
        // First, remove interval merging operators to allow other optimizations to occur
        DropIntervalMergingOptimization,
        // Push down projections
        PushDownProjectOptimization,
        // Eagerly drop columns to reduce data volume
        DropColumnsOptimization,
        // Merge adjacent projections
        CollapseProjectsOptimization,
        // Split filter conditions to filter early and often
        PushDownFilterOptimization,
        // Merge adjacent filter and join operations into a join-on operation
        FilterAsJoinConditionOptimization,
        // Finally, insert interval merging operators
        InsertIntervalMergingOptimization
    )

    private const val MAX_OPTIMIZATION_ITERATIONS = 100

    fun optimizePhysicalPlan(
        physicalQueryPlan: PhysicalQueryPlan,
        optimizationStrategies: List<OptimizationStrategy> = defaultOptimizationStrategies
    ): PhysicalQueryPlan {
        var optimizedPlan = physicalQueryPlan
        var previousOptimizedPlan: PhysicalQueryPlan
        var iterationsCompleted = 0
        do {
            previousOptimizedPlan = optimizedPlan
            for (s in optimizationStrategies) {
                optimizedPlan = s.optimizeOrReturn(optimizedPlan)
            }
            iterationsCompleted++
            if (iterationsCompleted == MAX_OPTIMIZATION_ITERATIONS) return optimizedPlan
        } while (!optimizedPlan.isEquivalent(previousOptimizedPlan))
        return optimizedPlan
    }

}