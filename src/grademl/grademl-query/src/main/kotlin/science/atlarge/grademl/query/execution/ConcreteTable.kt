package science.atlarge.grademl.query.execution

import science.atlarge.grademl.query.execution.IntTypes.toInt
import science.atlarge.grademl.query.model.*

class ConcreteTable private constructor(
    override val schema: TableSchema,
    private val booleanColumns: Array<BooleanArray>,
    private val numericColumns: Array<DoubleArray>,
    private val stringColumns: Array<Array<String>>,
    private val timeSeriesIndices: IntArray,
    private val timeSeriesSizes: IntArray,
) : Table {

    val timeSeriesCount = timeSeriesSizes.size
    val rowCount = timeSeriesSizes.sum()

    override fun timeSeriesIterator() = object : AbstractTimeSeriesIterator(this@ConcreteTable.schema) {
        private var currentTimeSeriesId = -1

        override val currentTimeSeries = object : TimeSeries {
            override val schema: TableSchema
                get() = this@ConcreteTable.schema

            override fun getBoolean(columnIndex: Int) = booleanColumns[columnIndex][currentTimeSeriesId]
            override fun getNumeric(columnIndex: Int) = numericColumns[columnIndex][currentTimeSeriesId]
            override fun getString(columnIndex: Int) = stringColumns[columnIndex][currentTimeSeriesId]

            override fun rowIterator() = object : AbstractRowIterator(this@ConcreteTable.schema) {
                private var currentRowId = timeSeriesIndices[currentTimeSeriesId] - 1
                private val lastRowId = currentRowId + timeSeriesSizes[currentTimeSeriesId]

                override val currentRow = object : Row {
                    override val schema: TableSchema
                        get() = this@ConcreteTable.schema

                    override fun getBoolean(columnIndex: Int) = booleanColumns[columnIndex][currentRowId]
                    override fun getNumeric(columnIndex: Int) = numericColumns[columnIndex][currentRowId]
                    override fun getString(columnIndex: Int) = stringColumns[columnIndex][currentRowId]
                }

                override fun internalLoadNext(): Boolean {
                    if (currentRowId >= lastRowId) return false
                    currentRowId++
                    return true
                }
            }
        }

        override fun internalLoadNext(): Boolean {
            if (currentTimeSeriesId + 1 >= timeSeriesCount) return false
            currentTimeSeriesId++
            return true
        }
    }

    companion object {

        private const val INITIAL_ARRAY_SIZE = 16

        @Suppress("UNCHECKED_CAST")
        fun from(timeSeriesIterator: TimeSeriesIterator): ConcreteTable {
            // Determine the schema and column types
            val schema = timeSeriesIterator.schema
            val columnTypes = schema.columns.map { it.type.toInt() }.toIntArray()
            val keyColumns = schema.columns
                .mapIndexedNotNull { index, col -> if (col.isKey) index else null }
                .toIntArray()
            val valueColumns = schema.columns
                .mapIndexedNotNull { index, col -> if (!col.isKey) index else null }
                .toIntArray()

            // Construct data arrays for each column
            var timeSeriesArraySize = INITIAL_ARRAY_SIZE
            var rowArraySize = INITIAL_ARRAY_SIZE
            val booleanColumns = Array(columnTypes.size) { columnId ->
                if (columnTypes[columnId] == IntTypes.TYPE_BOOLEAN) BooleanArray(INITIAL_ARRAY_SIZE)
                else booleanArrayOf()
            }
            val numericColumns = Array(columnTypes.size) { columnId ->
                if (columnTypes[columnId] == IntTypes.TYPE_NUMERIC) DoubleArray(INITIAL_ARRAY_SIZE)
                else doubleArrayOf()
            }
            val stringColumns = Array(columnTypes.size) { columnId ->
                if (columnTypes[columnId] == IntTypes.TYPE_STRING) arrayOfNulls<String>(INITIAL_ARRAY_SIZE)
                else emptyArray()
            }

            // Track the row count and first row index for each time series
            var timeSeriesIndices = IntArray(INITIAL_ARRAY_SIZE)
            var timeSeriesSizes = IntArray(INITIAL_ARRAY_SIZE)

            // Read every time series and every row from the input table and add it to the data arrays
            var timeSeriesAdded = 0
            var rowsAdded = 0
            while (timeSeriesIterator.loadNext()) {
                val timeSeries = timeSeriesIterator.currentTimeSeries
                val timeSeriesId = timeSeriesAdded++

                // Extend the data arrays if needed to store the time series' key column values
                if (timeSeriesId >= timeSeriesArraySize) {
                    timeSeriesArraySize *= 2
                    timeSeriesIndices = timeSeriesIndices.copyOf(timeSeriesArraySize)
                    timeSeriesSizes = timeSeriesSizes.copyOf(timeSeriesArraySize)
                    for (c in keyColumns) {
                        when (columnTypes[c]) {
                            IntTypes.TYPE_BOOLEAN -> booleanColumns[c] = booleanColumns[c].copyOf(timeSeriesArraySize)
                            IntTypes.TYPE_NUMERIC -> numericColumns[c] = numericColumns[c].copyOf(timeSeriesArraySize)
                            IntTypes.TYPE_STRING -> stringColumns[c] = stringColumns[c].copyOf(timeSeriesArraySize)
                            else -> throw IllegalArgumentException("Unsupported column type")
                        }
                    }
                }

                // Add the key columns for this time series to the data arrays
                for (c in keyColumns) {
                    when (columnTypes[c]) {
                        IntTypes.TYPE_BOOLEAN -> booleanColumns[c][timeSeriesId] = timeSeries.getBoolean(c)
                        IntTypes.TYPE_NUMERIC -> numericColumns[c][timeSeriesId] = timeSeries.getNumeric(c)
                        IntTypes.TYPE_STRING -> stringColumns[c][timeSeriesId] = timeSeries.getString(c)
                        else -> throw IllegalArgumentException("Unsupported column type")
                    }
                }

                // Iterate over rows to add them to the data arrays
                val timeSeriesStartIndex = rowsAdded
                var timeSeriesRowCount = 0
                val rowIterator = timeSeries.rowIterator()
                while (rowIterator.loadNext()) {
                    val rowId = rowsAdded++
                    val row = rowIterator.currentRow
                    timeSeriesRowCount++

                    // Extend the data arrays if needed to store the row's values
                    if (rowId >= rowArraySize) {
                        rowArraySize *= 2
                        for (c in valueColumns) {
                            when (columnTypes[c]) {
                                IntTypes.TYPE_BOOLEAN -> booleanColumns[c] = booleanColumns[c].copyOf(rowArraySize)
                                IntTypes.TYPE_NUMERIC -> numericColumns[c] = numericColumns[c].copyOf(rowArraySize)
                                IntTypes.TYPE_STRING -> stringColumns[c] = stringColumns[c].copyOf(rowArraySize)
                                else -> throw IllegalArgumentException("Unsupported column type")
                            }
                        }
                    }

                    // Add the row's values to the data arrays
                    for (c in valueColumns) {
                        when (columnTypes[c]) {
                            IntTypes.TYPE_BOOLEAN -> booleanColumns[c][timeSeriesId] = timeSeries.getBoolean(c)
                            IntTypes.TYPE_NUMERIC -> numericColumns[c][timeSeriesId] = timeSeries.getNumeric(c)
                            IntTypes.TYPE_STRING -> stringColumns[c][timeSeriesId] = timeSeries.getString(c)
                            else -> throw IllegalArgumentException("Unsupported column type")
                        }
                    }
                }

                // Store the time series' starting index and size
                timeSeriesIndices[timeSeriesId] = timeSeriesStartIndex
                timeSeriesSizes[timeSeriesId] = timeSeriesRowCount
            }

            // Trim the data and metadata arrays to the correct size
            timeSeriesIndices = timeSeriesIndices.copyOf(timeSeriesAdded)
            timeSeriesSizes = timeSeriesSizes.copyOf(timeSeriesAdded)
            for (c in keyColumns) {
                when (columnTypes[c]) {
                    IntTypes.TYPE_BOOLEAN -> booleanColumns[c] = booleanColumns[c].copyOf(timeSeriesAdded)
                    IntTypes.TYPE_NUMERIC -> numericColumns[c] = numericColumns[c].copyOf(timeSeriesAdded)
                    IntTypes.TYPE_STRING -> stringColumns[c] = stringColumns[c].copyOf(timeSeriesAdded)
                    else -> throw IllegalArgumentException("Unsupported column type")
                }
            }
            for (c in valueColumns) {
                when (columnTypes[c]) {
                    IntTypes.TYPE_BOOLEAN -> booleanColumns[c] = booleanColumns[c].copyOf(rowsAdded)
                    IntTypes.TYPE_NUMERIC -> numericColumns[c] = numericColumns[c].copyOf(rowsAdded)
                    IntTypes.TYPE_STRING -> stringColumns[c] = stringColumns[c].copyOf(rowsAdded)
                    else -> throw IllegalArgumentException("Unsupported column type")
                }
            }

            // Create the ConcreteTable
            return ConcreteTable(
                schema,
                booleanColumns,
                numericColumns,
                stringColumns as Array<Array<String>>,
                timeSeriesIndices,
                timeSeriesSizes
            )
        }

    }

}