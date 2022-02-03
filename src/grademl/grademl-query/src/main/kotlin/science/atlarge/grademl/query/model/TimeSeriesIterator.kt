package science.atlarge.grademl.query.model

interface TimeSeriesIterator {

    val schema: TableSchema

    val currentTimeSeries: TimeSeries

    /**
     * Read the next [TimeSeries] into [currentTimeSeries]. Returns true iff a new [TimeSeries] is loaded.
     * Accessing [currentTimeSeries] before calling [loadNext] or after [loadNext] returns false is undefined behavior.
     */
    fun loadNext(): Boolean

    /**
     * Pushes back the [currentTimeSeries] such that the next [loadNext] call reads the same time series. Does not
     * reload the previous value of [currentTimeSeries]. Cannot be used after calling [TimeSeries.rowIterator] on
     * [currentTimeSeries]. Accessing [currentTimeSeries] after calling [pushBack] and before calling [loadNext]
     * is undefined behavior. Returns true iff a [currentTimeSeries] was loaded and is now pushed back.
     *
     * Can be used to "undo" loading a [TimeSeries] after determining that it should be read again later (e.g.,
     * in a group-by operation to push back the first non-matching time series until the next group is read).
     */
    fun pushBack(): Boolean

}