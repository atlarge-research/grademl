package science.atlarge.grademl.query.execution

import science.atlarge.grademl.query.model.TimeSeries
import science.atlarge.grademl.query.model.TimeSeriesIterator

object TimeSeriesCacheUtil {

    fun addTimeSeriesGroupToCache(
        timeSeriesIterator: TimeSeriesIterator,
        cache: TimeSeriesCache,
        areTimeSeriesInSameGroup: (TimeSeries, TimeSeries) -> Boolean
    ): Boolean {
        // Add the next time series to the next as the first time series in the group
        if (!timeSeriesIterator.loadNext()) return false
        val firstTimeSeriesId = cache.addTimeSeries(timeSeriesIterator.currentTimeSeries)
        // Get a reference to the first time series
        val firstTimeSeries = cache.createTimeSeriesWrapper(firstTimeSeriesId)
        // Read additional time series until one is found that does not belong to the same group
        while (timeSeriesIterator.loadNext()) {
            if (areTimeSeriesInSameGroup(firstTimeSeries, timeSeriesIterator.currentTimeSeries)) {
                // Add all time series in the same group to the cache
                cache.addTimeSeries(timeSeriesIterator.currentTimeSeries)
            } else {
                timeSeriesIterator.pushBack()
                break
            }
        }
        return true
    }

}