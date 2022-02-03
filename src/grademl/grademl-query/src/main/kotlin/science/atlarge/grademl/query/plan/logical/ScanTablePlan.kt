package science.atlarge.grademl.query.plan.logical

import science.atlarge.grademl.query.model.Table
import science.atlarge.grademl.query.model.TableSchema

class ScanTablePlan(
    override val nodeId: Int,
    val table: Table,
    val tableName: String
) : LogicalQueryPlan {

    override val schema: TableSchema
        get() = table.schema

    override val children: List<LogicalQueryPlan>
        get() = emptyList()

    override fun accept(logicalQueryPlanVisitor: LogicalQueryPlanVisitor) {
        logicalQueryPlanVisitor.visit(this)
    }

}