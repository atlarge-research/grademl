package science.atlarge.grademl.query.execution

import science.atlarge.grademl.query.execution.IntTypes.toInt
import science.atlarge.grademl.query.model.TimeSeriesIterator
import java.nio.file.Path

object TableExporter {

    fun export(outputPath: Path, data: TimeSeriesIterator, limit: Int? = null): Int {
        val maxLines = limit ?: Int.MAX_VALUE
        val columnTypes = data.schema.columns.map { it.type.toInt() }
        var rowsWritten = 0
        outputPath.toFile().printWriter().use { writer ->
            // Write a header to the file
            for (columnIndex in data.schema.columns.indices) {
                if (columnIndex != 0) writer.print('\t')
                writer.print(data.schema.columns[columnIndex].identifier)
            }
            writer.println()
            // Write each row as a separate line
            while (rowsWritten < maxLines && data.loadNext()) {
                val rowIterator = data.currentTimeSeries.rowIterator()
                while (rowsWritten < maxLines && rowIterator.loadNext()) {
                    // Write the next row column-by-column
                    val row = rowIterator.currentRow
                    for (columnIndex in columnTypes.indices) {
                        if (columnIndex != 0) writer.print('\t')
                        when (columnTypes[columnIndex]) {
                            IntTypes.TYPE_BOOLEAN -> {
                                writer.print(if (row.getBoolean(columnIndex)) "TRUE" else "FALSE")
                            }
                            IntTypes.TYPE_NUMERIC -> {
                                writer.print(row.getNumeric(columnIndex))
                            }
                            IntTypes.TYPE_STRING -> {
                                writer.print('"')
                                writer.print(row.getString(columnIndex))
                                writer.print('"')
                            }
                            else -> throw IllegalArgumentException(
                                "Cannot export values of type ${data.schema.columns[columnIndex].type}"
                            )
                        }
                    }
                    writer.println()
                    rowsWritten++
                }
            }
        }
        return rowsWritten
    }

}