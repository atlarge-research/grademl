package science.atlarge.grademl.query.plan.logical

import science.atlarge.grademl.query.language.Expression
import science.atlarge.grademl.query.model.TableSchema

class FilterPlan(
    override val nodeId: Int,
    val input: LogicalQueryPlan,
    val condition: Expression
) : LogicalQueryPlan {

    override val schema: TableSchema
        get() = input.schema

    override val children: List<LogicalQueryPlan>
        get() = listOf(input)

    override fun accept(logicalQueryPlanVisitor: LogicalQueryPlanVisitor) {
        logicalQueryPlanVisitor.visit(this)
    }

}