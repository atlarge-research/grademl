package science.atlarge.grademl.query.analysis

import science.atlarge.grademl.query.language.ColumnLiteral
import science.atlarge.grademl.query.language.Expression

object ColumnAliasingPass {

    fun aliasColumns(originalExpression: Expression, newNamesOfOldColumns: Map<String, String>): Expression {
        return object : ExpressionRewritePass(rewriteOriginalOfCustomExpression = true) {
            override fun rewrite(e: ColumnLiteral): Expression {
                return if (e.columnPath in newNamesOfOldColumns) {
                    ColumnLiteral(newNamesOfOldColumns[e.columnPath]!!)
                } else {
                    e
                }
            }
        }.rewriteExpression(originalExpression)
    }

}