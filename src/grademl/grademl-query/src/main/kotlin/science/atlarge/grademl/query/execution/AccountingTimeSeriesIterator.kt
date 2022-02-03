package science.atlarge.grademl.query.execution

import science.atlarge.grademl.query.model.RowIterator
import science.atlarge.grademl.query.model.TableSchema
import science.atlarge.grademl.query.model.TimeSeries
import science.atlarge.grademl.query.model.TimeSeriesIterator

abstract class AccountingTimeSeriesIterator<T : AccountingRowIterator>(
    final override val schema: TableSchema
) : TimeSeriesIterator, TimeSeries {

    final override val currentTimeSeries: TimeSeries
        get() = this
    private var isCurrentTimeSeriesValid = false
    private var isCurrentTimeSeriesPushedBack = false

    private val rowIterators = mutableListOf<T>()
    private var rowIteratorsInUse = 0

    fun resetInternal() {
        isCurrentTimeSeriesValid = false
        isCurrentTimeSeriesPushedBack = false
        rowIteratorsInUse = 0
    }

    protected abstract fun createRowIterator(): T
    protected abstract fun resetRowIteratorWithCurrentTimeSeries(rowIterator: T)

    final override fun rowIterator(): RowIterator {
        if (rowIteratorsInUse == rowIterators.size) rowIterators.add(createRowIterator())
        val rowIterator = rowIterators[rowIteratorsInUse++]
        rowIterator.resetInternal()
        resetRowIteratorWithCurrentTimeSeries(rowIterator)
        return rowIterator
    }

    var timeSeriesProduced = 0L
        private set
    var totalRowsProduced = 0L
        private set
    var uniqueRowsProduced = 0L
        private set

    protected abstract fun internalLoadNext(): Boolean

    final override fun loadNext(): Boolean {
        if (isCurrentTimeSeriesPushedBack) {
            isCurrentTimeSeriesPushedBack = false
            isCurrentTimeSeriesValid = true
            return true
        }
        // Collect statistics from row iterators and reset the iterator count
        if (rowIteratorsInUse > 0) {
            var maxRowsProduced = 0L
            for (i in 0 until rowIteratorsInUse) {
                val rowIterator = rowIterators[i]
                val rowsProduced = rowIterator.rowsProduced
                rowIterator.resetRowsProduced()
                totalRowsProduced += rowsProduced
                maxRowsProduced = maxOf(maxRowsProduced, rowsProduced)
            }
            uniqueRowsProduced += maxRowsProduced
            rowIteratorsInUse = 0
        }
        // Load the next time series
        isCurrentTimeSeriesValid = internalLoadNext()
        if (isCurrentTimeSeriesValid) timeSeriesProduced++
        return isCurrentTimeSeriesValid
    }

    override fun pushBack(): Boolean {
        if (!isCurrentTimeSeriesValid || isCurrentTimeSeriesPushedBack) return false
        if (rowIteratorsInUse > 0) {
            throw UnsupportedOperationException("Cannot pushBack a TimeSeries after a RowIterator has been created")
        }
        isCurrentTimeSeriesValid = false
        isCurrentTimeSeriesPushedBack = true
        return true
    }

}