package science.atlarge.grademl.query.execution

import science.atlarge.grademl.query.model.TableSchema
import science.atlarge.grademl.query.model.TimeSeriesIterator

abstract class AbstractTimeSeriesIterator(
    override val schema: TableSchema
) : TimeSeriesIterator {

    protected var isCurrentTimeSeriesValid = false
    protected var isCurrentTimeSeriesPushedBack = false

    protected abstract fun internalLoadNext(): Boolean

    override fun loadNext(): Boolean {
        if (isCurrentTimeSeriesPushedBack) {
            isCurrentTimeSeriesValid = true
            isCurrentTimeSeriesPushedBack = false
            return true
        }
        isCurrentTimeSeriesValid = internalLoadNext()
        return isCurrentTimeSeriesValid
    }

    override fun pushBack(): Boolean {
        if (!isCurrentTimeSeriesValid || isCurrentTimeSeriesPushedBack) return false
        isCurrentTimeSeriesValid = false
        isCurrentTimeSeriesPushedBack = true
        return true
    }
}