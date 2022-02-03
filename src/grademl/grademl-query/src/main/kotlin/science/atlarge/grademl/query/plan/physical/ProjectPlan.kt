package science.atlarge.grademl.query.plan.physical

import science.atlarge.grademl.query.analysis.ASTAnalysis
import science.atlarge.grademl.query.analysis.ASTUtils
import science.atlarge.grademl.query.execution.QueryExecutionStatistics
import science.atlarge.grademl.query.execution.operators.ProjectOperator
import science.atlarge.grademl.query.execution.operators.QueryOperator
import science.atlarge.grademl.query.execution.toPhysicalExpression
import science.atlarge.grademl.query.language.Expression
import science.atlarge.grademl.query.language.NamedExpression
import science.atlarge.grademl.query.model.Column
import science.atlarge.grademl.query.model.TableSchema

class ProjectPlan(
    override val nodeId: Int,
    val input: PhysicalQueryPlan,
    columnExpressions: List<NamedExpression>
) : PhysicalQueryPlan {

    val columnExpressions: List<Expression>
    val namedColumnExpressions: List<NamedExpression>
    override val schema: TableSchema

    init {
        val newColumnExpressions = mutableListOf<Expression>()
        val newNamedColumnExpressions = mutableListOf<NamedExpression>()
        val newColumns = mutableListOf<Column>()

        // For each column expression provided: analyze the expression, create a named expression, and create a column
        columnExpressions.forEachIndexed { index, columnExpression ->
            val rewrittenExpression = ASTAnalysis.analyzeExpression(columnExpression.expr, input.schema.columns)
            newColumnExpressions.add(rewrittenExpression)
            newNamedColumnExpressions.add(NamedExpression(rewrittenExpression, columnExpression.name))
            // Determine if the new column is a key
            val columnsUsed = ASTUtils.findColumnLiterals(rewrittenExpression)
            val allKeys = columnsUsed.all { input.schema.columns[it.columnIndex].isKey }
            newColumns.add(Column(columnExpression.name, rewrittenExpression.type, allKeys))
        }

        this.columnExpressions = newColumnExpressions
        this.namedColumnExpressions = newNamedColumnExpressions
        this.schema = TableSchema(newColumns)
    }

    override val children: List<PhysicalQueryPlan>
        get() = listOf(input)

    override fun toQueryOperator(): QueryOperator {
        lastOperator = ProjectOperator(
            input.toQueryOperator(),
            schema,
            columnExpressions.map { it.toPhysicalExpression() }
        )
        return lastOperator
    }

    private lateinit var lastOperator: ProjectOperator

    override fun collectLastExecutionStatisticsPerOperator(): Map<String, QueryExecutionStatistics> {
        return mapOf("ProjectOperator" to lastOperator.collectExecutionStatistics())
    }

    override fun <T> accept(visitor: PhysicalQueryPlanVisitor<T>): T {
        return visitor.visit(this)
    }

    override fun isEquivalent(other: PhysicalQueryPlan): Boolean {
        if (other !is ProjectPlan) return false
        if (columnExpressions.size != other.columnExpressions.size) return false
        if (columnExpressions.indices.any {
                namedColumnExpressions[it].name != other.namedColumnExpressions[it].name ||
                        !namedColumnExpressions[it].expr.isEquivalent(other.namedColumnExpressions[it].expr)
            }) return false
        return input.isEquivalent(other.input)
    }

}