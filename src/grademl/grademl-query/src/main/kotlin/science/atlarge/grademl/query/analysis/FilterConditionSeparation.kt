package science.atlarge.grademl.query.analysis

import science.atlarge.grademl.query.language.BinaryExpression
import science.atlarge.grademl.query.language.BinaryOp
import science.atlarge.grademl.query.language.Expression
import science.atlarge.grademl.query.language.Type

object FilterConditionSeparation : ExpressionRewritePass() {

    fun splitFilterConditionByColumns(e: Expression, availableColumnsPerSplit: List<Set<Int>>): Result {
        require(e.type == Type.BOOLEAN) { "Filter condition must be a BOOLEAN expression" }
        val allAvailableColumns = availableColumnsPerSplit.fold(emptySet<Int>()) { a, b -> a + b }
        require(allAvailableColumns.size == availableColumnsPerSplit.sumOf { it.size }) {
            "Available column splits must not overlap"
        }

        val simplifiedCondition = BooleanSimplification.simplify(e)
        val filterTerms = collectAndExpressionTerms(simplifiedCondition)

        val matchedTermsPerSplit = availableColumnsPerSplit.map { mutableListOf<Expression>() }
        val unmatchedTerms = mutableListOf<Expression>()
        for (term in filterTerms) {
            val matchingSplit = findMatchingSplit(term, availableColumnsPerSplit)
            if (matchingSplit != null) {
                matchedTermsPerSplit[matchingSplit].add(term)
            } else {
                unmatchedTerms.add(term)
            }
        }

        return Result(
            matchedTermsPerSplit.map { mergeExpressions(it) },
            mergeExpressions(unmatchedTerms)
        )
    }

    fun collectAndExpressionTerms(e: Expression): List<Expression> {
        val termsToCheck = mutableListOf<Expression>()
        val termsFound = mutableListOf<Expression>()
        termsToCheck.add(e)
        while (termsToCheck.isNotEmpty()) {
            val term = termsToCheck.removeLast()
            if (term is BinaryExpression && term.op == BinaryOp.AND) {
                termsToCheck.add(term.lhs)
                termsToCheck.add(term.rhs)
            } else {
                termsFound.add(term)
            }
        }
        return termsFound
    }

    private fun findMatchingSplit(e: Expression, columnSplits: List<Set<Int>>): Int? {
        val columnsInExpression = ASTUtils.findColumnLiterals(e).map { it.columnIndex }.toSet()
        val splitWithAllColumns = columnSplits.indexOfFirst { it.containsAll(columnsInExpression) }
        return if (splitWithAllColumns >= 0) splitWithAllColumns else null
    }

    fun mergeExpressions(expressions: Iterable<Expression>): Expression? {
        var result: Expression? = null
        for (e in expressions) {
            if (result == null) result = e
            else result = BinaryExpression(result, e, BinaryOp.AND).apply { type = Type.BOOLEAN }
        }
        return result
    }

    class Result(
        val filterExpressionPerSplit: List<Expression?>,
        val remainingFilterExpression: Expression?
    )

}