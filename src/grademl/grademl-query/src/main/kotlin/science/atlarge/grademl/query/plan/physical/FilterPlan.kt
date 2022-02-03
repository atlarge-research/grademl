package science.atlarge.grademl.query.plan.physical

import science.atlarge.grademl.query.analysis.ASTAnalysis
import science.atlarge.grademl.query.analysis.FilterConditionSeparation
import science.atlarge.grademl.query.execution.BooleanPhysicalExpression
import science.atlarge.grademl.query.execution.QueryExecutionStatistics
import science.atlarge.grademl.query.execution.operators.FilterOperator
import science.atlarge.grademl.query.execution.operators.QueryOperator
import science.atlarge.grademl.query.execution.toPhysicalExpression
import science.atlarge.grademl.query.language.Expression
import science.atlarge.grademl.query.language.Type
import science.atlarge.grademl.query.model.TableSchema

class FilterPlan(
    override val nodeId: Int,
    val input: PhysicalQueryPlan,
    filterCondition: Expression
) : PhysicalQueryPlan {

    val filterCondition = ASTAnalysis.analyzeExpression(filterCondition, input.schema.columns)

    override val schema: TableSchema
        get() = input.schema
    override val children: List<PhysicalQueryPlan>
        get() = listOf(input)

    init {
        require(filterCondition.type == Type.BOOLEAN) { "Filter condition must be a boolean expression" }
    }

    override fun toQueryOperator(): QueryOperator {
        // Split filter condition into key-only and remaining conditions
        val keyColumnIndices = input.schema.columns
            .mapIndexedNotNull { index, column -> if (column.isKey) index else null }
            .toSet()
        val splitCondition = FilterConditionSeparation
            .splitFilterConditionByColumns(filterCondition, listOf(keyColumnIndices))
        // Convert part of condition expression using only key columns to physical expression for time-series filter
        val timeSeriesCondition = splitCondition.filterExpressionPerSplit[0]
        val physicalTimeSeriesCondition = if (timeSeriesCondition != null) {
            timeSeriesCondition.toPhysicalExpression() as BooleanPhysicalExpression
        } else {
            BooleanPhysicalExpression.ALWAYS_TRUE
        }
        // Convert remaining part of condition expression to physical expression for row filter
        val rowCondition = splitCondition.remainingFilterExpression
        val physicalRowCondition = if (rowCondition != null) {
            rowCondition.toPhysicalExpression() as BooleanPhysicalExpression
        } else {
            BooleanPhysicalExpression.ALWAYS_TRUE
        }
        lastOperator = FilterOperator(
            input.toQueryOperator(),
            physicalTimeSeriesCondition,
            physicalRowCondition
        )
        return lastOperator
    }

    private lateinit var lastOperator: FilterOperator

    override fun collectLastExecutionStatisticsPerOperator(): Map<String, QueryExecutionStatistics> {
        return mapOf("FilterOperator" to lastOperator.collectExecutionStatistics())
    }

    override fun <T> accept(visitor: PhysicalQueryPlanVisitor<T>): T {
        return visitor.visit(this)
    }

    override fun isEquivalent(other: PhysicalQueryPlan): Boolean {
        if (other !is FilterPlan) return false
        if (!filterCondition.isEquivalent(other.filterCondition)) return false
        return input.isEquivalent(other.input)
    }

}