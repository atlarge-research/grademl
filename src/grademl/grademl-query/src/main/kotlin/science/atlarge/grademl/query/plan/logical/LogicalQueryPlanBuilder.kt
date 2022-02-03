package science.atlarge.grademl.query.plan.logical

import science.atlarge.grademl.query.execution.SortColumn
import science.atlarge.grademl.query.language.Expression
import science.atlarge.grademl.query.language.NamedExpression
import science.atlarge.grademl.query.model.Table

class LogicalQueryPlanBuilder {

    private var nextNodeId: Int = 1

    fun aggregate(
        input: LogicalQueryPlan,
        groupByExpressions: List<Expression>,
        aggregateExpressions: List<NamedExpression>
    ): LogicalQueryPlan {
        val nodeId = nextNodeId++
        return AggregatePlan(nodeId, input, groupByExpressions, aggregateExpressions)
    }

    fun filter(input: LogicalQueryPlan, condition: Expression): LogicalQueryPlan {
        val nodeId = nextNodeId++
        return FilterPlan(nodeId, input, condition)
    }

    fun project(input: LogicalQueryPlan, columnExpressions: List<NamedExpression>): LogicalQueryPlan {
        val nodeId = nextNodeId++
        return ProjectPlan(nodeId, input, columnExpressions)
    }

    fun scan(table: Table, tableName: String): LogicalQueryPlan {
        val nodeId = nextNodeId++
        return ScanTablePlan(nodeId, table, tableName)
    }

    fun sort(input: LogicalQueryPlan, sortColumns: List<SortColumn>): LogicalQueryPlan {
        val nodeId = nextNodeId++
        return SortPlan(nodeId, input, sortColumns)
    }

    fun temporalJoin(leftInput: LogicalQueryPlan, rightInput: LogicalQueryPlan): LogicalQueryPlan {
        val nodeId = nextNodeId++
        return TemporalJoinPlan(nodeId, leftInput, rightInput)
    }

}