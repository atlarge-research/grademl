package science.atlarge.grademl.query.plan

import science.atlarge.grademl.query.language.ColumnLiteral
import science.atlarge.grademl.query.language.Expression
import science.atlarge.grademl.query.language.prettyPrint
import science.atlarge.grademl.query.plan.physical.*

object ExplainPhysicalPlan {

    fun explain(queryPlan: PhysicalQueryPlan): String {
        val visitor = Visitor()
        queryPlan.accept(visitor)
        return visitor.toString()
    }

    private class Visitor : PhysicalQueryPlanVisitor<Unit> {

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

        private fun recurse(innerPlan: PhysicalQueryPlan, isLastInnerPlan: Boolean) {
            currentDepth++
            isLastAtDepth.add(isLastInnerPlan)
            innerPlan.accept(this)
            isLastAtDepth.removeLast()
            currentDepth--
        }

        private fun Expression.prettyPrintWithFormat(): String {
            return prettyPrint(
                formatColumnLiteral = { "${it.columnPath}#${it.columnIndex}" }
            )
        }

        override fun visit(filterPlan: FilterPlan) {
            // Append one line with top-level description
            stringBuilder.indentSummary()
                .append("Filter[")
                .append(filterPlan.nodeId)
                .append("] - Condition: ")
                .append(filterPlan.filterCondition.prettyPrintWithFormat())
                .appendLine()
            // Explain input node
            recurse(filterPlan.input, true)
        }

        override fun visit(intervalMergingPlan: IntervalMergingPlan) {
            // Append one line with top-level description
            stringBuilder.indentSummary()
                .append("IntervalMerging[")
                .append(intervalMergingPlan.nodeId)
                .append("]")
                .appendLine()
            // Explain input node
            recurse(intervalMergingPlan.input, true)
        }

        override fun visit(linearTableScanPlan: LinearTableScanPlan) {
            // Append one line with top-level description
            stringBuilder.indentSummary()
                .append("LinearTableScan[")
                .append(linearTableScanPlan.nodeId)
                .append("] - Table: ")
                .append(linearTableScanPlan.tableName)
                .appendLine()
            // Append an additional line listing column names
            stringBuilder.indentDetail(false)
                .append("Columns: [")
            var isFirst = true
            for (c in linearTableScanPlan.schema.columns.withIndex()) {
                if (!isFirst) stringBuilder.append(", ")
                stringBuilder.append(c.value.identifier)
                    .append('#')
                    .append(c.index)
                isFirst = false
            }
            stringBuilder.append(']')
                .appendLine()
            // Append an additional line for the filter condition, if any
            if (linearTableScanPlan.filterCondition != null) {
                stringBuilder.indentDetail(false)
                    .append("Filter condition: ")
                    .append(linearTableScanPlan.filterCondition.prettyPrintWithFormat())
                    .appendLine()
            }
        }

        override fun visit(projectPlan: ProjectPlan) {
            // Append one line with top-level description
            stringBuilder.indentSummary()
                .append("Project[")
                .append(projectPlan.nodeId)
                .append("] - Columns: [")
            var isFirst = true
            for (c in projectPlan.schema.columns.withIndex()) {
                if (!isFirst) stringBuilder.append(", ")
                stringBuilder.append(c.value.identifier)
                    .append('#')
                    .append(c.index)
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
                    .append('#')
                    .append(i)
                    .append(" = ")
                    .append(columnExpr.prettyPrintWithFormat())
                    .appendLine()
            }
            // Explain input node
            recurse(projectPlan.input, true)
        }

        override fun visit(sortedAggregatePlan: SortedAggregatePlan) {
            // Append one line with top-level description
            stringBuilder.indentSummary()
                .append("SortedAggregate[")
                .append(sortedAggregatePlan.nodeId)
                .append("] - ")
            var isFirst = true
            if (sortedAggregatePlan.groupByColumns.isNotEmpty()) {
                stringBuilder.append("Group by: [")
                for (g in sortedAggregatePlan.groupByColumns) {
                    if (!isFirst) stringBuilder.append(", ")
                    stringBuilder.append(g)
                        .append('#')
                        .append(sortedAggregatePlan.input.schema.indexOfColumn(g)!!)
                    isFirst = false
                }
                stringBuilder.append("] - ")
            }
            stringBuilder.append("Columns: [")
            isFirst = true
            for (c in sortedAggregatePlan.schema.columns.withIndex()) {
                if (!isFirst) stringBuilder.append(", ")
                stringBuilder.append(c.value.identifier)
                    .append('#')
                    .append(c.index)
                isFirst = false
            }
            stringBuilder.append(']')
                .appendLine()
            // Append one line per projection expression
            for (i in sortedAggregatePlan.schema.columns.indices) {
                val columnName = sortedAggregatePlan.schema.columns[i].identifier
                val columnExpr = sortedAggregatePlan.columnExpressions[i]
                // Skip trivial column expressions
                if (columnExpr is ColumnLiteral && columnExpr.columnPath == columnName && columnExpr.columnIndex == i) continue
                stringBuilder.indentDetail(true)
                    .append("Column ")
                    .append(columnName)
                    .append('#')
                    .append(i)
                    .append(" = ")
                    .append(columnExpr.prettyPrintWithFormat())
                    .appendLine()
            }
            // Explain input node
            recurse(sortedAggregatePlan.input, true)
        }

        override fun visit(sortedTemporalAggregatePlan: SortedTemporalAggregatePlan) {
            // Append one line with top-level description
            stringBuilder.indentSummary()
                .append("SortedTemporalAggregate[")
                .append(sortedTemporalAggregatePlan.nodeId)
                .append("] - Group by: [_start_time, _end_time")
            for (g in sortedTemporalAggregatePlan.groupByColumns) {
                stringBuilder.append(", ")
                    .append(g)
                    .append('#')
                    .append(sortedTemporalAggregatePlan.input.schema.indexOfColumn(g)!!)
            }
            stringBuilder.append("] - Columns: [")
            var isFirst = true
            for (c in sortedTemporalAggregatePlan.schema.columns.withIndex()) {
                if (!isFirst) stringBuilder.append(", ")
                stringBuilder.append(c.value.identifier)
                    .append('#')
                    .append(c.index)
                isFirst = false
            }
            stringBuilder.append(']')
                .appendLine()
            // Append one line per projection expression
            for (i in sortedTemporalAggregatePlan.schema.columns.indices) {
                val columnName = sortedTemporalAggregatePlan.schema.columns[i].identifier
                val columnExpr = sortedTemporalAggregatePlan.columnExpressions[i]
                // Skip trivial column expressions
                if (columnExpr is ColumnLiteral && columnExpr.columnPath == columnName && columnExpr.columnIndex == i) continue
                stringBuilder.indentDetail(true)
                    .append("Column ")
                    .append(columnName)
                    .append('#')
                    .append(i)
                    .append(" = ")
                    .append(columnExpr.prettyPrintWithFormat())
                    .appendLine()
            }
            // Explain input node
            recurse(sortedTemporalAggregatePlan.input, true)
        }

        override fun visit(sortedTemporalJoinPlan: SortedTemporalJoinPlan) {
            // Append one line with top-level description
            stringBuilder.indentSummary()
                .append("SortedTemporalJoin[")
                .append(sortedTemporalJoinPlan.nodeId)
                .append("] - Columns: [")
            var isFirst = true
            for (c in sortedTemporalJoinPlan.schema.columns.withIndex()) {
                if (!isFirst) stringBuilder.append(", ")
                stringBuilder.append(c.value.identifier)
                    .append('#')
                    .append(c.index)
                isFirst = false
            }
            stringBuilder.append(']')
                .appendLine()
            // Append one line per join condition
            isFirst = true
            for (j in sortedTemporalJoinPlan.leftJoinColumns.indices) {
                stringBuilder.indentDetail(true)
                if (isFirst) stringBuilder.append("On: ")
                else stringBuilder.append("    ")
                stringBuilder.append(sortedTemporalJoinPlan.leftJoinColumns[j].column.prettyPrintWithFormat())
                    .append(" = ")
                    .append(sortedTemporalJoinPlan.rightJoinColumns[j].column.prettyPrintWithFormat())
                    .appendLine()
                isFirst = false
            }
            // Explain input nodes
            recurse(sortedTemporalJoinPlan.rightInput, false)
            recurse(sortedTemporalJoinPlan.leftInput, true)
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
                stringBuilder.append(c.column.prettyPrintWithFormat())
                    .append(' ')
                    .append(if (c.ascending) "ASCENDING" else "DESCENDING")
                isFirst = false
            }
            stringBuilder.append(']')
                .appendLine()
            // Explain input node
            recurse(sortPlan.input, true)
        }

        override fun toString(): String {
            return stringBuilder.dropLast(1).toString()
        }

    }

}