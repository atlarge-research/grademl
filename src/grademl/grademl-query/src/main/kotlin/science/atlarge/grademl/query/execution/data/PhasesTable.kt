package science.atlarge.grademl.query.execution.data

import science.atlarge.grademl.core.GradeMLJob
import science.atlarge.grademl.core.models.ExecutionPhase
import science.atlarge.grademl.query.execution.AbstractRowIterator
import science.atlarge.grademl.query.execution.AbstractTimeSeriesIterator
import science.atlarge.grademl.query.language.Type
import science.atlarge.grademl.query.model.*

class PhasesTable(
    private val gradeMLJob: GradeMLJob
) : Table {

    override val schema = TableSchema(COLUMNS)

    override fun timeSeriesIterator(): TimeSeriesIterator {
        val allPhases = gradeMLJob.unifiedExecutionModel.phases.iterator()
        val firstTimestampNs = gradeMLJob.unifiedExecutionModel.rootPhase.startTime
        return object : AbstractTimeSeriesIterator(this@PhasesTable.schema) {
            override val currentTimeSeries: TimeSeries
                get() = phaseTimeSeries

            private val phaseTimeSeries = object : TimeSeries {
                lateinit var phase: ExecutionPhase

                override val schema: TableSchema
                    get() = this@PhasesTable.schema

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
                        INDEX_PATH -> phase.path.toString()
                        INDEX_TYPE -> phase.type.toString()
                        else -> throw IllegalArgumentException(
                            "Column $columnIndex does not exist, is not a key, or is not STRING"
                        )
                    }
                }

                override fun rowIterator(): RowIterator {
                    return object : AbstractRowIterator(this@PhasesTable.schema) {
                        override val currentRow = object : Row {
                            override val schema: TableSchema
                                get() = this@PhasesTable.schema

                            override fun getBoolean(columnIndex: Int): Boolean {
                                throw IllegalArgumentException(
                                    "Column $columnIndex does not exist, or is not BOOLEAN"
                                )
                            }

                            override fun getNumeric(columnIndex: Int): Double {
                                return when (columnIndex) {
                                    INDEX_START_TIME -> (phase.startTime - firstTimestampNs) / 1e9
                                    INDEX_END_TIME -> (phase.endTime - firstTimestampNs) / 1e9
                                    INDEX_DURATION -> phase.duration / 1e9
                                    else -> throw IllegalArgumentException(
                                        "Column $columnIndex does not exist, or is not NUMERIC"
                                    )
                                }
                            }

                            override fun getString(columnIndex: Int): String {
                                return when (columnIndex) {
                                    INDEX_PATH -> phase.path.toString()
                                    INDEX_TYPE -> phase.type.toString()
                                    else -> throw IllegalArgumentException(
                                        "Column $columnIndex does not exist, or is not STRING"
                                    )
                                }
                            }
                        }

                        private var hasStarted = false
                        override fun internalLoadNext(): Boolean {
                            if (hasStarted) return false
                            hasStarted = true
                            return true
                        }
                    }
                }
            }

            override fun internalLoadNext(): Boolean {
                while (allPhases.hasNext()) {
                    phaseTimeSeries.phase = allPhases.next()
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
        const val INDEX_PATH = Columns.INDEX_NOT_RESERVED
        const val INDEX_TYPE = Columns.INDEX_NOT_RESERVED + 1

        val COLUMNS = listOf(
            Columns.START_TIME,
            Columns.END_TIME,
            Columns.DURATION,
            Column("path", Type.STRING, true),
            Column("type", Type.STRING, true)
        )
    }

}