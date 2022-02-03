package science.atlarge.grademl.query.plan.physical

import science.atlarge.grademl.query.analysis.ASTAnalysis
import science.atlarge.grademl.query.analysis.ASTUtils
import science.atlarge.grademl.query.analysis.AggregateFunctionDecomposition
import science.atlarge.grademl.query.execution.QueryExecutionStatistics
import science.atlarge.grademl.query.execution.operators.QueryOperator
import science.atlarge.grademl.query.execution.operators.SortedTemporalAggregateOperator
import science.atlarge.grademl.query.execution.toPhysicalExpression
import science.atlarge.grademl.query.language.Expression
import science.atlarge.grademl.query.language.NamedExpression
import science.atlarge.grademl.query.model.Column
import science.atlarge.grademl.query.model.Columns
import science.atlarge.grademl.query.model.TableSchema

class SortedTemporalAggregatePlan(
    override val nodeId: Int,
    val input: PhysicalQueryPlan,
    val groupByColumns: List<String>,
    columnExpressions: List<NamedExpression>
) : PhysicalQueryPlan {

    val columnExpressions: List<Expression>
    val namedColumnExpressions: List<NamedExpression>
    override val schema: TableSchema

    private val groupByColumnIndices = this.groupByColumns.map { name ->
        require(name != Columns.START_TIME.identifier && name != Columns.END_TIME.identifier) {
            "Group-by columns must not include start or end time columns"
        }
        input.schema.indexOfColumn(name) ?: throw IllegalArgumentException(
            "Cannot group by column that does not exist in input: \"$name\""
        )
    }

    override val children: List<PhysicalQueryPlan>
        get() = listOf(input)

    init {
        val newColumnExpressions = mutableListOf<Expression>()
        val newNamedColumnExpressions = mutableListOf<NamedExpression>()
        val newColumns = mutableListOf<Column>()
        val groupByColumnIndexSet = groupByColumnIndices.toSet()

        // For each column expression provided: analyze the expression, create a named expression, and create a column
        for (columnExpression in columnExpressions) {
            val rewrittenExpression = ASTAnalysis.analyzeExpression(columnExpression.expr, input.schema.columns)
            newColumnExpressions.add(rewrittenExpression)
            newNamedColumnExpressions.add(NamedExpression(rewrittenExpression, columnExpression.name))
            // Determine if the new column is a key
            val columnsUsed = ASTUtils.findColumnLiterals(rewrittenExpression)
            val allKeys = columnsUsed.all { it.columnIndex in groupByColumnIndexSet }
            newColumns.add(Column(columnExpression.name, rewrittenExpression.type, allKeys))
        }

        this.columnExpressions = newColumnExpressions
        this.namedColumnExpressions = newNamedColumnExpressions
        this.schema = TableSchema(newColumns)
    }

    override fun toQueryOperator(): QueryOperator {
        // Decompose aggregate expressions into aggregate functions with arguments expression and final projections
        val aggregateDecomposition = AggregateFunctionDecomposition.decompose(
            columnExpressions, input.schema.columns
        )
        aggregateDecomposition.aggregateFunctions

        lastOperator = SortedTemporalAggregateOperator(
            input.toQueryOperator(),
            schema,
            groupByColumnIndices,
            aggregateDecomposition.aggregateFunctions,
            aggregateDecomposition.aggregateFunctionTypes,
            aggregateDecomposition.aggregateFunctionArguments.map { it.map(Expression::toPhysicalExpression) },
            aggregateDecomposition.aggregateFunctionArguments.map { it.map(Expression::type) },
            aggregateDecomposition.aggregateColumns,
            aggregateDecomposition.rewrittenExpressions.map(Expression::toPhysicalExpression)
        )
        return lastOperator
    }

    private lateinit var lastOperator: SortedTemporalAggregateOperator

    override fun collectLastExecutionStatisticsPerOperator(): Map<String, QueryExecutionStatistics> {
        return mapOf("SortedTemporalAggregateOperator" to lastOperator.collectExecutionStatistics())
    }

    override fun <T> accept(visitor: PhysicalQueryPlanVisitor<T>): T {
        return visitor.visit(this)
    }

    override fun isEquivalent(other: PhysicalQueryPlan): Boolean {
        if (other !is SortedAggregatePlan) return false
        if (groupByColumns != other.groupByColumns) return false
        if (columnExpressions.size != other.columnExpressions.size) return false
        if (columnExpressions.indices.any {
                namedColumnExpressions[it].name != other.namedColumnExpressions[it].name ||
                        !namedColumnExpressions[it].expr.isEquivalent(other.namedColumnExpressions[it].expr)
            }) return false
        return input.isEquivalent(other.input)
    }

}