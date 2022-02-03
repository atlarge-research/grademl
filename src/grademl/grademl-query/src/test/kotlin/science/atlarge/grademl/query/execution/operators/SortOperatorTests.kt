package science.atlarge.grademl.query.execution.operators

import org.junit.jupiter.api.BeforeAll
import science.atlarge.grademl.query.execution.DataGenerator
import science.atlarge.grademl.query.execution.DataUtils
import science.atlarge.grademl.query.execution.DataUtils.sortGeneratedRows
import science.atlarge.grademl.query.execution.DataUtils.toGeneratedRows
import science.atlarge.grademl.query.execution.DataUtils.toTimeSeriesIterator
import science.atlarge.grademl.query.execution.GeneratedRow
import science.atlarge.grademl.query.execution.IndexedSortColumn
import science.atlarge.grademl.query.model.Table
import science.atlarge.grademl.query.model.TableSchema
import kotlin.test.Test

class SortOperatorTests {

    companion object {
        private var inputData: List<GeneratedRow> = emptyList()

        @JvmStatic
        @BeforeAll
        fun generateInputData() {
            inputData = DataGenerator.generate(0.0, 10, 5)
        }
    }

    @Test
    fun testSortFirstKeyColumn() {
        testSort(listOf("k1"))
    }

    @Test
    fun testSortSecondKeyColumn() {
        testSort(listOf("-k2"))
    }

    @Test
    fun testSortBothKeyColumns() {
        testSort(listOf("-k1", "k2"))
    }

    @Test
    fun testSortNumericValueColumn() {
        testSort(listOf("v1"))
    }

    @Test
    fun testSortStringValueColumn() {
        testSort(listOf("-v2"))
    }

    @Test
    fun testSortBooleanValueColumn() {
        testSort(listOf("v3"))
    }

    @Test
    fun testSortMixedColumns() {
        testSort(listOf("k2", "v1", "k1"))
    }

    @Test
    fun testSortKeyColumnWithPreSort() {
        testSort(listOf("k2"), listOf("k1"))
    }

    @Test
    fun testSortNumericValueColumnWithPreSort() {
        testSort(listOf("-v1"), listOf("k2"))
    }

    @Test
    fun testSortStringValueColumnWithPreSort() {
        testSort(listOf("v2"), listOf("-k1"))
    }

    @Test
    fun testSortBooleanValueColumnWithPreSort() {
        testSort(listOf("-v3"), listOf("-k2"))
    }

    @Test
    fun testSortMixedColumnsWithPreSort() {
        testSort(listOf("-v3", "k2", "v1"), listOf("k1"))
    }

    private fun testSort(sortColumns: List<String>, preSortColumns: List<String> = emptyList()) {
        val shuffledInput = preSortInput(preSortColumns)
        val sortOperator = createSortOperator(
            shuffledInput, preSortColumns.map { sortColumn(it).columnIndex }, sortColumns.map { sortColumn(it) }
        )
        val expectedOutput = sortGeneratedRows(shuffledInput, preSortColumns + sortColumns)
        val producedOutput = sortOperator.execute()
        DataUtils.compareOrderedOutput(expectedOutput, producedOutput.toGeneratedRows())
    }

    private fun sortColumn(columnName: String): IndexedSortColumn {
        val name = columnName.trimStart('-')
        val ascending = !columnName.startsWith('-')
        return IndexedSortColumn(
            DataGenerator.schema.columns.indexOfFirst { it.identifier == name }, ascending
        )
    }

    private fun preSortInput(columnsToSort: List<String>): List<List<GeneratedRow>> {
        // Group rows into time series and shuffle before sorting
        val sortedTimeSeries = inputData.groupBy(GeneratedRow::originalTimeSeriesId)
            .map { it.value }
            .shuffled()
            .toMutableList()
        for (c in columnsToSort.asReversed()) {
            when (c) {
                "k1" -> sortedTimeSeries.sortBy { it[0].k1 }
                "-k1" -> sortedTimeSeries.sortByDescending { it[0].k1 }
                "k2" -> sortedTimeSeries.sortBy { it[0].k2 }
                "-k2" -> sortedTimeSeries.sortByDescending { it[0].k2 }
            }
        }
        return sortedTimeSeries
    }

    private fun createSortOperator(
        inputData: List<List<GeneratedRow>>,
        preSortedColumns: List<Int>,
        columnsToSort: List<IndexedSortColumn>
    ): QueryOperator {
        val table = object : Table {
            override val schema: TableSchema
                get() = DataGenerator.schema

            override fun timeSeriesIterator() = inputData.toTimeSeriesIterator()
        }
        val tableScan = LinearTableScanOperator(table)
        val newSchema = TableSchema(
            DataGenerator.schema.columns.mapIndexed { i, column ->
                if (!column.isKey && columnsToSort.any { it.columnIndex == 1 }) column.copy(isKey = true)
                else column
            }
        )
        return SortOperator(tableScan, newSchema, preSortedColumns, columnsToSort)
    }

}