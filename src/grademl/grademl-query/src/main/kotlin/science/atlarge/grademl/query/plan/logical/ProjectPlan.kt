package science.atlarge.grademl.query.plan.logical

import science.atlarge.grademl.query.analysis.ASTAnalysis
import science.atlarge.grademl.query.analysis.ASTUtils
import science.atlarge.grademl.query.language.Expression
import science.atlarge.grademl.query.language.NamedExpression
import science.atlarge.grademl.query.model.Column
import science.atlarge.grademl.query.model.TableSchema

class ProjectPlan(
    override val nodeId: Int,
    val input: LogicalQueryPlan,
    columnExpressions: List<NamedExpression>
) : LogicalQueryPlan {

    val columnExpressions: List<Expression>
    override val schema: TableSchema

    override val children: List<LogicalQueryPlan>
        get() = listOf(input)

    init {
        val analyzedColumnExpressions = mutableListOf<Expression>()
        val columns = mutableListOf<Column>()

        for (i in columnExpressions.indices) {
            // Analyze the project expression
            analyzedColumnExpressions.add(
                ASTAnalysis.analyzeExpression(
                    columnExpressions[i].expr,
                    input.schema.columns
                )
            )
            // Find all columns used in the expression
            val columnsUsed = ASTUtils.findColumnLiterals(analyzedColumnExpressions[i])
            // Determine if all input columns are keys
            val allKey = columnsUsed.all { input.schema.columns[it.columnIndex].isKey }
            // Create the new column, mark it as a key column iff all inputs are keys
            columns.add(Column(columnExpressions[i].name, analyzedColumnExpressions[i].type, allKey))
        }

        this.columnExpressions = analyzedColumnExpressions
        this.schema = TableSchema(columns)
    }

    override fun accept(logicalQueryPlanVisitor: LogicalQueryPlanVisitor) {
        logicalQueryPlanVisitor.visit(this)
    }

}