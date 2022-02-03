package science.atlarge.grademl.query.plan.logical

import science.atlarge.grademl.query.analysis.ASTAnalysis
import science.atlarge.grademl.query.execution.SortColumn
import science.atlarge.grademl.query.language.ColumnLiteral
import science.atlarge.grademl.query.model.TableSchema

class SortPlan(
    override val nodeId: Int,
    val input: LogicalQueryPlan,
    sortByColumns: List<SortColumn>
) : LogicalQueryPlan {

    val sortByColumns: List<SortColumn>

    override val schema: TableSchema
        get() = input.schema
    override val children: List<LogicalQueryPlan>
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
    }

    override fun accept(logicalQueryPlanVisitor: LogicalQueryPlanVisitor) {
        logicalQueryPlanVisitor.visit(this)
    }

}