package science.atlarge.grademl.query.plan.physical

import science.atlarge.grademl.query.analysis.ASTAnalysis
import science.atlarge.grademl.query.execution.IndexedSortColumn
import science.atlarge.grademl.query.execution.QueryExecutionStatistics
import science.atlarge.grademl.query.execution.SortColumn
import science.atlarge.grademl.query.execution.operators.QueryOperator
import science.atlarge.grademl.query.execution.operators.SortedTemporalJoinOperator
import science.atlarge.grademl.query.language.ColumnLiteral
import science.atlarge.grademl.query.model.Columns
import science.atlarge.grademl.query.model.TableSchema

class SortedTemporalJoinPlan(
    override val nodeId: Int,
    val leftInput: PhysicalQueryPlan,
    val rightInput: PhysicalQueryPlan,
    leftJoinColumns: List<SortColumn>,
    rightJoinColumns: List<SortColumn>,
    leftDropColumns: Set<String>,
    rightDropColumns: Set<String>
) : PhysicalQueryPlan {

    val leftJoinColumns = leftJoinColumns.map {
        SortColumn(ASTAnalysis.analyzeExpression(it.column, leftInput.schema.columns) as ColumnLiteral, it.ascending)
    }
    val rightJoinColumns = rightJoinColumns.map {
        SortColumn(ASTAnalysis.analyzeExpression(it.column, rightInput.schema.columns) as ColumnLiteral, it.ascending)
    }

    val leftDropColumns = leftDropColumns.filter {
        it != Columns.START_TIME.identifier && it != Columns.END_TIME.identifier && leftInput.schema.column(it) != null
    }.toSet()
    val rightDropColumns = rightDropColumns.filter {
        it != Columns.START_TIME.identifier && it != Columns.END_TIME.identifier && rightInput.schema.column(it) != null
    }.toSet()

    private val leftOutputColumns = leftInput.schema.columns.filter {
        !it.isReserved && it.identifier !in leftDropColumns
    }
    private val rightOutputColumns = rightInput.schema.columns.filter {
        !it.isReserved && it.identifier !in rightDropColumns
    }

    override val schema = TableSchema(
        Columns.RESERVED_COLUMNS + leftOutputColumns + rightOutputColumns
    )

    override val children: List<PhysicalQueryPlan>
        get() = listOf(leftInput, rightInput)

    override fun toQueryOperator(): QueryOperator {
        lastOperator = SortedTemporalJoinOperator(
            leftInput.toQueryOperator(),
            rightInput.toQueryOperator(),
            schema,
            leftJoinColumns.map { IndexedSortColumn(it.column.columnIndex, it.ascending) },
            rightJoinColumns.map { IndexedSortColumn(it.column.columnIndex, it.ascending) },
            leftOutputColumns,
            rightOutputColumns
        )
        return lastOperator
    }

    private lateinit var lastOperator: SortedTemporalJoinOperator

    override fun collectLastExecutionStatisticsPerOperator(): Map<String, QueryExecutionStatistics> {
        return mapOf("SortedTemporalJoinOperator" to lastOperator.collectExecutionStatistics())
    }

    override fun <T> accept(visitor: PhysicalQueryPlanVisitor<T>): T {
        return visitor.visit(this)
    }

    override fun isEquivalent(other: PhysicalQueryPlan): Boolean {
        if (other !is SortedTemporalJoinPlan) return false
        if (leftJoinColumns != other.leftJoinColumns) return false
        if (rightJoinColumns != other.rightJoinColumns) return false
        if (leftDropColumns != other.leftDropColumns) return false
        if (rightDropColumns != other.rightDropColumns) return false
        return leftInput.isEquivalent(other.leftInput) && rightInput.isEquivalent(other.rightInput)
    }

}