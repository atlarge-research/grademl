package science.atlarge.grademl.query.plan.logical

import science.atlarge.grademl.query.model.Columns
import science.atlarge.grademl.query.model.TableSchema

class TemporalJoinPlan(
    override val nodeId: Int,
    val leftInput: LogicalQueryPlan,
    val rightInput: LogicalQueryPlan
) : LogicalQueryPlan {

    override val schema: TableSchema

    override val children: List<LogicalQueryPlan>
        get() = listOf(leftInput, rightInput)

    init {
        // Verify that both the left and right inputs have start time and end time columns
        require(Columns.START_TIME in leftInput.schema.columns) { "Left input to temporal join does not have start time column" }
        require(Columns.END_TIME in leftInput.schema.columns) { "Left input to temporal join does not have end time column" }
        require(Columns.START_TIME in rightInput.schema.columns) { "Right input to temporal join does not have start time column" }
        require(Columns.END_TIME in rightInput.schema.columns) { "Right input to temporal join does not have end time column" }

        // Select reserved columns (start time, end time, etc.) and all non-reserved columns from left and right inputs
        val reservedColumns = Columns.RESERVED_COLUMNS
        val leftNotReservedColumns = leftInput.schema.columns - reservedColumns
        val rightNotReservedColumns = rightInput.schema.columns - reservedColumns

        // Check for duplicate column names
        val allColumnIdentifiers = mutableSetOf<String>()
        allColumnIdentifiers.addAll(reservedColumns.map { it.identifier })
        val leftOverlap = allColumnIdentifiers.intersect(leftNotReservedColumns.map { it.identifier })
        require(leftOverlap.isEmpty()) { "Found duplicate column identifier(s): ${leftOverlap.joinToString()}" }
        allColumnIdentifiers.addAll(leftNotReservedColumns.map { it.identifier })
        val rightOverlap = allColumnIdentifiers.intersect(rightNotReservedColumns.map { it.identifier })
        require(rightOverlap.isEmpty()) { "Found duplicate column identifier(s): ${rightOverlap.joinToString()}" }

        // Create the combined list of columns
        schema = TableSchema(reservedColumns + leftNotReservedColumns + rightNotReservedColumns)
    }

    override fun accept(logicalQueryPlanVisitor: LogicalQueryPlanVisitor) {
        logicalQueryPlanVisitor.visit(this)
    }

}