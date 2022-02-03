package science.atlarge.grademl.query.plan.logical

import science.atlarge.grademl.query.analysis.ASTAnalysis
import science.atlarge.grademl.query.analysis.ASTUtils
import science.atlarge.grademl.query.language.Expression
import science.atlarge.grademl.query.language.NamedExpression
import science.atlarge.grademl.query.model.Column
import science.atlarge.grademl.query.model.TableSchema

class AggregatePlan(
    override val nodeId: Int,
    val input: LogicalQueryPlan,
    groupByExpressions: List<Expression>,
    aggregateExpressions: List<NamedExpression>
) : LogicalQueryPlan {

    val groupExpressions = groupByExpressions.map { expression ->
        ASTAnalysis.analyzeExpression(expression, input.schema.columns)
    }
    val aggregateExpressions = aggregateExpressions.map { aggregateExpression ->
        NamedExpression(
            ASTAnalysis.analyzeExpression(aggregateExpression.expr, input.schema.columns),
            aggregateExpression.name
        )
    }

    override val schema = TableSchema(
        this.aggregateExpressions.map { aggregateExpression ->
            // Find all columns used in the expression
            val columnsUsed = ASTUtils.findColumnLiterals(aggregateExpression.expr)
            // Determine if all input columns are keys
            val allKeys = columnsUsed.all { input.schema.columns[it.columnIndex].isKey }
            // Create the new column, mark it as a key column iff all inputs are keys
            Column(aggregateExpression.name, aggregateExpression.expr.type, allKeys)
        }
    )

    override val children: List<LogicalQueryPlan>
        get() = listOf(input)

    override fun accept(logicalQueryPlanVisitor: LogicalQueryPlanVisitor) {
        logicalQueryPlanVisitor.visit(this)
    }

}