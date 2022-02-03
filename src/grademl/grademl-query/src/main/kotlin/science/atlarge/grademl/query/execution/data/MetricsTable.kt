package science.atlarge.grademl.query.execution.data

import science.atlarge.grademl.core.GradeMLJob
import science.atlarge.grademl.core.models.Metric
import science.atlarge.grademl.query.execution.AbstractRowIterator
import science.atlarge.grademl.query.execution.AbstractTimeSeriesIterator
import science.atlarge.grademl.query.language.Type
import science.atlarge.grademl.query.model.*

class MetricsTable(
    private val gradeMLJob: GradeMLJob
) : Table {

    override val schema = TableSchema(COLUMNS)

    override fun timeSeriesIterator(): TimeSeriesIterator {
        val allMetrics = gradeMLJob.unifiedResourceModel.rootResource.metricsInTree.iterator()
        val firstTimestampNs = gradeMLJob.unifiedExecutionModel.rootPhase.startTime
        return object : AbstractTimeSeriesIterator(this@MetricsTable.schema) {
            override val currentTimeSeries: TimeSeries
                get() = metricTimeSeries

            private val metricTimeSeries = object : TimeSeries {
                lateinit var metric: Metric

                override val schema: TableSchema
                    get() = this@MetricsTable.schema

                override fun getBoolean(columnIndex: Int): Boolean {
                    throw IllegalArgumentException(
                        "Column $columnIndex does not exist, is not a key, or is not BOOLEAN"
                    )
                }

                override fun getNumeric(columnIndex: Int): Double {
                    return when (columnIndex) {
                        INDEX_CAPACITY -> metric.data.maxValue
                        else -> throw IllegalArgumentException(
                            "Column $columnIndex does not exist, is not a key, or is not NUMERIC"
                        )
                    }
                }

                override fun getString(columnIndex: Int): String {
                    return when (columnIndex) {
                        INDEX_PATH -> metric.path.toString()
                        INDEX_TYPE -> metric.type.toString()
                        else -> throw IllegalArgumentException(
                            "Column $columnIndex does not exist, is not a key, or is not STRING"
                        )
                    }
                }

                override fun rowIterator(): RowIterator {
                    val usageIterator = metric.data.iterator()
                    return object : AbstractRowIterator(this@MetricsTable.schema) {
                        override val currentRow = object : Row {
                            override val schema: TableSchema
                                get() = this@MetricsTable.schema

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
                                    INDEX_UTILIZATION -> usageIterator.currentValue / metric.data.maxValue
                                    INDEX_USAGE -> usageIterator.currentValue
                                    INDEX_CAPACITY -> metric.data.maxValue
                                    else -> throw IllegalArgumentException(
                                        "Column $columnIndex does not exist, or is not NUMERIC"
                                    )
                                }
                            }

                            override fun getString(columnIndex: Int): String {
                                return when (columnIndex) {
                                    INDEX_PATH -> metric.path.toString()
                                    INDEX_TYPE -> metric.type.toString()
                                    else -> throw IllegalArgumentException(
                                        "Column $columnIndex does not exist, or is not STRING"
                                    )
                                }
                            }
                        }

                        override fun internalLoadNext(): Boolean {
                            if (!usageIterator.hasNext) return false
                            usageIterator.next()
                            return true
                        }
                    }
                }
            }

            override fun internalLoadNext(): Boolean {
                while (allMetrics.hasNext()) {
                    metricTimeSeries.metric = allMetrics.next()
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
        const val INDEX_PATH = Columns.INDEX_NOT_RESERVED + 3
        const val INDEX_TYPE = Columns.INDEX_NOT_RESERVED + 4

        val COLUMNS = listOf(
            Columns.START_TIME,
            Columns.END_TIME,
            Columns.DURATION,
            Column("utilization", Type.NUMERIC, false),
            Column("usage", Type.NUMERIC, false),
            Column("capacity", Type.NUMERIC, true),
            Column("path", Type.STRING, true),
            Column("type", Type.STRING, true)
        )
    }

}