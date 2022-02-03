package science.atlarge.grademl.query.plan

import science.atlarge.grademl.query.language.ColumnLiteral
import science.atlarge.grademl.query.language.prettyPrint
import science.atlarge.grademl.query.plan.logical.*

object ExplainLogicalPlan {

    fun explain(queryPlan: LogicalQueryPlan): String {
        val visitor = Visitor()
        queryPlan.accept(visitor)
        return visitor.toString()
    }

    private class Visitor : LogicalQueryPlanVisitor {

        private val stringBuilder = StringBuilder()
        private var currentDepth = 0
        private val isLastAtDepth = mutableListOf<Boolean>()

        private fun StringBuilder.partialIndent(): StringBuilder {
            for (i in 0 until currentDepth - 1) {
                if (isLastAtDepth[i]) append("   ") else append("|  ")
            }
            return this
        }

        private fun StringBuilder.indentSummary(): StringBuilder {
            return partialIndent().also {
                if (currentDepth > 0) append("+- ")
            }
        }

        private fun StringBuilder.indentDetail(hasChildren: Boolean): StringBuilder {
            for (i in 0 until currentDepth) {
                if (isLastAtDepth[i]) append("   ") else append("|  ")
            }
            if (!hasChildren) append("   ") else append("|  ")
            return append("   ")
        }

        private fun recurse(innerPlan: LogicalQueryPlan, isLastInnerPlan: Boolean) {
            currentDepth++
            isLastAtDepth.add(isLastInnerPlan)
            innerPlan.accept(this)
            isLastAtDepth.removeLast()
            currentDepth--
        }

        override fun visit(aggregatePlan: AggregatePlan) {
            // Append one line with top-level description
            stringBuilder.indentSummary()
                .append("Aggregate[")
                .append(aggregatePlan.nodeId)
                .append("] - ")
            if (aggregatePlan.groupExpressions.isNotEmpty()) {
                stringBuilder.append("Group by: [")
                var isFirst = true
                for (g in aggregatePlan.groupExpressions) {
                    if (!isFirst) stringBuilder.append(", ")
                    stringBuilder.append(g.prettyPrint())
                    isFirst = false
                }
                stringBuilder.append("], ")
            }
            stringBuilder.append("Columns: [")
            var isFirst = true
            for (c in aggregatePlan.schema.columns) {
                if (!isFirst) stringBuilder.append(", ")
                stringBuilder.append(c.identifier)
                isFirst = false
            }
            stringBuilder.append(']')
                .appendLine()
            // Append one line per projection expression
            for (i in aggregatePlan.schema.columns.indices) {
                val columnName = aggregatePlan.schema.columns[i].identifier
                val columnExpr = aggregatePlan.aggregateExpressions[i].expr
                // Skip trivial column expressions
                if (columnExpr is ColumnLiteral && columnExpr.columnPath == columnName) continue
                stringBuilder.indentDetail(true)
                    .append("Column ")
                    .append(columnName)
                    .append(" = ")
                    .append(columnExpr.prettyPrint())
                    .appendLine()
            }
            // Explain input node
            recurse(aggregatePlan.input, true)
        }

        override fun visit(filterPlan: FilterPlan) {
            // Append one line with top-level description
            stringBuilder.indentSummary()
                .append("Filter[")
                .append(filterPlan.nodeId)
                .append("] - Condition: ")
                .append(filterPlan.condition.prettyPrint())
                .appendLine()
            // Explain input node
            recurse(filterPlan.input, true)
        }

        override fun visit(projectPlan: ProjectPlan) {
            // Append one line with top-level description
            stringBuilder.indentSummary()
                .append("Project[")
                .append(projectPlan.nodeId)
                .append("] - Columns: [")
            var isFirst = true
            for (c in projectPlan.schema.columns) {
                if (!isFirst) stringBuilder.append(", ")
                stringBuilder.append(c.identifier)
                isFirst = false
            }
            stringBuilder.append(']')
                .appendLine()
            // Append one line per projection expression
            for (i in projectPlan.schema.columns.indices) {
                val columnName = projectPlan.schema.columns[i].identifier
                val columnExpr = projectPlan.columnExpressions[i]
                // Skip trivial column expressions
                if (columnExpr is ColumnLiteral && columnExpr.columnPath == columnName) continue
                stringBuilder.indentDetail(true)
                    .append("Column ")
                    .append(columnName)
                    .append(" = ")
                    .append(columnExpr.prettyPrint())
                    .appendLine()
            }
            // Explain input node
            recurse(projectPlan.input, true)
        }

        override fun visit(scanTablePlan: ScanTablePlan) {
            // Append one line with top-level description
            stringBuilder.indentSummary()
                .append("ScanTable[")
                .append(scanTablePlan.nodeId)
                .append("] - Table: ")
                .append(scanTablePlan.tableName)
                .appendLine()
            // Append an additional line listing column names
            stringBuilder.indentDetail(false)
                .append("Columns: [")
            var isFirst = true
            for (c in scanTablePlan.schema.columns) {
                if (!isFirst) stringBuilder.append(", ")
                stringBuilder.append(c.identifier)
                isFirst = false
            }
            stringBuilder.append(']')
                .appendLine()
        }

        override fun visit(sortPlan: SortPlan) {
            // Append one line with top-level description
            stringBuilder.indentSummary()
                .append("Sort[")
                .append(sortPlan.nodeId)
                .append("] - By: [")
            var isFirst = true
            for (c in sortPlan.sortByColumns) {
                if (!isFirst) stringBuilder.append(", ")
                stringBuilder.append(c.column.columnPath)
                    .append(' ')
                    .append(if (c.ascending) "ASCENDING" else "DESCENDING")
                isFirst = false
            }
            stringBuilder.append(']')
                .appendLine()
            // Explain input node
            recurse(sortPlan.input, true)
        }

        override fun visit(temporalJoinPlan: TemporalJoinPlan) {
            // Append one line with top-level description
            stringBuilder.indentSummary()
                .append("TemporalJoin[")
                .append(temporalJoinPlan.nodeId)
                .append("] - Columns: [")
            var isFirst = true
            for (c in temporalJoinPlan.schema.columns) {
                if (!isFirst) stringBuilder.append(", ")
                stringBuilder.append(c.identifier)
                isFirst = false
            }
            stringBuilder.append(']')
                .appendLine()
            // Explain input nodes
            recurse(temporalJoinPlan.leftInput, false)
            recurse(temporalJoinPlan.rightInput, true)
        }

        override fun toString(): String {
            return stringBuilder.dropLast(1).toString()
        }

    }

}