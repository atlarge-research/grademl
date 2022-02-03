package science.atlarge.grademl.query.execution.operators

import science.atlarge.grademl.query.execution.*
import science.atlarge.grademl.query.execution.IntTypes.toInt
import science.atlarge.grademl.query.model.TableSchema
import science.atlarge.grademl.query.model.TimeSeriesIterator

class SortOperator(
    private val input: QueryOperator,
    override val schema: TableSchema,
    private val preSortedColumns: List<Int>,
    private val remainingSortColumns: List<IndexedSortColumn>
) : AccountingQueryOperator() {

    override fun createTimeSeriesIterator(): AccountingTimeSeriesIterator<*> = SortTimeSeriesIterator(
        input.execute(),
        preSortedColumns.toIntArray(),
        remainingSortColumns.map { it.columnIndex }.toIntArray(),
        remainingSortColumns.map { it.ascending }.toBooleanArray()
    )

}

private fun sort(sortInput: SortInput): SortResult {
    // Create a comparator for row groups
    val rowGroupComparator = object : Comparator<Int> {
        private val columns = sortInput.remainingSortColumns
        private val localColumnTypes = sortInput.columnTypes
        private val columnDirectionMultipliers = IntArray(sortInput.remainingSortColumnsAscending.size) {
            if (sortInput.remainingSortColumnsAscending[it]) 1 else -1
        }

        private val inputCache = sortInput.inputCache

        override fun compare(leftIndex: Int, rightIndex: Int): Int {
            columns.forEachIndexed { index, columnIndex ->
                val result = when (localColumnTypes[columnIndex]) {
                    IntTypes.TYPE_BOOLEAN -> inputCache.getBoolean(columnIndex, leftIndex)
                        .compareTo(inputCache.getBoolean(columnIndex, rightIndex))
                    IntTypes.TYPE_NUMERIC -> inputCache.getNumeric(columnIndex, leftIndex)
                        .compareTo(inputCache.getNumeric(columnIndex, rightIndex))
                    IntTypes.TYPE_STRING -> inputCache.getString(columnIndex, leftIndex)
                        .compareTo(inputCache.getString(columnIndex, rightIndex))
                    else -> throw IllegalStateException()
                }
                if (result != 0) return result * columnDirectionMultipliers[index]
            }
            return 0
        }
    }
    val rowComparator = Comparator<Int> { leftIndex, rightIndex ->
        val groupComparison = rowGroupComparator.compare(leftIndex, rightIndex)
        if (groupComparison != 0) groupComparison else leftIndex.compareTo(rightIndex)
    }
    // Sort the rows in the cache
    val newRowOrder = (0 until sortInput.inputCache.numCachedRows).toMutableList()
    newRowOrder.sortWith(rowComparator)
    // Return the result
    return SortResult(
        sortInput.inputCache,
        newRowOrder.toIntArray(),
        areInputRowsInSameGroup = { l, r -> rowGroupComparator.compare(l, r) == 0 }
    )
}

private class SortInput(
    val inputCache: TimeSeriesCache,
    val columnTypes: IntArray,
    val remainingSortColumns: IntArray,
    val remainingSortColumnsAscending: BooleanArray
)

private class SortResult(
    private val inputCache: TimeSeriesCache,
    private val reorderedInputRows: IntArray,
    private val areInputRowsInSameGroup: (Int, Int) -> Boolean
) {

    private val timeSeriesIds = IntArray(reorderedInputRows.size)
    private var lastComputedTimeSeriesOffset = 0

    init {
        timeSeriesIds[0] = 1
    }

    fun inputRowAt(outputRowId: Int) = reorderedInputRows[outputRowId]

    fun findFirstRowOfNextTimeSeries(fromRowId: Int): Int? {
        var currentRowId = fromRowId + 1
        while (currentRowId < timeSeriesIds.size) {
            if (!isRowInSameTimeSeriesAsPreviousRow(currentRowId)) return currentRowId
            currentRowId++
        }
        return null
    }

    fun isRowInSameTimeSeriesAsPreviousRow(rowId: Int): Boolean {
        require(rowId > 0 && rowId < timeSeriesIds.size)
        // Fill the time series ID cache
        computeTimeSeriesIdsUntil(rowId)
        // Check the time series ID cache
        return timeSeriesIds[rowId] == timeSeriesIds[rowId - 1]
    }

    private fun computeTimeSeriesIdsUntil(targetRowId: Int) {
        for (currentOutRow in (lastComputedTimeSeriesOffset + 1)..targetRowId) {
            val currentInRow = reorderedInputRows[currentOutRow]
            val lastInRow = reorderedInputRows[currentOutRow - 1]
            // Check if both rows are in the same input time series and in the same sorted group
            val inSameTimeSeries =
                (inputCache.timeSeriesIdOf(lastInRow) == inputCache.timeSeriesIdOf(currentInRow)) &&
                        areInputRowsInSameGroup(lastInRow, currentInRow)
            if (inSameTimeSeries) timeSeriesIds[currentOutRow] = timeSeriesIds[currentOutRow - 1]
            else timeSeriesIds[currentOutRow] = timeSeriesIds[currentOutRow - 1] + 1
        }
        lastComputedTimeSeriesOffset = maxOf(lastComputedTimeSeriesOffset, targetRowId)
    }

}

private class SortTimeSeriesIterator(
    private val input: TimeSeriesIterator,
    private val preSortedColumns: IntArray,
    private val remainingSortColumns: IntArray,
    private val remainingSortColumnsAscending: BooleanArray
) : AccountingTimeSeriesIterator<SortRowIterator>(input.schema) {

    // Store time series and rows for sorting
    private val inputCache = TimeSeriesCache(schema)

    // Pre-compute column types needed for sorting
    private val columnTypes = schema.columns.map { it.type.toInt() }.toIntArray()

    // Track the result of the sorting operation
    private var sortResult: SortResult? = null
    private var firstOutRowOfTimeSeries = -1
    private var firstInRowOfTimeSeries = -1

    override fun getBoolean(columnIndex: Int) = inputCache.getBoolean(columnIndex, firstInRowOfTimeSeries)
    override fun getNumeric(columnIndex: Int) = inputCache.getNumeric(columnIndex, firstInRowOfTimeSeries)
    override fun getString(columnIndex: Int) = inputCache.getString(columnIndex, firstInRowOfTimeSeries)

    override fun createRowIterator() = SortRowIterator(schema, inputCache)

    override fun resetRowIteratorWithCurrentTimeSeries(rowIterator: SortRowIterator) {
        rowIterator.reset(sortResult!!, firstOutRowOfTimeSeries)
    }

    override fun internalLoadNext(): Boolean {
        // Check if there are rows in the cache to create a new time series from
        if (sortResult != null) {
            val firstRow = sortResult!!.findFirstRowOfNextTimeSeries(firstOutRowOfTimeSeries)
            if (firstRow != null) {
                firstOutRowOfTimeSeries = firstRow
                firstInRowOfTimeSeries = sortResult!!.inputRowAt(firstOutRowOfTimeSeries)
                return true
            }
        }
        // Reset the row cache
        inputCache.clear()
        // Check if there are more time series to process
        if (!input.loadNext()) {
            // If not, clean up the cache and return
            inputCache.finalize()
            return false
        } else {
            input.pushBack()
        }
        // Add the next group of time series to the cache
        TimeSeriesCacheUtil.addTimeSeriesGroupToCache(input, inputCache) { left, right ->
            preSortedColumns.all { c ->
                when (columnTypes[c]) {
                    IntTypes.TYPE_BOOLEAN -> left.getBoolean(c) == right.getBoolean(c)
                    IntTypes.TYPE_NUMERIC -> left.getNumeric(c) == right.getNumeric(c)
                    IntTypes.TYPE_STRING -> left.getString(c) == right.getString(c)
                    else -> throw IllegalArgumentException("Sort does not support type: ${schema.columns[c].type}")
                }
            }
        }
        // Sort the rows in the cache
        val sortInput = SortInput(inputCache, columnTypes, remainingSortColumns, remainingSortColumnsAscending)
        sortResult = sort(sortInput)
        // Return the first time series in the sorted result
        firstOutRowOfTimeSeries = 0
        firstInRowOfTimeSeries = sortResult!!.inputRowAt(firstOutRowOfTimeSeries)
        return true
    }

}

private class SortRowIterator(
    schema: TableSchema,
    private val inputCache: TimeSeriesCache
) : AccountingRowIterator(schema) {

    private lateinit var sortResult: SortResult
    private var firstOutRowId = -1
    private var isFirst = false
    private var isValid = false
    private var currentOutRowId = -1
    private var currentInRowId = -1

    fun reset(sortResult: SortResult, firstOutRowId: Int) {
        this.sortResult = sortResult
        this.firstOutRowId = firstOutRowId
        this.isFirst = true
        this.isValid = true
    }

    override fun getBoolean(columnIndex: Int) = inputCache.getBoolean(columnIndex, currentInRowId)
    override fun getNumeric(columnIndex: Int) = inputCache.getNumeric(columnIndex, currentInRowId)
    override fun getString(columnIndex: Int) = inputCache.getString(columnIndex, currentInRowId)

    override fun internalLoadNext(): Boolean {
        if (!isValid) return false
        // The first row is always valid
        if (isFirst) {
            currentOutRowId = firstOutRowId
            currentInRowId = sortResult.inputRowAt(currentOutRowId)
            isFirst = false
            return true
        }
        // Check if there is a next row
        if (currentOutRowId + 1 >= inputCache.numCachedRows) {
            isValid = false
            return false
        }
        // Check if the next row is in the same time series
        currentOutRowId++
        if (!sortResult.isRowInSameTimeSeriesAsPreviousRow(currentOutRowId)) {
            isValid = false
            return false
        }
        // Return the next row
        currentInRowId = sortResult.inputRowAt(currentOutRowId)
        return true
    }

}