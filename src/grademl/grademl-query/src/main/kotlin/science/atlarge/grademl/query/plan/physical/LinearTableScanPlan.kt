package science.atlarge.grademl.query.plan.physical

import science.atlarge.grademl.query.analysis.ASTAnalysis
import science.atlarge.grademl.query.analysis.ASTUtils
import science.atlarge.grademl.query.execution.FilterableTable
import science.atlarge.grademl.query.execution.QueryExecutionStatistics
import science.atlarge.grademl.query.execution.operators.LinearTableScanOperator
import science.atlarge.grademl.query.execution.operators.QueryOperator
import science.atlarge.grademl.query.language.Expression
import science.atlarge.grademl.query.model.Table
import science.atlarge.grademl.query.model.TableSchema

class LinearTableScanPlan(
    override val nodeId: Int,
    val table: Table,
    val tableName: String,
    filterCondition: Expression?
) : PhysicalQueryPlan {

    val filterCondition = filterCondition?.let { ASTAnalysis.analyzeExpression(it, table.schema.columns) }
    val filterableColumns = if (table is FilterableTable) table.filterableColumns else emptyList()

    override val schema: TableSchema
        get() = table.schema
    override val children: List<PhysicalQueryPlan>
        get() = emptyList()

    init {
        if (filterCondition != null) {
            require(table is FilterableTable) { "Cannot filter a Table that does not implement FilterableTable" }
            val filteredColumns = ASTUtils.findColumnLiterals(filterCondition).map { it.columnPath }
            val filterableColumnsSet = filterableColumns.map { it.identifier }.toSet()
            require(filteredColumns.all { it in filterableColumnsSet }) {
                "Cannot filter by columns not in filterableColumns"
            }
        }
    }

    override fun toQueryOperator(): QueryOperator {
        val filteredTable = if (filterCondition != null) {
            (table as FilterableTable).filteredBy(filterCondition)
        } else {
            table
        }
        lastOperator = LinearTableScanOperator(filteredTable)
        return lastOperator
    }

    private lateinit var lastOperator: LinearTableScanOperator

    override fun collectLastExecutionStatisticsPerOperator(): Map<String, QueryExecutionStatistics> {
        return mapOf("LinearTableScanOperator" to lastOperator.collectExecutionStatistics())
    }

    override fun <T> accept(visitor: PhysicalQueryPlanVisitor<T>): T {
        return visitor.visit(this)
    }

    override fun isEquivalent(other: PhysicalQueryPlan): Boolean {
        if (other !is LinearTableScanPlan) return false
        if (tableName != other.tableName) return false
        return when {
            filterCondition != null && other.filterCondition != null ->
                filterCondition.isEquivalent(other.filterCondition)
            filterCondition == null && other.filterCondition == null -> true
            else -> false
        }
    }

}