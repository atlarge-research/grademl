package science.atlarge.grademl.query.model

interface RowIterator {

    val schema: TableSchema

    val currentRow: Row

    /**
     * Read the next [Row] into [currentRow]. Returns true iff a new [Row] is loaded. Accessing [currentRow]
     * before calling [loadNext] or after [loadNext] returns false is undefined behavior.
     */
    fun loadNext(): Boolean

    /**
     * Pushes back the [currentRow] such that the next [loadNext] call reads the same time series. Does not reload
     * the previous value of [currentRow]. Accessing [currentRow] after calling [pushBack] and before calling
     * [loadNext] is undefined behavior. Returns true iff a [currentRow] was loaded and is now pushed back.
     *
     * Can be used to "undo" loading a [Row] after determining that it should be read again later (e.g.,
     * in a group-by operation to push back the first non-matching row until the next group is read).
     */
    fun pushBack(): Boolean

}