package science.atlarge.grademl.query.plan.physical

import science.atlarge.grademl.query.analysis.ASTAnalysis
import science.atlarge.grademl.query.execution.IndexedSortColumn
import science.atlarge.grademl.query.execution.QueryExecutionStatistics
import science.atlarge.grademl.query.execution.SortColumn
import science.atlarge.grademl.query.execution.operators.QueryOperator
import science.atlarge.grademl.query.execution.operators.SortOperator
import science.atlarge.grademl.query.language.ColumnLiteral
import science.atlarge.grademl.query.model.TableSchema

class SortPlan(
    override val nodeId: Int,
    val input: PhysicalQueryPlan,
    sortByColumns: List<SortColumn>
) : PhysicalQueryPlan {

    val sortByColumns: List<SortColumn>

    override val schema: TableSchema
    override val children: List<PhysicalQueryPlan>
        get() = listOf(input)

    init {
        // Sanity check sort columns and filter duplicates
        val uniqueSortColumns = mutableListOf<SortColumn>()
        val usedColumnNames = mutableListOf<String>()
        for (sc in sortByColumns) {
            val resolvedColumn = ASTAnalysis.analyzeExpression(sc.column, input.schema.columns) as ColumnLiteral
            if (!usedColumnNames.contains(resolvedColumn.columnPath)) {
                uniqueSortColumns.add(SortColumn(resolvedColumn, sc.ascending))
                usedColumnNames.add(resolvedColumn.columnPath)
            }
        }
        this.sortByColumns = uniqueSortColumns

        // Create a new schema, marking all sorted columns as keys
        this.schema = TableSchema(
            input.schema.columns.map { column ->
                if (!column.isKey && column.identifier in usedColumnNames) column.copy(isKey = true) else column
            }
        )
    }

    override fun toQueryOperator(): QueryOperator {
        lastOperator = SortOperator(
            input.toQueryOperator(), schema,
            preSortedColumns = emptyList(),
            remainingSortColumns = sortByColumns.map { IndexedSortColumn(it.column.columnIndex, it.ascending) }
        )
        return lastOperator
    }

    private lateinit var lastOperator: SortOperator

    override fun collectLastExecutionStatisticsPerOperator(): Map<String, QueryExecutionStatistics> {
        return mapOf("SortOperator" to lastOperator.collectExecutionStatistics())
    }

    override fun <T> accept(visitor: PhysicalQueryPlanVisitor<T>): T {
        return visitor.visit(this)
    }

    override fun isEquivalent(other: PhysicalQueryPlan): Boolean {
        if (other !is SortPlan) return false
        if (sortByColumns != other.sortByColumns) return false
        return input.isEquivalent(other.input)
    }

}