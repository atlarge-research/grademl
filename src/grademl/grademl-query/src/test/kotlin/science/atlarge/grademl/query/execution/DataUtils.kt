package science.atlarge.grademl.query.execution

import science.atlarge.grademl.query.model.*
import kotlin.test.assertEquals

object DataUtils {
    fun sortGeneratedRows(
        orderedInput: List<List<GeneratedRow>>,
        columnsToSort: List<String>
    ): List<List<GeneratedRow>> {
        var reorderedInput = orderedInput
        for (c in columnsToSort.asReversed()) {
            when (c) {
                "k1" -> reorderedInput = reorderedInput.sortedBy { it[0].k1 }
                "-k1" -> reorderedInput = reorderedInput.sortedByDescending { it[0].k1 }
                "k2" -> reorderedInput = reorderedInput.sortedBy { it[0].k2 }
                "-k2" -> reorderedInput = reorderedInput.sortedByDescending { it[0].k2 }
                "v1" -> reorderedInput = reorderedInput.flatMap { it.groupBy { it.v1 }.map { it.value } }
                    .sortedBy { it[0].v1 }
                "-v1" -> reorderedInput = reorderedInput.flatMap { it.groupBy { it.v1 }.map { it.value } }
                    .sortedByDescending { it[0].v1 }
                "v2" -> reorderedInput = reorderedInput.flatMap { it.groupBy { it.v2 }.map { it.value } }
                    .sortedBy { it[0].v2 }
                "-v2" -> reorderedInput = reorderedInput.flatMap { it.groupBy { it.v2 }.map { it.value } }
                    .sortedByDescending { it[0].v2 }
                "v3" -> reorderedInput = reorderedInput.flatMap { it.groupBy { it.v3 }.map { it.value } }
                    .sortedBy { it[0].v3 }
                "-v3" -> reorderedInput = reorderedInput.flatMap { it.groupBy { it.v3 }.map { it.value } }
                    .sortedByDescending { it[0].v3 }
                "v4" -> reorderedInput = reorderedInput.flatMap { it.groupBy { it.v4 }.map { it.value } }
                    .sortedBy { it[0].v4 }
                "-v4" -> reorderedInput = reorderedInput.flatMap { it.groupBy { it.v4 }.map { it.value } }
                    .sortedByDescending { it[0].v4 }
            }
        }
        return reorderedInput
    }

    fun compareOrderedOutput(expected: List<List<GeneratedRow>>, produced: List<List<GeneratedRow>>) {
        // Check number of time series
        assertEquals(expected.size, produced.size, "Produced incorrect number of time series")
        // Check number of rows per time series
        expected.forEachIndexed { index, expectedRows ->
            val producedRows = produced[index]
            assertEquals(expectedRows.size, producedRows.size, "Produced incorrect number of rows")
        }
        // Check rows in time series
        expected.forEachIndexed { index, expectedRows ->
            val producedRows = produced[index]
            expectedRows.forEachIndexed { rowIndex, expectedRow ->
                val producedRow = producedRows[rowIndex]
                assertEquals(expectedRow, producedRow, "Produced incorrect row")
            }
        }
    }

    fun TimeSeriesIterator.toGeneratedRows(): List<List<GeneratedRow>> {
        val result = mutableListOf<List<GeneratedRow>>()
        while (loadNext()) {
            val rows = mutableListOf<GeneratedRow>()
            val rowIter = currentTimeSeries.rowIterator()
            while (rowIter.loadNext()) {
                rows.add(GeneratedRow.fromQueryEngineRow(rowIter.currentRow))
            }
            result.add(rows)
        }
        return result
    }

    fun List<List<GeneratedRow>>.toTimeSeriesIterator() = object : AbstractTimeSeriesIterator(DataGenerator.schema) {
        private val mappedData = this@toTimeSeriesIterator.map { ts -> ts.map { it.asQueryEngineRow() } }
        private var currentTimeSeriesIndex = -1

        override val currentTimeSeries: TimeSeries = object : TimeSeries {
            override val schema: TableSchema = DataGenerator.schema

            override fun getBoolean(columnIndex: Int): Boolean {
                require(schema.columns[columnIndex].isKey)
                return mappedData[currentTimeSeriesIndex][0].getBoolean(columnIndex)
            }

            override fun getNumeric(columnIndex: Int): Double {
                require(schema.columns[columnIndex].isKey)
                return mappedData[currentTimeSeriesIndex][0].getNumeric(columnIndex)
            }

            override fun getString(columnIndex: Int): String {
                require(schema.columns[columnIndex].isKey)
                return mappedData[currentTimeSeriesIndex][0].getString(columnIndex)
            }

            override fun rowIterator(): RowIterator = object : AbstractRowIterator(DataGenerator.schema) {
                private val timeSeriesIndex = currentTimeSeriesIndex
                private var currentRowIndex = -1

                override val currentRow: Row
                    get() = mappedData[timeSeriesIndex][currentRowIndex]

                override fun internalLoadNext(): Boolean {
                    if (currentRowIndex + 1 >= mappedData[timeSeriesIndex].size) return false
                    currentRowIndex++
                    return true
                }
            }
        }

        override fun internalLoadNext(): Boolean {
            if (currentTimeSeriesIndex + 1 >= mappedData.size) return false
            currentTimeSeriesIndex++
            return true
        }
    }
}