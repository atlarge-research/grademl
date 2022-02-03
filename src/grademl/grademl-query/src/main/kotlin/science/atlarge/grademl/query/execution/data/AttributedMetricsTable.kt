package science.atlarge.grademl.query.execution.data

import science.atlarge.grademl.core.GradeMLJob
import science.atlarge.grademl.core.attribution.AttributedResourceData
import science.atlarge.grademl.core.models.ExecutionPhase
import science.atlarge.grademl.core.models.Metric
import science.atlarge.grademl.query.analysis.ASTAnalysis
import science.atlarge.grademl.query.analysis.ASTUtils
import science.atlarge.grademl.query.analysis.FilterConditionSeparation
import science.atlarge.grademl.query.execution.*
import science.atlarge.grademl.query.language.Expression
import science.atlarge.grademl.query.language.Type
import science.atlarge.grademl.query.model.*

class AttributedMetricsTable(
    private val gradeMLJob: GradeMLJob,
    private val filterCondition: Expression? = null
) : FilterableTable {

    override val schema = TableSchema(COLUMNS)

    private val metricColumns = listOf(COLUMNS[INDEX_METRIC_PATH], COLUMNS[INDEX_METRIC_TYPE])
    private val phaseColumns = listOf(COLUMNS[INDEX_PHASE_PATH], COLUMNS[INDEX_PHASE_TYPE])

    override val filterableColumns = metricColumns + phaseColumns

    private val metricFilterCondition: BooleanPhysicalExpression?
    private val phaseFilterCondition: BooleanPhysicalExpression?
    private val combinedFilterCondition: BooleanPhysicalExpression?

    init {
        if (filterCondition != null) {
            // Split the filter condition into metric-only, phase-only, and combined filters
            val metricColumnIndices = metricColumns.map { schema.indexOfColumn(it)!! }.toSet()
            val phaseColumnIndices = phaseColumns.map { schema.indexOfColumn(it)!! }.toSet()
            val separatedFilter = FilterConditionSeparation.splitFilterConditionByColumns(
                filterCondition, listOf(metricColumnIndices, phaseColumnIndices)
            )

            metricFilterCondition = separatedFilter.filterExpressionPerSplit[0]?.let {
                ASTAnalysis.analyzeExpression(it, metricColumns).toPhysicalExpression() as BooleanPhysicalExpression
            }
            phaseFilterCondition = separatedFilter.filterExpressionPerSplit[1]?.let {
                ASTAnalysis.analyzeExpression(it, phaseColumns).toPhysicalExpression() as BooleanPhysicalExpression
            }
            combinedFilterCondition = separatedFilter.remainingFilterExpression?.let {
                ASTAnalysis.analyzeExpression(it, filterableColumns).toPhysicalExpression() as BooleanPhysicalExpression
            }
        } else {
            metricFilterCondition = null
            phaseFilterCondition = null
            combinedFilterCondition = null
        }
    }

    override fun filteredBy(condition: Expression): FilterableTable {
        val analyzedCondition = ASTAnalysis.analyzeExpression(condition, schema.columns)
        require(analyzedCondition.type == Type.BOOLEAN) { "Can only filter by a BOOLEAN expression" }

        val filterableColumnNames = filterableColumns.map { it.identifier }.toSet()
        val columnsInFilter = ASTUtils.findColumnLiterals(condition).map { it.columnPath }
        require(columnsInFilter.all { it in filterableColumnNames }) { "Cannot filter by non-key columns" }

        return AttributedMetricsTable(
            gradeMLJob,
            FilterConditionSeparation.mergeExpressions(listOfNotNull(filterCondition, condition))
        )
    }

    private fun findSelectedMetrics(): List<Metric> {
        val allMetrics = gradeMLJob.unifiedResourceModel.rootResource.metricsInTree
        if (metricFilterCondition == null) return allMetrics.toList()

        val metricRow = object : Row {
            lateinit var metric: Metric

            override val schema = TableSchema(listOf(COLUMNS[INDEX_METRIC_PATH], COLUMNS[INDEX_METRIC_TYPE]))

            override fun getBoolean(columnIndex: Int) =
                throw IllegalArgumentException("Column does not exist or is not BOOLEAN")

            override fun getNumeric(columnIndex: Int) =
                throw IllegalArgumentException("Column does not exist or is not NUMERIC")

            override fun getString(columnIndex: Int) = when (columnIndex) {
                0 -> metric.path.toString()
                1 -> metric.type.toString()
                else -> throw IllegalArgumentException("Column does not exist or is not STRING")
            }
        }

        return allMetrics.filter { metric ->
            metricRow.metric = metric
            metricFilterCondition.evaluateAsBoolean(metricRow)
        }
    }

    private fun findSelectedPhases(): List<ExecutionPhase> {
        val allPhases = gradeMLJob.unifiedExecutionModel.phases
        if (phaseFilterCondition == null) return allPhases.toList()

        val phaseRow = object : Row {
            lateinit var phase: ExecutionPhase

            override val schema = TableSchema(listOf(COLUMNS[INDEX_PHASE_PATH], COLUMNS[INDEX_PHASE_TYPE]))

            override fun getBoolean(columnIndex: Int) =
                throw IllegalArgumentException("Column does not exist or is not BOOLEAN")

            override fun getNumeric(columnIndex: Int) =
                throw IllegalArgumentException("Column does not exist or is not NUMERIC")

            override fun getString(columnIndex: Int) = when (columnIndex) {
                0 -> phase.path.toString()
                1 -> phase.type.toString()
                else -> throw IllegalArgumentException("Column does not exist or is not STRING")
            }
        }

        return allPhases.filter { phase ->
            phaseRow.phase = phase
            phaseFilterCondition.evaluateAsBoolean(phaseRow)
        }
    }

    private fun findSelectedMetricPhasePairs(
        selectedMetrics: List<Metric>,
        selectedPhases: List<ExecutionPhase>
    ): List<Pair<Metric, ExecutionPhase>> {
        return if (combinedFilterCondition == null) {
            selectedMetrics.flatMap { metric -> selectedPhases.map { metric to it } }
        } else {
            val metricPhaseRow = object : Row {
                lateinit var metric: Metric
                lateinit var phase: ExecutionPhase

                override val schema = TableSchema(
                    listOf(
                        COLUMNS[INDEX_METRIC_PATH],
                        COLUMNS[INDEX_METRIC_TYPE],
                        COLUMNS[INDEX_PHASE_PATH],
                        COLUMNS[INDEX_PHASE_TYPE]
                    )
                )

                override fun getBoolean(columnIndex: Int) =
                    throw IllegalArgumentException("Column does not exist or is not BOOLEAN")

                override fun getNumeric(columnIndex: Int) =
                    throw IllegalArgumentException("Column does not exist or is not NUMERIC")

                override fun getString(columnIndex: Int) = when (columnIndex) {
                    0 -> metric.path.toString()
                    1 -> metric.type.toString()
                    2 -> phase.path.toString()
                    3 -> phase.type.toString()
                    else -> throw IllegalArgumentException("Column does not exist or is not STRING")
                }
            }

            selectedMetrics.flatMap { metric ->
                selectedPhases.map { metric to it }.filter { (metric, phase) ->
                    metricPhaseRow.metric = metric
                    metricPhaseRow.phase = phase
                    combinedFilterCondition.evaluateAsBoolean(metricPhaseRow)
                }
            }
        }
    }

    override fun timeSeriesIterator(): TimeSeriesIterator {
        val selectedMetricPhasePairs = findSelectedMetricPhasePairs(
            findSelectedMetrics(), findSelectedPhases()
        ).iterator()
        val firstTimestampNs = gradeMLJob.unifiedExecutionModel.rootPhase.startTime
        return object : AbstractTimeSeriesIterator(this@AttributedMetricsTable.schema) {
            override val currentTimeSeries: TimeSeries
                get() = metricPhaseTimeSeries

            private val metricPhaseTimeSeries = object : TimeSeries {
                lateinit var metric: Metric
                lateinit var phase: ExecutionPhase
                lateinit var attributedResourceData: AttributedResourceData

                override val schema: TableSchema
                    get() = this@AttributedMetricsTable.schema

                override fun getBoolean(columnIndex: Int): Boolean {
                    throw IllegalArgumentException(
                        "Column $columnIndex does not exist, is not a key, or is not BOOLEAN"
                    )
                }

                override fun getNumeric(columnIndex: Int): Double {
                    throw IllegalArgumentException(
                        "Column $columnIndex does not exist, is not a key, or is not NUMERIC"
                    )
                }

                override fun getString(columnIndex: Int): String {
                    return when (columnIndex) {
                        INDEX_METRIC_PATH -> metric.path.toString()
                        INDEX_METRIC_TYPE -> metric.type.toString()
                        INDEX_PHASE_PATH -> phase.path.toString()
                        INDEX_PHASE_TYPE -> phase.type.toString()
                        else -> throw IllegalArgumentException(
                            "Column $columnIndex does not exist, is not a key, or is not STRING"
                        )
                    }
                }

                override fun rowIterator(): RowIterator {
                    val usageIterator = attributedResourceData.metricData.iterator()
                    val capacityIterator = attributedResourceData.availableCapacity.iterator()
                    return object : AbstractRowIterator(this@AttributedMetricsTable.schema) {
                        override val currentRow = object : Row {
                            override val schema: TableSchema
                                get() = this@AttributedMetricsTable.schema

                            override fun getBoolean(columnIndex: Int): Boolean {
                                throw IllegalArgumentException(
                                    "Column $columnIndex does not exist, or is not BOOLEAN"
                                )
                            }

                            override fun getNumeric(columnIndex: Int): Double {
                                return when (columnIndex) {
                                    INDEX_START_TIME -> (usageIterator.currentStartTime - firstTimestampNs) / 1e9
                                    INDEX_END_TIME -> (usageIterator.currentEndTime - firstTimestampNs) / 1e9
                                    INDEX_DURATION -> (usageIterator.currentEndTime - usageIterator.currentStartTime) / 1e9
                                    INDEX_UTILIZATION ->
                                        if (capacityIterator.currentValue == 0.0) 0.0
                                        else usageIterator.currentValue / capacityIterator.currentValue
                                    INDEX_USAGE -> usageIterator.currentValue
                                    INDEX_CAPACITY -> capacityIterator.currentValue
                                    else -> throw IllegalArgumentException(
                                        "Column $columnIndex does not exist, or is not NUMERIC"
                                    )
                                }
                            }

                            override fun getString(columnIndex: Int): String {
                                return when (columnIndex) {
                                    INDEX_METRIC_PATH -> metric.path.toString()
                                    INDEX_METRIC_TYPE -> metric.type.toString()
                                    INDEX_PHASE_PATH -> phase.path.toString()
                                    INDEX_PHASE_TYPE -> phase.type.toString()
                                    else -> throw IllegalArgumentException(
                                        "Column $columnIndex does not exist, or is not STRING"
                                    )
                                }
                            }
                        }

                        override fun internalLoadNext(): Boolean {
                            if (!usageIterator.hasNext) return false
                            usageIterator.next()
                            capacityIterator.next()
                            return true
                        }
                    }
                }
            }

            override fun internalLoadNext(): Boolean {
                while (selectedMetricPhasePairs.hasNext()) {
                    val (metric, phase) = selectedMetricPhasePairs.next()
                    // Get resource attribution result for this metric-phase pair
                    val attributionResult = gradeMLJob.resourceAttribution.attributeMetricToPhase(metric, phase)
                    // Skip if the current metric is not attributed to the current phase
                    if (attributionResult !is AttributedResourceData) continue
                    // Return the next time series
                    metricPhaseTimeSeries.metric = metric
                    metricPhaseTimeSeries.phase = phase
                    metricPhaseTimeSeries.attributedResourceData = attributionResult
                    return true
                }
                return false
            }
        }
    }

    companion object {
        const val INDEX_START_TIME = Columns.INDEX_START_TIME
        const val INDEX_END_TIME = Columns.INDEX_END_TIME
        const val INDEX_DURATION = Columns.INDEX_DURATION
        const val INDEX_UTILIZATION = Columns.INDEX_NOT_RESERVED
        const val INDEX_USAGE = Columns.INDEX_NOT_RESERVED + 1
        const val INDEX_CAPACITY = Columns.INDEX_NOT_RESERVED + 2
        const val INDEX_METRIC_PATH = Columns.INDEX_NOT_RESERVED + 3
        const val INDEX_METRIC_TYPE = Columns.INDEX_NOT_RESERVED + 4
        const val INDEX_PHASE_PATH = Columns.INDEX_NOT_RESERVED + 5
        const val INDEX_PHASE_TYPE = Columns.INDEX_NOT_RESERVED + 6

        val COLUMNS = listOf(
            Columns.START_TIME,
            Columns.END_TIME,
            Columns.DURATION,
            Column("utilization", Type.NUMERIC, false),
            Column("usage", Type.NUMERIC, false),
            Column("capacity", Type.NUMERIC, false),
            Column("metric_path", Type.STRING, true),
            Column("metric_type", Type.STRING, true),
            Column("phase_path", Type.STRING, true),
            Column("phase_type", Type.STRING, true)
        )
    }

}