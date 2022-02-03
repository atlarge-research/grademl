package science.atlarge.grademl.query.analysis

import science.atlarge.grademl.query.language.ColumnLiteral
import science.atlarge.grademl.query.language.Expression

object ColumnReplacementPass {

    fun replaceColumnLiterals(expression: Expression, replacementFn: (ColumnLiteral) -> Expression): Expression {
        return object : ExpressionRewritePass(rewriteOriginalOfCustomExpression = true) {
            override fun rewrite(e: ColumnLiteral) = replacementFn(e)
        }.rewriteExpression(expression)
    }

}