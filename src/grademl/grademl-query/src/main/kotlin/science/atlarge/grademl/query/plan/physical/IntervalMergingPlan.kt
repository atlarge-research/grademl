package science.atlarge.grademl.query.plan.physical

import science.atlarge.grademl.query.execution.QueryExecutionStatistics
import science.atlarge.grademl.query.execution.operators.IntervalMergingOperator
import science.atlarge.grademl.query.execution.operators.QueryOperator
import science.atlarge.grademl.query.model.TableSchema

class IntervalMergingPlan(
    override val nodeId: Int,
    val input: PhysicalQueryPlan
) : PhysicalQueryPlan {

    override val schema: TableSchema
        get() = input.schema
    override val children: List<PhysicalQueryPlan>
        get() = listOf(input)

    override fun toQueryOperator(): QueryOperator {
        lastOperator = IntervalMergingOperator(input.toQueryOperator())
        return lastOperator
    }

    private lateinit var lastOperator: IntervalMergingOperator

    override fun collectLastExecutionStatisticsPerOperator(): Map<String, QueryExecutionStatistics> {
        return mapOf("IntervalMergingOperator" to lastOperator.collectExecutionStatistics())
    }

    override fun <T> accept(visitor: PhysicalQueryPlanVisitor<T>) = visitor.visit(this)

    override fun isEquivalent(other: PhysicalQueryPlan): Boolean {
        if (other !is IntervalMergingPlan) return false
        return input.isEquivalent(other.input)
    }

}