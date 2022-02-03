package science.atlarge.grademl.query.execution

import science.atlarge.grademl.query.language.Type
import science.atlarge.grademl.query.model.Column
import science.atlarge.grademl.query.model.Columns
import science.atlarge.grademl.query.model.Row
import science.atlarge.grademl.query.model.TableSchema
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

object DataGenerator {

    val schema = TableSchema(
        Columns.RESERVED_COLUMNS +
                listOf(
                    Column("k1", Type.NUMERIC, true),
                    Column("k2", Type.STRING, true),
                    Column("v1", Type.NUMERIC, false),
                    Column("v2", Type.STRING, false),
                    Column("v3", Type.BOOLEAN, false),
                    Column("v4", Type.NUMERIC, false),
                    Column("originalTimeSeriesId", Type.NUMERIC, true)
                )
    )

    fun generate(
        seed: Double,
        timeSeriesRowCounts: List<Int>,
        durationGenerator: Pair<Double, Double> = 0.7 to 2.0,
        k1Generator: Pair<Double, Double> = 1.0 to 3.0,
        k2Generator: Pair<Double, Double> = 2.0 to 5.0,
        v1Generator: Pair<Double, Double> = 3.0 to 7.0,
        v2Generator: Pair<Double, Double> = 1.0 to 2.0,
        v3Generator: Pair<Double, Double> = 0.6 to 2.0,
        v4Generator: Pair<Double, Double> = 0.75 to 7.0
    ): List<GeneratedRow> {
        val absSeed = if (!seed.isFinite()) 0.0 else seed.absoluteValue
        val generatedRows = mutableListOf<GeneratedRow>()
        timeSeriesRowCounts.forEachIndexed { timeSeriesId, rowCount ->
            fun Pair<Double, Double>.genKey() = (absSeed + first * timeSeriesId).mod(second)
            var startTime = timeSeriesId.mod(2).toDouble()
            val k1 = k1Generator.genKey()
            val k2 = k2Generator.genKey().toString()

            for (rowId in 0 until rowCount) {
                fun Pair<Double, Double>.genValue() = (absSeed + first * (rowId + timeSeriesId)).mod(second)
                val duration = durationGenerator.genValue() + 1.0
                val endTime = startTime + duration

                generatedRows.add(
                    GeneratedRow(
                        timeSeriesId, startTime, endTime, k1, k2,
                        v1Generator.genValue(), v2Generator.genValue().toString(),
                        v3Generator.genValue() < 1.0, v4Generator.genValue()
                    )
                )

                startTime = endTime
            }
        }
        return generatedRows
    }

    fun generate(seed: Double, numTimeSeries: Int, rowsPerTimeSeries: Int): List<GeneratedRow> {
        val timeSeriesRowCounts = mutableListOf<Int>()
        repeat(numTimeSeries) { timeSeriesRowCounts.add(rowsPerTimeSeries) }
        return generate(seed, timeSeriesRowCounts)
    }

}

data class GeneratedRow(
    val originalTimeSeriesId: Int,
    val _startTime: Double,
    val _endTime: Double,
    val k1: Double,
    val k2: String,
    val v1: Double,
    val v2: String,
    val v3: Boolean,
    val v4: Double
) {

    fun asQueryEngineRow(): Row = object : Row {
        override val schema: TableSchema = DataGenerator.schema

        override fun getBoolean(columnIndex: Int): Boolean {
            return when (columnIndex) {
                7 -> v3
                else -> throw IllegalArgumentException()
            }
        }

        override fun getNumeric(columnIndex: Int): Double {
            return when (columnIndex) {
                0 -> _startTime
                1 -> _endTime
                2 -> _endTime - _startTime
                3 -> k1
                5 -> v1
                8 -> v4
                9 -> originalTimeSeriesId.toDouble()
                else -> throw IllegalArgumentException()
            }
        }

        override fun getString(columnIndex: Int): String {
            return when (columnIndex) {
                4 -> k2
                6 -> v2
                else -> throw IllegalArgumentException()
            }
        }
    }

    companion object {
        fun fromQueryEngineRow(row: Row): GeneratedRow {
            fun col(name: String) = row.schema.columns.indexOfFirst { it.identifier == name }
            return GeneratedRow(
                originalTimeSeriesId = row.getNumeric(col("originalTimeSeriesId")).roundToInt(),
                _startTime = row.getNumeric(col("_start_time")),
                _endTime = row.getNumeric(col("_end_time")),
                k1 = row.getNumeric(col("k1")),
                k2 = row.getString(col("k2")),
                v1 = row.getNumeric(col("v1")),
                v2 = row.getString(col("v2")),
                v3 = row.getBoolean(col("v3")),
                v4 = row.getNumeric(col("v4"))
            )
        }
    }

}