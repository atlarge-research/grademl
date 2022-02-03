package science.atlarge.grademl.query.execution.operators

import science.atlarge.grademl.query.execution.AccountingTimeSeriesIterator
import science.atlarge.grademl.query.execution.QueryExecutionStatistics
import science.atlarge.grademl.query.model.TimeSeriesIterator

abstract class AccountingQueryOperator : QueryOperator {

    private val iterators = mutableListOf<AccountingTimeSeriesIterator<*>>()

    protected abstract fun createTimeSeriesIterator(): AccountingTimeSeriesIterator<*>

    fun collectExecutionStatistics(): QueryExecutionStatistics {
        var uniqueTimeSeriesProduced = 0L
        var totalTimeSeriesProduced = 0L
        var uniqueRowsProduced = 0L
        var totalRowsProduced = 0L
        for (iterator in iterators) {
            uniqueTimeSeriesProduced = maxOf(uniqueTimeSeriesProduced, iterator.timeSeriesProduced)
            totalTimeSeriesProduced += iterator.timeSeriesProduced
            uniqueRowsProduced = maxOf(uniqueRowsProduced, iterator.uniqueRowsProduced)
            totalRowsProduced += iterator.totalRowsProduced
        }
        return QueryExecutionStatistics(
            uniqueTimeSeriesProduced,
            totalTimeSeriesProduced,
            uniqueRowsProduced,
            totalRowsProduced
        )
    }

    final override fun execute(): TimeSeriesIterator {
        val iterator = createTimeSeriesIterator()
        iterators.add(iterator)
        return iterator
    }

}