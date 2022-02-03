package science.atlarge.grademl.query.execution

import science.atlarge.grademl.query.model.Table
import science.atlarge.grademl.query.model.TableSchema
import science.atlarge.grademl.query.model.TimeSeriesIterator
import science.atlarge.grademl.query.plan.logical.LogicalQueryPlan
import science.atlarge.grademl.query.plan.physical.PhysicalQueryPlan

class VirtualTable(
    val logicalPlan: LogicalQueryPlan,
    val physicalPlan: PhysicalQueryPlan
) : Table {

    override val schema: TableSchema
        get() = logicalPlan.schema

    override fun timeSeriesIterator(): TimeSeriesIterator {
        return physicalPlan.toQueryOperator().execute()
    }

}