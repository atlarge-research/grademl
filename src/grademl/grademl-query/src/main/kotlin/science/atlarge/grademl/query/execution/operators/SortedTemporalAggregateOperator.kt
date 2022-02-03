package science.atlarge.grademl.query.execution.operators

import it.unimi.dsi.fastutil.ints.IntHeapPriorityQueue
import it.unimi.dsi.fastutil.ints.IntPriorityQueue
import science.atlarge.grademl.query.execution.*
import science.atlarge.grademl.query.execution.IntTypes.toInt
import science.atlarge.grademl.query.language.FunctionDefinition
import science.atlarge.grademl.query.language.Type
import science.atlarge.grademl.query.model.*

class SortedTemporalAggregateOperator(
    private val input: QueryOperator,
    override val schema: TableSchema,
    private val groupByColumns: List<Int>,
    aggregateFunctions: List<FunctionDefinition>,
    private val aggregateFunctionTypes: List<Type>,
    aggregateFunctionArguments: List<List<PhysicalExpression>>,
    aggregateFunctionArgumentTypes: List<List<Type>>,
    private val aggregateColumns: List<Column>,
    private val projections: List<PhysicalExpression>
) : AccountingQueryOperator() {

    private val aggregatorFactories = aggregateFunctions.mapIndexed { i, functionDefinition ->
        val implementation = BuiltinFunctionImplementations.from(functionDefinition)
        implementation as? AggregatingFunctionImplementation ?: throw IllegalStateException()
        return@mapIndexed {
            implementation.newAggregator(aggregateFunctionArguments[i], aggregateFunctionArgumentTypes[i])
        }
    }

    init {
        require(input.schema.indexOfStartTimeColumn() == Columns.INDEX_START_TIME) {
            "Input to SortedTemporalAggregateOperator must have _start_time column"
        }
        require(input.schema.indexOfEndTimeColumn() == Columns.INDEX_END_TIME) {
            "Input to SortedTemporalAggregateOperator must have _end_time column"
        }
    }

    override fun createTimeSeriesIterator(): AccountingTimeSeriesIterator<*> =
        SortedTemporalAggregateTimeSeriesIterator(
            input = input.execute(),
            schema = schema,
            groupByColumns = groupByColumns.toIntArray(),
            aggregatorFactories = aggregatorFactories,
            aggregatorTypes = aggregateFunctionTypes.map { it.toInt() }.toIntArray(),
            aggregateColumns = aggregateColumns,
            projections = projections.toTypedArray()
        )

}

private class SortedTemporalAggregateTimeSeriesIterator(
    private val input: TimeSeriesIterator,
    schema: TableSchema,
    private val groupByColumns: IntArray,
    private val aggregatorFactories: List<() -> Aggregator>,
    private val aggregatorTypes: IntArray,
    private val aggregateColumns: List<Column>,
    private val projections: Array<PhysicalExpression>
) : AccountingTimeSeriesIterator<SortedTemporalAggregateRowIterator>(schema) {

    // Cache all time series in a group to allow concurrent iteration over multiple time series
    private val inputCache = TimeSeriesCache(input.schema)
    private val firstRowWrapper = inputCache.createRowWrapper()

    // Input types
    private val inputColumnTypes = input.schema.columns.map { it.type.toInt() }.toIntArray()

    // Cast projections to specific types
    private val booleanProjections = Array(projections.size) { projections[it] as? BooleanPhysicalExpression }
    private val numericProjections = Array(projections.size) { projections[it] as? NumericPhysicalExpression }
    private val stringProjections = Array(projections.size) { projections[it] as? StringPhysicalExpression }

    override fun getBoolean(columnIndex: Int) = booleanProjections[columnIndex]!!.evaluateAsBoolean(firstRowWrapper)
    override fun getNumeric(columnIndex: Int) = numericProjections[columnIndex]!!.evaluateAsNumeric(firstRowWrapper)
    override fun getString(columnIndex: Int) = stringProjections[columnIndex]!!.evaluateAsString(firstRowWrapper)

    override fun createRowIterator() = SortedTemporalAggregateRowIterator(
        schema = schema,
        inputSchema = input.schema,
        inputCache = inputCache,
        inputColumnTypes = inputColumnTypes,
        aggregators = aggregatorFactories.map { it() }.toTypedArray(),
        aggregatorTypes = aggregatorTypes,
        aggregateColumns = aggregateColumns,
        projections = projections
    )

    override fun resetRowIteratorWithCurrentTimeSeries(rowIterator: SortedTemporalAggregateRowIterator) {
        rowIterator.reset()
    }

    override fun internalLoadNext(): Boolean {
        // Add the next group of time series to the input cache
        inputCache.clear()
        TimeSeriesCacheUtil.addTimeSeriesGroupToCache(input, inputCache) { left, right ->
            groupByColumns.all { g ->
                when (inputColumnTypes[g]) {
                    IntTypes.TYPE_BOOLEAN -> left.getBoolean(g) == right.getBoolean(g)
                    IntTypes.TYPE_NUMERIC -> left.getNumeric(g) == right.getNumeric(g)
                    IntTypes.TYPE_STRING -> left.getString(g) == right.getString(g)
                    else -> throw IllegalArgumentException()
                }
            }
        }
        firstRowWrapper.rowId = 0
        return !inputCache.isEmpty
    }

}

private class TemporalGroupingComputation(
    timeSeriesCount: Int,
    private val rowIterators: List<RowIterator>
) {

    var currentStartTime = Double.NEGATIVE_INFINITY
        private set
    var currentEndTime: Double
        private set

    // Track which time series iterators have a currently matching row
    private val activeTimeSeriesIds = IntArray(timeSeriesCount) { -1 }
    private val inverseActiveTimeSeriesIds = IntArray(timeSeriesCount) { -1 }
    var activeTimeSeriesCount = 0
        private set

    // Use a priority queue to identify when each time series changes (start/end of a row)
    private val nextChangePoint = DoubleArray(timeSeriesCount)
    private val changePointQueue: IntPriorityQueue = IntHeapPriorityQueue(timeSeriesCount) { l, r ->
        nextChangePoint[l].compareTo(nextChangePoint[r])
    }

    init {
        // Fill in the change point array and queue
        for (i in 0 until timeSeriesCount) {
            val iterator = rowIterators[i]
            if (iterator.loadNext()) {
                nextChangePoint[i] = iterator.currentRow.getNumeric(Columns.INDEX_START_TIME)
                changePointQueue.enqueue(i)
                iterator.pushBack()
            } else {
                nextChangePoint[i] = Double.POSITIVE_INFINITY
            }
        }
        currentEndTime = nextChangePointTime()
    }

    fun next(): Boolean {
        // If all time series iterators are depleted, there are no more rows to produce
        if (changePointQueue.isEmpty) return false
        // Process all changes at the end of the last time slice
        val lastEndTime = currentEndTime
        while (!changePointQueue.isEmpty && nextChangePoint[changePointQueue.firstInt()] == lastEndTime) {
            processChangePoint(changePointQueue.dequeueInt())
        }
        // Set the new time range
        currentStartTime = lastEndTime
        currentEndTime = nextChangePointTime()
        return true
    }

    fun nextNonEmpty(): Boolean {
        do {
            if (!next()) return false
        } while (activeTimeSeriesCount == 0)
        return true
    }

    fun activeRow(index: Int): Row = rowIterators[activeTimeSeriesIds[index]].currentRow

    private fun processChangePoint(timeSeriesId: Int) {
        // Update the activity of the time series and add its next change point back into the queue
        val iterator = rowIterators[timeSeriesId]
        if (inverseActiveTimeSeriesIds[timeSeriesId] == -1) {
            // The time series is now active, so add it to the activity list
            activateTimeSeries(timeSeriesId)
            if (!iterator.loadNext()) throw IllegalStateException()
            // Cache the time series' next change point
            nextChangePoint[timeSeriesId] = iterator.currentRow.getNumeric(Columns.INDEX_END_TIME)
            changePointQueue.enqueue(timeSeriesId)
        } else if (!iterator.loadNext()) {
            // If there are no more rows, the time series is now inactive and will not have another change point
            deactivateTimeSeries(timeSeriesId)
        } else if (iterator.currentRow.getNumeric(Columns.INDEX_START_TIME) == currentEndTime) {
            // If the next row starts at the same time as the last row ended, do not deactivate the time series
            // Cache the time series' next change point
            nextChangePoint[timeSeriesId] = iterator.currentRow.getNumeric(Columns.INDEX_END_TIME)
            changePointQueue.enqueue(timeSeriesId)
        } else {
            // Finally, deactivate the time series and cache the start of its next row
            deactivateTimeSeries(timeSeriesId)
            iterator.pushBack()
            // Cache the time series' next change point
            nextChangePoint[timeSeriesId] = iterator.currentRow.getNumeric(Columns.INDEX_START_TIME)
            changePointQueue.enqueue(timeSeriesId)
        }
    }

    private fun activateTimeSeries(timeSeriesId: Int) {
        val newIndex = activeTimeSeriesCount
        activeTimeSeriesIds[newIndex] = timeSeriesId
        inverseActiveTimeSeriesIds[timeSeriesId] = newIndex
        activeTimeSeriesCount++
    }

    private fun deactivateTimeSeries(timeSeriesId: Int) {
        val freedIndex = inverseActiveTimeSeriesIds[timeSeriesId]
        val lastIndex = activeTimeSeriesCount - 1
        inverseActiveTimeSeriesIds[timeSeriesId] = -1
        if (freedIndex != lastIndex) {
            val lastTimeSeries = activeTimeSeriesIds[lastIndex]
            inverseActiveTimeSeriesIds[lastTimeSeries] = freedIndex
            activeTimeSeriesIds[freedIndex] = lastTimeSeries
        }
        activeTimeSeriesCount--
    }

    private fun nextChangePointTime() =
        if (!changePointQueue.isEmpty) nextChangePoint[changePointQueue.firstInt()]
        else Double.POSITIVE_INFINITY

}

private class SortedTemporalAggregateRowIterator(
    schema: TableSchema,
    inputSchema: TableSchema,
    private val inputCache: TimeSeriesCache,
    private val inputColumnTypes: IntArray,
    private val aggregators: Array<Aggregator>,
    private val aggregatorTypes: IntArray,
    aggregateColumns: List<Column>,
    private val projections: Array<PhysicalExpression>
) : AccountingRowIterator(schema) {

    private val rowIterators = mutableListOf<TimeSeriesCache.CachedRowIterator>()
    private lateinit var temporalGroupingComputation: TemporalGroupingComputation

    // Compute and cache column counts
    private val inputColumnCount = inputColumnTypes.size
    private val addedColumnCount = aggregators.size
    private val cacheColumnCount = inputColumnCount + addedColumnCount

    // Cast projections to specific types
    private val booleanProjections = Array(projections.size) { projections[it] as? BooleanPhysicalExpression }
    private val numericProjections = Array(projections.size) { projections[it] as? NumericPhysicalExpression }
    private val stringProjections = Array(projections.size) { projections[it] as? StringPhysicalExpression }

    // Store values of first row in group and of completed aggregations for final projections
    private val rowBooleanValues = BooleanArray(cacheColumnCount)
    private val rowNumericValues = DoubleArray(cacheColumnCount)
    private val rowStringValues = arrayOfNulls<String?>(cacheColumnCount)

    // Wrap input rows to override the start and end time columns
    private val inputRowWrapper = object : Row {
        lateinit var backingRow: Row
        var startTime = 0.0
        var endTime = 0.0

        override val schema: TableSchema
            get() = backingRow.schema

        override fun getBoolean(columnIndex: Int) = backingRow.getBoolean(columnIndex)

        override fun getNumeric(columnIndex: Int) = when (columnIndex) {
            Columns.INDEX_START_TIME -> startTime
            Columns.INDEX_END_TIME -> endTime
            else -> backingRow.getNumeric(columnIndex)
        }

        override fun getString(columnIndex: Int) = backingRow.getString(columnIndex)
    }

    // Wrap the output of the aggregators as input to the final projections
    private val aggregateRow = object : Row {
        override val schema = TableSchema(inputSchema.columns + aggregateColumns)

        override fun getBoolean(columnIndex: Int) = rowBooleanValues[columnIndex]
        override fun getNumeric(columnIndex: Int) = rowNumericValues[columnIndex]
        override fun getString(columnIndex: Int) = rowStringValues[columnIndex]!!
    }

    fun reset() {
        // Obtain an iterator for each time series in the cache
        val timeSeriesCount = inputCache.numCachedTimeSeries
        while (rowIterators.size < timeSeriesCount) rowIterators.add(inputCache.createRowIterator())
        rowIterators.forEachIndexed { index, iterator -> iterator.reset(index) }
        // Initialize the temporal grouping process
        temporalGroupingComputation = TemporalGroupingComputation(timeSeriesCount, rowIterators)
    }

    override fun getBoolean(columnIndex: Int) = booleanProjections[columnIndex]!!.evaluateAsBoolean(aggregateRow)
    override fun getNumeric(columnIndex: Int) = numericProjections[columnIndex]!!.evaluateAsNumeric(aggregateRow)
    override fun getString(columnIndex: Int) = stringProjections[columnIndex]!!.evaluateAsString(aggregateRow)

    override fun internalLoadNext(): Boolean {
        // Find the next group of overlapping rows
        val grouping = temporalGroupingComputation
        if (!grouping.nextNonEmpty()) return false

        // Cache the values of the first matching row
        val firstRow = grouping.activeRow(0)
        for (c in 0 until inputColumnCount) {
            when (inputColumnTypes[c]) {
                IntTypes.TYPE_BOOLEAN -> rowBooleanValues[c] = firstRow.getBoolean(c)
                IntTypes.TYPE_NUMERIC -> rowNumericValues[c] = firstRow.getNumeric(c)
                IntTypes.TYPE_STRING -> rowStringValues[c] = firstRow.getString(c)
            }
        }
        // Set the start and end time columns
        rowNumericValues[Columns.INDEX_START_TIME] = temporalGroupingComputation.currentStartTime
        rowNumericValues[Columns.INDEX_END_TIME] = temporalGroupingComputation.currentEndTime

        // Reset aggregators to prepare for aggregating this group of rows
        aggregators.forEach { it.reset() }
        // Aggregate every row in the temporal grouping
        inputRowWrapper.startTime = temporalGroupingComputation.currentStartTime
        inputRowWrapper.endTime = temporalGroupingComputation.currentEndTime
        for (i in 0 until grouping.activeTimeSeriesCount) {
            inputRowWrapper.backingRow = grouping.activeRow(i)
            // Add the next row to each aggregator
            for (a in aggregators) a.addRow(inputRowWrapper)
        }
        // Finalize each aggregation
        for (aggregatorIndex in aggregators.indices) {
            val aggregator = aggregators[aggregatorIndex]
            val columnIndex = aggregatorIndex + inputColumnCount
            when (aggregatorTypes[aggregatorIndex]) {
                IntTypes.TYPE_BOOLEAN -> rowBooleanValues[columnIndex] = aggregator.getBooleanResult()
                IntTypes.TYPE_NUMERIC -> rowNumericValues[columnIndex] = aggregator.getNumericResult()
                IntTypes.TYPE_STRING -> rowStringValues[columnIndex] = aggregator.getStringResult()
            }
        }

        return true
    }

}