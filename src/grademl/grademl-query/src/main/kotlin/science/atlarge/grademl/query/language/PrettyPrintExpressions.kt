package science.atlarge.grademl.query.language

object PrettyPrintExpressions {

    fun print(
        expression: Expression,
        formatColumnLiteral: ((ColumnLiteral) -> String)? = null
    ): String {
        val visitor = Visitor(
            formatColumnLiteral = formatColumnLiteral ?: { it.columnPath }
        )
        expression.accept(visitor)
        return visitor.toString()
    }

    private class Visitor(
        private val formatColumnLiteral: ((ColumnLiteral) -> String)
    ) : ExpressionVisitor {

        private val stringBuilder = StringBuilder()
        private var isNested = false

        private fun recurse(expression: Expression, nested: Boolean) {
            val previousIsNested = isNested
            isNested = nested
            expression.accept(this)
            isNested = previousIsNested
        }

        override fun toString(): String {
            return stringBuilder.toString()
        }

        override fun visit(e: BooleanLiteral) {
            stringBuilder.append(if (e.value) "TRUE" else "FALSE")
        }

        override fun visit(e: NumericLiteral) {
            stringBuilder.append(e.value)
        }

        override fun visit(e: StringLiteral) {
            stringBuilder.append('"')
                .append(e.value)
                .append('"')
        }

        override fun visit(e: ColumnLiteral) {
            stringBuilder.append(formatColumnLiteral(e))
        }

        override fun visit(e: UnaryExpression) {
            when (e.op) {
                UnaryOp.NOT -> {
                    stringBuilder.append("NOT ")
                    recurse(e, true)
                }
            }
        }

        override fun visit(e: BinaryExpression) {
            if (isNested) stringBuilder.append('(')
            recurse(e.lhs, true)
            stringBuilder.append(
                when (e.op) {
                    BinaryOp.ADD -> " + "
                    BinaryOp.SUBTRACT -> " - "
                    BinaryOp.MULTIPLY -> " * "
                    BinaryOp.DIVIDE -> " / "
                    BinaryOp.AND -> " AND "
                    BinaryOp.OR -> " OR "
                    BinaryOp.EQUAL -> " == "
                    BinaryOp.NOT_EQUAL -> " != "
                    BinaryOp.APPROX_EQUAL -> " ~= "
                    BinaryOp.NOT_APPROX_EQUAL -> " !~ "
                    BinaryOp.GREATER -> " > "
                    BinaryOp.GREATER_EQUAL -> " >= "
                    BinaryOp.SMALLER -> " < "
                    BinaryOp.SMALLER_EQUAL -> " <= "
                }
            )
            recurse(e.rhs, true)
            if (isNested) stringBuilder.append(')')
        }

        override fun visit(e: FunctionCallExpression) {
            stringBuilder.append(e.functionName)
                .append('(')
            e.arguments.forEachIndexed { index, expression ->
                if (index > 0) stringBuilder.append(", ")
                recurse(expression, false)
            }
            stringBuilder.append(')')
        }

        override fun visit(e: AbstractExpression) {
            e.originalExpression.accept(this)
        }

    }

}

fun Expression.prettyPrint(
    formatColumnLiteral: ((ColumnLiteral) -> String)? = null
): String = PrettyPrintExpressions.print(this, formatColumnLiteral)