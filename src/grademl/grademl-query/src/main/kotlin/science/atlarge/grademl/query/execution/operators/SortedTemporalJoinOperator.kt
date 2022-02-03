package science.atlarge.grademl.query.execution.operators

import science.atlarge.grademl.query.execution.*
import science.atlarge.grademl.query.execution.IntTypes.TYPE_BOOLEAN
import science.atlarge.grademl.query.execution.IntTypes.TYPE_NUMERIC
import science.atlarge.grademl.query.execution.IntTypes.TYPE_STRING
import science.atlarge.grademl.query.execution.IntTypes.toInt
import science.atlarge.grademl.query.language.Type
import science.atlarge.grademl.query.model.*

class SortedTemporalJoinOperator(
    private val leftInput: QueryOperator,
    private val rightInput: QueryOperator,
    override val schema: TableSchema,
    leftJoinColumns: List<IndexedSortColumn>,
    rightJoinColumns: List<IndexedSortColumn>,
    leftSelectedColumns: List<Column>,
    rightSelectedColumns: List<Column>
) : AccountingQueryOperator() {

    private val joinColumnTypes = leftJoinColumns.map {
        leftInput.schema.columns[it.columnIndex].type
    }.toTypedArray()
    private val joinColumnAscending = leftJoinColumns.map { it.ascending }.toBooleanArray()
    private val leftJoinColumnIndices = leftJoinColumns.map { it.columnIndex }.toIntArray()
    private val rightJoinColumnIndices = rightJoinColumns.map { it.columnIndex }.toIntArray()

    private val leftSelectedColumnIds = leftSelectedColumns.map { column -> leftInput.schema.indexOfColumn(column)!! }
    private val leftStartTimeColumnId = leftInput.schema.indexOfStartTimeColumn() ?: throw IllegalArgumentException(
        "Left input to temporal join must have _start_time column"
    )
    private val leftEndTimeColumnId = leftInput.schema.indexOfEndTimeColumn() ?: throw IllegalArgumentException(
        "Left input to temporal join must have _end_time column"
    )

    private val rightSelectedColumnIds =
        rightSelectedColumns.map { column -> rightInput.schema.indexOfColumn(column)!! }
    private val rightStartTimeColumnId = rightInput.schema.indexOfStartTimeColumn() ?: throw IllegalArgumentException(
        "Right input to temporal join must have _start_time column"
    )
    private val rightEndTimeColumnId = rightInput.schema.indexOfEndTimeColumn() ?: throw IllegalArgumentException(
        "Right input to temporal join must have _end_time column"
    )

    init {
        require(rightJoinColumns.size == leftJoinColumns.size) {
            "Must have the same number of left and right join columns"
        }
        rightJoinColumns.forEachIndexed { i, rc ->
            require(rightInput.schema.columns[rc.columnIndex].type == joinColumnTypes[i]) {
                "Join columns must have the same type"
            }
            require(rc.ascending == joinColumnAscending[i]) {
                "Join columns must have the sort direction"
            }
        }
        // Sanity check output schema
        require(schema.indexOfStartTimeColumn() == 0) { "Temporal join must produce _start_time column" }
        require(schema.indexOfEndTimeColumn() == 1) { "Temporal join must produce _end_time column" }
        require(schema.indexOfDurationColumn() == 2) { "Temporal join must produce _duration column" }
    }

    override fun createTimeSeriesIterator(): AccountingTimeSeriesIterator<*> = TemporalJoinTimeSeriesIterator(
        schema = schema,
        leftInput = leftInput.execute(),
        rightInput = rightInput.execute(),
        joinColumnTypes = joinColumnTypes,
        joinColumnAscending = joinColumnAscending,
        leftJoinColumnIndices = leftJoinColumnIndices,
        rightJoinColumnIndices = rightJoinColumnIndices,
        leftColumnMap = leftSelectedColumnIds.toIntArray(),
        leftStartColumn = leftStartTimeColumnId,
        leftEndColumn = leftEndTimeColumnId,
        rightColumnMap = rightSelectedColumnIds.toIntArray(),
        rightStartColumn = rightStartTimeColumnId,
        rightEndColumn = rightEndTimeColumnId
    )

}

private class TemporalJoinTimeSeriesIterator(
    schema: TableSchema,
    private val leftInput: TimeSeriesIterator,
    private val rightInput: TimeSeriesIterator,
    joinColumnTypes: Array<Type>,
    private val joinColumnAscending: BooleanArray,
    private val leftJoinColumnIndices: IntArray,
    private val rightJoinColumnIndices: IntArray,
    private val leftColumnMap: IntArray,
    private val leftStartColumn: Int,
    private val leftEndColumn: Int,
    private val rightColumnMap: IntArray,
    private val rightStartColumn: Int,
    private val rightEndColumn: Int
) : AccountingTimeSeriesIterator<SortedTemporalJoinRowIterator>(schema) {

    // Store column types as integers for faster look-ups
    private val joinColumnTypes = joinColumnTypes.map { it.toInt() }.toIntArray()

    // Cache the values of join columns for quick comparison with other time series
    private val cachedBooleanJoinColumnValue = BooleanArray(joinColumnTypes.size)
    private val cachedNumericJoinColumnValue = DoubleArray(joinColumnTypes.size)
    private val cachedStringJoinColumnValue = arrayOfNulls<String?>(joinColumnTypes.size)

    // Cache time series from the left input
    private val leftTimeSeriesCache = TimeSeriesCache(leftInput.schema)
    private var leftCacheIterator: TimeSeriesIterator? = null

    // Cache pointers to left and right input time series
    private lateinit var leftTimeSeries: TimeSeries
    private lateinit var rightTimeSeries: TimeSeries

    // Create a comparator that determines if two time series can be joined
    private val comparator = object : Comparator<TimeSeries> {
        private val types = this@TemporalJoinTimeSeriesIterator.joinColumnTypes
        private val columnDirectionMultipliers = IntArray(joinColumnAscending.size) {
            if (joinColumnAscending[it]) 1 else -1
        }

        override fun compare(left: TimeSeries, right: TimeSeries): Int {
            for (c in types.indices) {
                val lc = leftJoinColumnIndices[c]
                val rc = rightJoinColumnIndices[c]
                val result = when (types[c]) {
                    TYPE_BOOLEAN -> left.getBoolean(lc).compareTo(right.getBoolean(rc))
                    TYPE_NUMERIC -> left.getNumeric(lc).compareTo(right.getNumeric(rc))
                    TYPE_STRING -> left.getString(lc).compareTo(right.getString(rc))
                    else -> throw IllegalStateException()
                }
                if (result != 0) return result * columnDirectionMultipliers[c]
            }
            return 0
        }
    }

    // Pre-compute column offsets for left and right input
    private val leftColumnOffset = Columns.INDEX_NOT_RESERVED
    private val rightColumnOffset = leftColumnOffset + leftColumnMap.size

    override fun getBoolean(columnIndex: Int): Boolean {
        return when {
            columnIndex >= rightColumnOffset -> rightTimeSeries.getBoolean(rightColumnMap[columnIndex - rightColumnOffset])
            columnIndex >= leftColumnOffset -> leftTimeSeries.getBoolean(leftColumnMap[columnIndex - leftColumnOffset])
            else -> throw IllegalArgumentException("Column $columnIndex does not exist or is not a key column")
        }
    }

    override fun getNumeric(columnIndex: Int): Double {
        return when {
            columnIndex >= rightColumnOffset -> rightTimeSeries.getNumeric(rightColumnMap[columnIndex - rightColumnOffset])
            columnIndex >= leftColumnOffset -> leftTimeSeries.getNumeric(leftColumnMap[columnIndex - leftColumnOffset])
            else -> throw IllegalArgumentException("Column $columnIndex does not exist or is not a key column")
        }
    }

    override fun getString(columnIndex: Int): String {
        return when {
            columnIndex >= rightColumnOffset -> rightTimeSeries.getString(rightColumnMap[columnIndex - rightColumnOffset])
            columnIndex >= leftColumnOffset -> leftTimeSeries.getString(leftColumnMap[columnIndex - leftColumnOffset])
            else -> throw IllegalArgumentException("Column $columnIndex does not exist or is not a key column")
        }
    }

    override fun createRowIterator() = SortedTemporalJoinRowIterator(
        schema = schema,
        leftColumnMap = leftColumnMap,
        leftStartColumn = leftStartColumn,
        leftEndColumn = leftEndColumn,
        leftColumnOffset = leftColumnOffset,
        rightColumnMap = rightColumnMap,
        rightStartColumn = rightStartColumn,
        rightEndColumn = rightEndColumn,
        rightColumnOffset = rightColumnOffset
    )

    override fun resetRowIteratorWithCurrentTimeSeries(rowIterator: SortedTemporalJoinRowIterator) {
        rowIterator.reset(leftTimeSeries.rowIterator(), rightTimeSeries.rowIterator())
    }

    override fun internalLoadNext(): Boolean {
        // Join the next time series from the cache with the current right input
        if (leftCacheIterator != null) {
            if (leftCacheIterator!!.loadNext()) {
                leftTimeSeries = leftCacheIterator!!.currentTimeSeries
                rightTimeSeries = rightInput.currentTimeSeries
                return true
            } else {
                leftCacheIterator = null
            }
        }
        // Check if the next right input time series can be joined with the left input cache
        if (leftTimeSeriesCache.numCachedTimeSeries > 0) {
            // Abort if no right input can be loaded
            if (!rightInput.loadNext()) {
                return false
            }
            if (isTimeSeriesMatching(rightInput.currentTimeSeries)) {
                // Restart the left cache iterator to be joined with the new right input
                leftCacheIterator = leftTimeSeriesCache.iterator()
                if (!leftCacheIterator!!.loadNext()) throw IllegalStateException("Cache iterator is empty after filling the cache")
                leftTimeSeries = leftCacheIterator!!.currentTimeSeries
                rightTimeSeries = rightInput.currentTimeSeries
                return true
            } else {
                rightInput.pushBack()
            }
        }
        // Clear the cache to load a new group of time series
        leftTimeSeriesCache.clear()
        // Find matching time series from the left and right input to join
        if (!findMatchingTimeSeries()) return false
        // Load all matching time series from the left input into the cache
        leftInput.pushBack()
        TimeSeriesCacheUtil.addTimeSeriesGroupToCache(leftInput, leftTimeSeriesCache) { left, right ->
            for (i in joinColumnTypes.indices) {
                val c = leftJoinColumnIndices[i]
                val result = when (joinColumnTypes[i]) {
                    TYPE_BOOLEAN -> left.getBoolean(c).compareTo(right.getBoolean(c))
                    TYPE_NUMERIC -> left.getNumeric(c).compareTo(right.getNumeric(c))
                    TYPE_STRING -> left.getString(c).compareTo(right.getString(c))
                    else -> throw IllegalStateException()
                }
                if (result != 0) return@addTimeSeriesGroupToCache false
            }
            true
        }
        // Create a new iterator over the cached inputs
        leftCacheIterator = leftTimeSeriesCache.iterator()
        if (!leftCacheIterator!!.loadNext()) throw IllegalStateException("Cache iterator is empty after filling the cache")
        // Cache the join column values from our left input
        val firstLeftTimeSeries = leftCacheIterator!!.currentTimeSeries
        for (c in leftJoinColumnIndices.indices) {
            when (joinColumnTypes[c]) {
                TYPE_BOOLEAN -> cachedBooleanJoinColumnValue[c] =
                    firstLeftTimeSeries.getBoolean(leftJoinColumnIndices[c])
                TYPE_NUMERIC -> cachedNumericJoinColumnValue[c] =
                    firstLeftTimeSeries.getNumeric(leftJoinColumnIndices[c])
                TYPE_STRING -> cachedStringJoinColumnValue[c] =
                    firstLeftTimeSeries.getString(leftJoinColumnIndices[c])
            }
        }
        // Prepare the next joined time series
        leftTimeSeries = firstLeftTimeSeries
        rightTimeSeries = rightInput.currentTimeSeries
        return true
    }

    private fun isTimeSeriesMatching(timeSeries: TimeSeries): Boolean {
        // Check if it matches the cached column values
        for (c in rightJoinColumnIndices.indices) {
            when (joinColumnTypes[c]) {
                TYPE_BOOLEAN ->
                    if (cachedBooleanJoinColumnValue[c] != timeSeries.getBoolean(rightJoinColumnIndices[c]))
                        return false
                TYPE_NUMERIC ->
                    if (cachedNumericJoinColumnValue[c] != timeSeries.getNumeric(rightJoinColumnIndices[c]))
                        return false
                TYPE_STRING ->
                    if (cachedStringJoinColumnValue[c] != timeSeries.getString(rightJoinColumnIndices[c]))
                        return false
            }
        }
        return true
    }

    private fun findMatchingTimeSeries(): Boolean {
        // Get the first time series from the left input
        if (!leftInput.loadNext()) return false
        var leftTimeSeries = leftInput.currentTimeSeries
        // Get the first time series from the right input
        if (!rightInput.loadNext()) return false
        var rightTimeSeries = rightInput.currentTimeSeries

        // Compare the left and right time series until a match is found
        while (true) {
            val comparison = comparator.compare(leftTimeSeries, rightTimeSeries)
            if (comparison == 0) return true
            // Check if left or right is "smaller"
            if (comparison < 0) {
                // Left is smaller, so load the next time series from the left input
                if (!leftInput.loadNext()) return false
                leftTimeSeries = leftInput.currentTimeSeries
            } else {
                // Right is smaller, so load the next time series from the right input
                if (!rightInput.loadNext()) return false
                rightTimeSeries = rightInput.currentTimeSeries
            }
        }
    }

}

private class SortedTemporalJoinRowIterator(
    schema: TableSchema,
    private val leftColumnMap: IntArray,
    private val leftStartColumn: Int,
    private val leftEndColumn: Int,
    private val leftColumnOffset: Int,
    private val rightColumnMap: IntArray,
    private val rightStartColumn: Int,
    private val rightEndColumn: Int,
    private val rightColumnOffset: Int
) : AccountingRowIterator(schema) {

    private lateinit var leftIterator: RowIterator
    private lateinit var rightIterator: RowIterator

    private var leftStart: Double = 0.0
    private var leftEnd: Double = 0.0
    private var rightStart: Double = 0.0
    private var rightEnd: Double = 0.0

    private var leftRow: Row? = null
    private var rightRow: Row? = null

    fun reset(leftIterator: RowIterator, rightIterator: RowIterator) {
        this.leftIterator = leftIterator
        this.rightIterator = rightIterator
        this.leftStart = 0.0
        this.leftEnd = 0.0
        this.rightStart = 0.0
        this.rightEnd = 0.0
        this.leftRow = null
        this.rightRow = null
    }

    override fun getBoolean(columnIndex: Int): Boolean {
        return when {
            columnIndex >= rightColumnOffset ->
                rightRow!!.getBoolean(rightColumnMap[columnIndex - rightColumnOffset])
            columnIndex >= leftColumnOffset ->
                leftRow!!.getBoolean(leftColumnMap[columnIndex - leftColumnOffset])
            else -> throw IllegalArgumentException("Column $columnIndex does not exist or is not a BOOLEAN column")
        }
    }

    override fun getNumeric(columnIndex: Int): Double {
        return when {
            columnIndex == 0 -> maxOf(leftStart, rightStart)
            columnIndex == 1 -> minOf(leftEnd, rightEnd)
            columnIndex == 2 -> minOf(leftEnd, rightEnd) - maxOf(leftStart, rightStart)
            columnIndex >= rightColumnOffset ->
                rightRow!!.getNumeric(rightColumnMap[columnIndex - rightColumnOffset])
            columnIndex >= leftColumnOffset ->
                leftRow!!.getNumeric(leftColumnMap[columnIndex - leftColumnOffset])
            else -> throw IllegalArgumentException("Column $columnIndex does not exist or is not a NUMERIC column")
        }
    }

    override fun getString(columnIndex: Int): String {
        return when {
            columnIndex >= rightColumnOffset ->
                rightRow!!.getString(rightColumnMap[columnIndex - rightColumnOffset])
            columnIndex >= leftColumnOffset ->
                leftRow!!.getString(leftColumnMap[columnIndex - leftColumnOffset])
            else -> throw IllegalArgumentException("Column $columnIndex does not exist or is not a STRING column")
        }
    }

    override fun internalLoadNext(): Boolean {
        // Load new rows if either the left or right input is not cached
        val loadNewRows = leftRow == null || rightRow == null
        if (leftRow == null && !nextLeft()) return false
        if (rightRow == null && !nextRight()) return false
        // If both rows were cached, advance either left or right (whichever ends first)
        if (!loadNewRows) {
            when {
                leftEnd < rightEnd -> if (!nextLeft()) return false
                leftEnd > rightEnd -> if (!nextRight()) return false
                else -> if (!nextLeft() || !nextRight()) return false
            }
        }
        // Keep loading new rows until overlap is found
        while (leftEnd <= rightStart || rightEnd <= leftStart) {
            when {
                leftEnd < rightEnd -> if (!nextLeft()) return false
                leftEnd > rightEnd -> if (!nextRight()) return false
            }
        }
        // We have found an overlapping pair of input rows
        return true
    }

    private fun nextLeft(): Boolean {
        leftRow = null
        if (!leftIterator.loadNext()) return false
        leftRow = leftIterator.currentRow
        leftStart = leftRow!!.getNumeric(leftStartColumn)
        leftEnd = leftRow!!.getNumeric(leftEndColumn)
        return true
    }

    private fun nextRight(): Boolean {
        rightRow = null
        if (!rightIterator.loadNext()) return false
        rightRow = rightIterator.currentRow
        rightStart = rightRow!!.getNumeric(rightStartColumn)
        rightEnd = rightRow!!.getNumeric(rightEndColumn)
        return true
    }

}