package science.atlarge.grademl.query.execution

import science.atlarge.grademl.query.language.Type
import science.atlarge.grademl.query.model.TimeSeriesIterator

object TablePrinter {

    fun print(timeSeriesIterator: TimeSeriesIterator, limit: Int? = null) {
        val showFirst = limit ?: 100
        val showLast = if (limit == null) 100 else 0
        val maxLines = limit ?: Int.MAX_VALUE
        val header = listOf("ROW", "TS") + timeSeriesIterator.schema.columns.map { it.identifier }
        val lines = Array(showFirst + showLast) { Array(timeSeriesIterator.schema.columns.size + 2) { "" } }
        var lineCount = 0
        var lineIndex = 0
        var timeSeriesCount = 0
        while (lineCount < maxLines && timeSeriesIterator.loadNext()) {
            timeSeriesCount++

            val rowIter = timeSeriesIterator.currentTimeSeries.rowIterator()
            while (lineCount < maxLines && rowIter.loadNext()) {
                val row = rowIter.currentRow
                lineCount++
                if (lineIndex >= lines.size) continue

                lines[lineIndex][0] = lineCount.toString()
                lines[lineIndex][1] = timeSeriesCount.toString()
                for (c in rowIter.schema.columns.withIndex()) {
                    val value = when (c.value.type) {
                        Type.UNDEFINED -> "UNDEFINED"
                        Type.BOOLEAN -> row.getBoolean(c.index).toString()
                        Type.NUMERIC -> row.getNumeric(c.index).toString()
                        Type.STRING -> row.getString(c.index)
                    }
                    lines[lineIndex][c.index + 2] = value
                }

                lineIndex++
                if (lineIndex == lines.size) {
                    lineIndex = showFirst
                }
            }
        }

        val startLines = when {
            lineCount <= lines.size -> lines.slice(0 until lineCount)
            showFirst > 0 -> lines.slice(0 until showFirst)
            else -> null
        }

        val endLines = if (lineCount > lines.size && showLast > 0) {
            lines.slice(lineIndex until lines.size) + lines.slice(showFirst until lineIndex)
        } else {
            null
        }

        val columnWidths = header.indices.map { c ->
            val maxValueWidth = if (lineCount == 0) 0 else
                (0 until minOf(lineCount, lines.size)).maxOf { lines[it][c].length }
            maxOf(header[c].length, maxValueWidth)
        }

        println("-".repeat(columnWidths.sum() + 3 * header.size + 1))
        for (c in header.indices) {
            print("| ${header[c].padEnd(columnWidths[c])} ")
        }
        println("|")
        if (startLines != null) {
            println("-".repeat(columnWidths.sum() + 3 * header.size + 1))
            for (l in startLines) {
                for (c in l.indices) {
                    print("| ${l[c].padEnd(columnWidths[c])} ")
                }
                println("|")
            }
        }

        if (lineCount > lines.size) {
            println("~".repeat(columnWidths.sum() + 3 * header.size + 1))
        } else if (lineCount > 0) {
            println("-".repeat(columnWidths.sum() + 3 * header.size + 1))
        }

        if (endLines != null) {
            for (l in endLines) {
                for (c in l.indices) {
                    print("| ${l[c].padEnd(columnWidths[c])} ")
                }
                println("|")
            }
            println("-".repeat(columnWidths.sum() + 3 * header.size + 1))
        }

        println("Showing ${minOf(lineCount, lines.size)} of $lineCount rows.")
    }

}