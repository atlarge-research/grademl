package science.atlarge.grademl.query.analysis

import science.atlarge.grademl.query.language.Expression
import science.atlarge.grademl.query.model.Column

object ASTAnalysis {

    fun analyzeExpression(expression: Expression, columns: List<Column>): Expression {
        var rewrittenExpression = expression.clone()
        rewrittenExpression = FunctionResolution.resolveFunctionCalls(rewrittenExpression)
        ColumnResolution.resolveColumns(rewrittenExpression, columns)
        TypeChecking.analyzeTypes(rewrittenExpression, columns)
        return PatternMatchCompilationPass.rewrite(rewrittenExpression)
    }

}