package science.atlarge.grademl.query.execution.operators

import science.atlarge.grademl.query.execution.AccountingRowIterator
import science.atlarge.grademl.query.execution.AccountingTimeSeriesIterator
import science.atlarge.grademl.query.execution.IntTypes
import science.atlarge.grademl.query.execution.IntTypes.toInt
import science.atlarge.grademl.query.model.Columns
import science.atlarge.grademl.query.model.RowIterator
import science.atlarge.grademl.query.model.TableSchema
import science.atlarge.grademl.query.model.TimeSeriesIterator

class IntervalMergingOperator(
    private val input: QueryOperator
) : AccountingQueryOperator() {

    override val schema: TableSchema
        get() = input.schema

    init {
        require(input.schema.indexOfColumn(Columns.START_TIME) == Columns.INDEX_START_TIME) {
            "Input to IntervalMergingOperator mush have start time column at index ${Columns.INDEX_START_TIME}"
        }
        require(input.schema.indexOfColumn(Columns.END_TIME) == Columns.INDEX_END_TIME) {
            "Input to IntervalMergingOperator mush have end time column at index ${Columns.INDEX_END_TIME}"
        }
    }

    override fun createTimeSeriesIterator(): AccountingTimeSeriesIterator<*> =
        IntervalMergingTimeSeriesIterator(input.execute())

}

private class IntervalMergingTimeSeriesIterator(
    private val input: TimeSeriesIterator
) : AccountingTimeSeriesIterator<IntervalMergingRowIterator>(input.schema) {

    override fun getBoolean(columnIndex: Int) = input.currentTimeSeries.getBoolean(columnIndex)
    override fun getNumeric(columnIndex: Int) = input.currentTimeSeries.getNumeric(columnIndex)
    override fun getString(columnIndex: Int) = input.currentTimeSeries.getString(columnIndex)

    override fun createRowIterator() = IntervalMergingRowIterator(schema)

    override fun resetRowIteratorWithCurrentTimeSeries(rowIterator: IntervalMergingRowIterator) {
        rowIterator.input = input.currentTimeSeries.rowIterator()
    }

    override fun internalLoadNext() = input.loadNext()

}

private class IntervalMergingRowIterator(
    schema: TableSchema
) : AccountingRowIterator(schema) {

    lateinit var input: RowIterator

    private val columnTypes = schema.columns.map { it.type.toInt() }.toIntArray()
    private val nonReservedValueColumns = schema.columns.mapIndexedNotNull { index, column ->
        if (!column.isKey && !column.isReserved) index else null
    }
    private val hasDurationColumn = schema.indexOfColumn(Columns.DURATION) == Columns.INDEX_DURATION

    private val booleanValues = BooleanArray(columnTypes.size)
    private val numericValues = DoubleArray(columnTypes.size)
    private val stringValues = arrayOfNulls<String>(columnTypes.size)

    override fun getBoolean(columnIndex: Int) = booleanValues[columnIndex]
    override fun getNumeric(columnIndex: Int) = numericValues[columnIndex]
    override fun getString(columnIndex: Int) = stringValues[columnIndex]!!

    override fun internalLoadNext(): Boolean {
        // Read the next row
        if (!input.loadNext()) return false
        // Cache its values
        val initialRow = input.currentRow
        for (c in columnTypes.indices) {
            when (columnTypes[c]) {
                IntTypes.TYPE_BOOLEAN -> booleanValues[c] = initialRow.getBoolean(c)
                IntTypes.TYPE_NUMERIC -> numericValues[c] = initialRow.getNumeric(c)
                IntTypes.TYPE_STRING -> stringValues[c] = initialRow.getString(c)
                else -> throw IllegalArgumentException("Unsupported type")
            }
        }
        // Read rows as long as their values are identical to merge them
        while (input.loadNext()) {
            // Compare the next row against the cached row
            val nextRow = input.currentRow
            // Check if the timestamps are consecutive
            if (numericValues[Columns.INDEX_END_TIME] != nextRow.getNumeric(Columns.INDEX_START_TIME)) {
                // If not, push back the row for next iteration
                input.pushBack()
                break
            }
            // Check all (regular) value columns
            var isEqual = true
            for (c in nonReservedValueColumns) {
                isEqual = when (columnTypes[c]) {
                    IntTypes.TYPE_BOOLEAN -> booleanValues[c] == nextRow.getBoolean(c)
                    IntTypes.TYPE_NUMERIC -> numericValues[c] == nextRow.getNumeric(c)
                    IntTypes.TYPE_STRING -> stringValues[c] == nextRow.getString(c)
                    else -> throw IllegalArgumentException("Unsupported type")
                }
                if (!isEqual) break
            }
            // If any columns are different, do not merge
            if (!isEqual) {
                input.pushBack()
                break
            }
            // Combine the rows by merging time intervals
            numericValues[Columns.INDEX_END_TIME] = nextRow.getNumeric(Columns.INDEX_END_TIME)
        }
        // Set the merged row's duration (if applicable)
        if (hasDurationColumn) {
            numericValues[Columns.INDEX_DURATION] = numericValues[Columns.INDEX_END_TIME] -
                    numericValues[Columns.INDEX_START_TIME]
        }
        return true
    }

}