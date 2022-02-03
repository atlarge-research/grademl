package science.atlarge.grademl.query.plan.physical

import science.atlarge.grademl.query.execution.SortColumn
import science.atlarge.grademl.query.language.Expression
import science.atlarge.grademl.query.language.NamedExpression
import science.atlarge.grademl.query.model.Table

object PhysicalQueryPlanBuilder {

    private var nextNodeId: Int = 1

    fun generateColumnName(prefix: String): String {
        val nodeId = nextNodeId++
        return prefix + nodeId
    }

    fun filter(input: PhysicalQueryPlan, condition: Expression): PhysicalQueryPlan {
        val nodeId = nextNodeId++
        return FilterPlan(nodeId, input, condition)
    }

    fun linearScan(table: Table, tableName: String, filter: Expression? = null): PhysicalQueryPlan {
        val nodeId = nextNodeId++
        return LinearTableScanPlan(nodeId, table, tableName, filter)
    }

    fun intervalMerging(input: PhysicalQueryPlan): PhysicalQueryPlan {
        val nodeId = nextNodeId++
        return IntervalMergingPlan(nodeId, input)
    }

    fun project(input: PhysicalQueryPlan, columnExpressions: List<NamedExpression>): PhysicalQueryPlan {
        val nodeId = nextNodeId++
        return ProjectPlan(nodeId, input, columnExpressions)
    }

    fun sortedAggregate(
        input: PhysicalQueryPlan,
        groupByColumns: List<String>,
        columnExpressions: List<NamedExpression>
    ): PhysicalQueryPlan {
        val nodeId = nextNodeId++
        return SortedAggregatePlan(nodeId, input, groupByColumns, columnExpressions)
    }

    fun sortedTemporalAggregate(
        input: PhysicalQueryPlan,
        groupByColumns: List<String>,
        columnExpressions: List<NamedExpression>
    ): PhysicalQueryPlan {
        val nodeId = nextNodeId++
        return SortedTemporalAggregatePlan(nodeId, input, groupByColumns, columnExpressions)
    }

    fun sortedTemporalJoin(
        leftInput: PhysicalQueryPlan,
        rightInput: PhysicalQueryPlan,
        leftJoinColumns: List<SortColumn>,
        rightJoinColumns: List<SortColumn>,
        leftDropColumns: Set<String>,
        rightDropColumns: Set<String>
    ): PhysicalQueryPlan {
        val nodeId = nextNodeId++
        return SortedTemporalJoinPlan(
            nodeId, leftInput, rightInput, leftJoinColumns, rightJoinColumns, leftDropColumns, rightDropColumns
        )
    }

    fun sort(input: PhysicalQueryPlan, sortByColumns: List<SortColumn>): PhysicalQueryPlan {
        val nodeId = nextNodeId++
        return SortPlan(nodeId, input, sortByColumns)
    }

}