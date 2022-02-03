package science.atlarge.grademl.query.execution

import science.atlarge.grademl.query.execution.IntTypes.toInt
import science.atlarge.grademl.query.model.*
import java.util.*

class TimeSeriesCache(
    private val schema: TableSchema
) {

    // Determine column types
    private val columnTypes = schema.columns.map { it.type.toInt() }.toIntArray()

    // Determine which column are keys and which are values
    private val isKeyColumn = schema.columns.map { it.isKey }.toBooleanArray()
    private val keyColumns = schema.columns
        .mapIndexed { index, column -> if (column.isKey) index else null }
        .filterNotNull()
        .toIntArray()
    private val valueColumns = schema.columns
        .mapIndexed { index, column -> if (!column.isKey) index else null }
        .filterNotNull()
        .toIntArray()

    // Cache time series keys and values
    private val cachedBooleanValues = columnTypes.map { columnType ->
        if (columnType == IntTypes.TYPE_BOOLEAN) BooleanArray(INITIAL_CACHE_SIZE) else booleanArrayOf()
    }.toTypedArray()
    private val cachedNumericValues = columnTypes.map { columnType ->
        if (columnType == IntTypes.TYPE_NUMERIC) DoubleArray(INITIAL_CACHE_SIZE) else doubleArrayOf()
    }.toTypedArray()
    private val cachedStringValues = columnTypes.map { columnType ->
        if (columnType == IntTypes.TYPE_STRING) arrayOfNulls<String?>(INITIAL_CACHE_SIZE) else emptyArray()
    }.toTypedArray()

    // Track how many rows are cached
    private var cachedRowCount = 0
    private var maxCachedRowCount = INITIAL_CACHE_SIZE

    // Track how many time series are cached and which rows map to which time series
    private var timeSeriesIdPerRow = IntArray(INITIAL_CACHE_SIZE)
    private var firstRowIdPerTimeSeries = IntArray(INITIAL_CACHE_SIZE)
    private var rowsPerTimeSeries = IntArray(INITIAL_CACHE_SIZE)
    private var cachedTimeSeriesCount = 0
    private var maxCachedTimeSeriesCount = INITIAL_CACHE_SIZE

    val numCachedTimeSeries: Int
        get() = cachedTimeSeriesCount
    val numCachedRows: Int
        get() = cachedRowCount

    val isEmpty: Boolean
        get() = cachedRowCount == 0

    // Random access of rows and time series
    fun firstRowIdOf(timeSeriesId: Int) = firstRowIdPerTimeSeries[timeSeriesId]
    fun lastRowIdOf(timeSeriesId: Int) = firstRowIdOf(timeSeriesId) + rowCountOf(timeSeriesId) - 1
    fun rowCountOf(timeSeriesId: Int) = rowsPerTimeSeries[timeSeriesId]

    fun timeSeriesIdOf(rowId: Int) = timeSeriesIdPerRow[rowId]

    fun getBoolean(columnId: Int, rowId: Int): Boolean {
        return if (isKeyColumn[columnId]) cachedBooleanValues[columnId][timeSeriesIdOf(rowId)]
        else cachedBooleanValues[columnId][rowId]
    }

    fun getNumeric(columnId: Int, rowId: Int): Double {
        return if (isKeyColumn[columnId]) cachedNumericValues[columnId][timeSeriesIdOf(rowId)]
        else cachedNumericValues[columnId][rowId]
    }

    fun getString(columnId: Int, rowId: Int): String {
        return if (isKeyColumn[columnId]) cachedStringValues[columnId][timeSeriesIdOf(rowId)]!!
        else cachedStringValues[columnId][rowId]!!
    }

    fun addTimeSeries(timeSeries: TimeSeries): Int {
        // Prepare the time series-level cache
        if (cachedTimeSeriesCount == maxCachedTimeSeriesCount) expandTimeSeriesCache()
        val timeSeriesId = cachedTimeSeriesCount
        // Add keys to the cache
        for (c in keyColumns) {
            when (columnTypes[c]) {
                IntTypes.TYPE_BOOLEAN -> cachedBooleanValues[c][timeSeriesId] = timeSeries.getBoolean(c)
                IntTypes.TYPE_NUMERIC -> cachedNumericValues[c][timeSeriesId] = timeSeries.getNumeric(c)
                IntTypes.TYPE_STRING -> cachedStringValues[c][timeSeriesId] = timeSeries.getString(c)
            }
        }
        // Add rows to the cache
        val rowIterator = timeSeries.rowIterator()
        var addedRowCount = 0
        var lCachedRowCount = cachedRowCount
        var lMaxCachedRowCount = maxCachedRowCount
        var lTimeSeriesIdPerRow = timeSeriesIdPerRow
        while (rowIterator.loadNext()) {
            val row = rowIterator.currentRow
            // Expand the row cache if needed
            if (lCachedRowCount == lMaxCachedRowCount) {
                expandRowCache()
                lMaxCachedRowCount = maxCachedRowCount
                lTimeSeriesIdPerRow = timeSeriesIdPerRow
            }
            // Add row values to the cache
            for (c in valueColumns) {
                when (columnTypes[c]) {
                    IntTypes.TYPE_BOOLEAN -> cachedBooleanValues[c][lCachedRowCount] = row.getBoolean(c)
                    IntTypes.TYPE_NUMERIC -> cachedNumericValues[c][lCachedRowCount] = row.getNumeric(c)
                    IntTypes.TYPE_STRING -> cachedStringValues[c][lCachedRowCount] = row.getString(c)
                }
            }
            // Set time series ID
            lTimeSeriesIdPerRow[lCachedRowCount] = timeSeriesId
            // Increment row counters
            addedRowCount++
            lCachedRowCount++
        }

        // Store updated counters
        firstRowIdPerTimeSeries[timeSeriesId] = cachedRowCount
        rowsPerTimeSeries[timeSeriesId] = addedRowCount
        cachedRowCount = lCachedRowCount
        cachedTimeSeriesCount++

        return timeSeriesId
    }

    fun createTimeSeriesWrapper(initialTimeSeriesId: Int = -1) = TimeSeriesWrapper().apply {
        timeSeriesId = initialTimeSeriesId
    }

    fun createRowIterator(initialTimeSeriesId: Int = -1) = CachedRowIterator().apply {
        reset(initialTimeSeriesId)
    }

    fun createRowWrapper(initialRowId: Int = -1) = RowWrapper().apply {
        rowId = initialRowId
    }

    fun iterator(): TimeSeriesIterator = object : AbstractTimeSeriesIterator(this@TimeSeriesCache.schema) {
        private var currentTimeSeriesId = -1

        private val timeSeriesWrapper = TimeSeriesWrapper()
        override val currentTimeSeries: TimeSeries
            get() = timeSeriesWrapper

        override fun internalLoadNext(): Boolean {
            // Return false if all cache time series have been read
            if (currentTimeSeriesId + 1 >= cachedTimeSeriesCount) return false
            // Increment the current time series ID
            currentTimeSeriesId++
            timeSeriesWrapper.timeSeriesId = currentTimeSeriesId
            return true
        }
    }

    fun clear() {
        cachedRowCount = 0
        cachedTimeSeriesCount = 0
    }

    fun finalize() {
        clear()
        maxCachedRowCount = 0
        maxCachedTimeSeriesCount = 0
        Arrays.fill(cachedBooleanValues, booleanArrayOf())
        Arrays.fill(cachedNumericValues, doubleArrayOf())
        Arrays.fill(cachedStringValues, emptyArray<String?>())
        timeSeriesIdPerRow = intArrayOf()
        firstRowIdPerTimeSeries = intArrayOf()
        rowsPerTimeSeries = intArrayOf()
    }

    private fun expandTimeSeriesCache() {
        val newSize = maxCachedTimeSeriesCount * 2

        // Expand key column cache
        for (c in keyColumns) {
            when (columnTypes[c]) {
                IntTypes.TYPE_BOOLEAN -> cachedBooleanValues[c] = cachedBooleanValues[c].copyOf(newSize)
                IntTypes.TYPE_NUMERIC -> cachedNumericValues[c] = cachedNumericValues[c].copyOf(newSize)
                IntTypes.TYPE_STRING -> cachedStringValues[c] = cachedStringValues[c].copyOf(newSize)
            }
        }
        // Expand row count and offset cache
        firstRowIdPerTimeSeries = firstRowIdPerTimeSeries.copyOf(newSize)
        rowsPerTimeSeries = rowsPerTimeSeries.copyOf(newSize)

        maxCachedTimeSeriesCount = newSize
    }

    private fun expandRowCache() {
        val newSize = maxCachedRowCount * 2

        // Expand value column cache
        for (c in valueColumns) {
            when (columnTypes[c]) {
                IntTypes.TYPE_BOOLEAN -> cachedBooleanValues[c] = cachedBooleanValues[c].copyOf(newSize)
                IntTypes.TYPE_NUMERIC -> cachedNumericValues[c] = cachedNumericValues[c].copyOf(newSize)
                IntTypes.TYPE_STRING -> cachedStringValues[c] = cachedStringValues[c].copyOf(newSize)
            }
        }

        // Expand time series ID map
        timeSeriesIdPerRow = timeSeriesIdPerRow.copyOf(newSize)

        maxCachedRowCount = newSize
    }

    inner class TimeSeriesWrapper : TimeSeries {
        override val schema: TableSchema
            get() = this@TimeSeriesCache.schema

        var timeSeriesId: Int = -1

        override fun getBoolean(columnIndex: Int): Boolean {
            require(isKeyColumn[columnIndex])
            return cachedBooleanValues[columnIndex][timeSeriesId]
        }

        override fun getNumeric(columnIndex: Int): Double {
            require(isKeyColumn[columnIndex])
            return cachedNumericValues[columnIndex][timeSeriesId]
        }

        override fun getString(columnIndex: Int): String {
            require(isKeyColumn[columnIndex])
            return cachedStringValues[columnIndex][timeSeriesId]!!
        }

        override fun rowIterator(): RowIterator =
            CachedRowIterator().also { it.reset(timeSeriesId) }
    }

    inner class CachedRowIterator : AbstractRowIterator(this@TimeSeriesCache.schema) {
        private var timeSeriesId = -1
        private var currentRowId = -1
        private var lastRowId = -1

        private val rowWrapper = RowWrapper()
        override val currentRow: Row
            get() = rowWrapper

        fun reset(newTimeSeriesId: Int) {
            if (newTimeSeriesId in 0 until cachedTimeSeriesCount) {
                timeSeriesId = newTimeSeriesId
                currentRowId = firstRowIdOf(newTimeSeriesId) - 1
                lastRowId = currentRowId + rowCountOf(newTimeSeriesId)
            } else {
                timeSeriesId = -1
                currentRowId = -1
                lastRowId = -1
            }
            isCurrentRowValid = false
            isCurrentRowPushedBack = false
        }

        override fun internalLoadNext(): Boolean {
            if (currentRowId == lastRowId) return false
            currentRowId++
            rowWrapper.rowId = currentRowId
            return true
        }
    }

    inner class RowWrapper : Row {
        override val schema: TableSchema
            get() = this@TimeSeriesCache.schema

        private var timeSeriesId: Int = -1
        var rowId: Int = -1
            set(value) {
                timeSeriesId = if (value in 0 until numCachedRows) timeSeriesIdOf(value) else -1
                field = value
            }

        override fun getBoolean(columnIndex: Int): Boolean {
            return if (isKeyColumn[columnIndex]) cachedBooleanValues[columnIndex][timeSeriesId]
            else cachedBooleanValues[columnIndex][rowId]
        }

        override fun getNumeric(columnIndex: Int): Double {
            return if (isKeyColumn[columnIndex]) cachedNumericValues[columnIndex][timeSeriesId]
            else cachedNumericValues[columnIndex][rowId]
        }

        override fun getString(columnIndex: Int): String {
            return if (isKeyColumn[columnIndex]) cachedStringValues[columnIndex][timeSeriesId]!!
            else cachedStringValues[columnIndex][rowId]!!
        }
    }

    companion object {
        private const val INITIAL_CACHE_SIZE = 16
    }
}