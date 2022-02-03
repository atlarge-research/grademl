package science.atlarge.grademl.query.execution.util

import science.atlarge.grademl.query.execution.AbstractRowIterator
import science.atlarge.grademl.query.execution.AbstractTimeSeriesIterator
import science.atlarge.grademl.query.language.Type
import science.atlarge.grademl.query.model.*

class ConcreteRow(private val values: List<Any>) {
    fun value(columnIndex: Int) = values[columnIndex]
    fun boolean(columnIndex: Int) = values[columnIndex] as Boolean
    fun numeric(columnIndex: Int) = values[columnIndex] as Double
    fun string(columnIndex: Int) = values[columnIndex] as String

    override fun equals(other: Any?): Boolean {
        if (other !is ConcreteRow) return false
        return values.indices.all { i -> value(i) == other.value(i) }
    }

    override fun hashCode(): Int {
        return values.hashCode()
    }

    override fun toString(): String {
        return "ConcreteRow(${values.joinToString()})"
    }
}

fun Row.toConcreteRow() = ConcreteRow(
    schema.columns.mapIndexed { index, column ->
        when (column.type) {
            Type.BOOLEAN -> getBoolean(index)
            Type.NUMERIC -> getNumeric(index)
            Type.STRING -> getString(index)
            else -> IllegalArgumentException()
        }
    }
)

fun TimeSeriesIterator.toConcreteRows(): List<List<ConcreteRow>> {
    val result = mutableListOf<List<ConcreteRow>>()
    while (loadNext()) {
        val iter = currentTimeSeries.rowIterator()
        val rows = mutableListOf<ConcreteRow>()
        while (iter.loadNext()) {
            rows.add(iter.currentRow.toConcreteRow())
        }
        result.add(rows)
    }
    return result
}

fun ConcreteRow.toRow(schema: TableSchema) = object : Row {
    override val schema = schema
    override fun getBoolean(columnIndex: Int) = boolean(columnIndex)
    override fun getNumeric(columnIndex: Int) = numeric(columnIndex)
    override fun getString(columnIndex: Int) = string(columnIndex)
}

fun List<List<ConcreteRow>>.toTimeSeriesIterator(tableSchema: TableSchema) =
    object : AbstractTimeSeriesIterator(tableSchema) {
        private val mappedData = map { ts -> ts.map { it.toRow(tableSchema) } }
        private var currentTimeSeriesIndex = -1

        override val currentTimeSeries: TimeSeries = object : TimeSeries {
            override val schema: TableSchema = tableSchema

            override fun getBoolean(columnIndex: Int): Boolean {
                require(tableSchema.columns[columnIndex].isKey)
                return mappedData[currentTimeSeriesIndex][0].getBoolean(columnIndex)
            }

            override fun getNumeric(columnIndex: Int): Double {
                require(tableSchema.columns[columnIndex].isKey)
                return mappedData[currentTimeSeriesIndex][0].getNumeric(columnIndex)
            }

            override fun getString(columnIndex: Int): String {
                require(tableSchema.columns[columnIndex].isKey)
                return mappedData[currentTimeSeriesIndex][0].getString(columnIndex)
            }

            override fun rowIterator(): RowIterator = object : AbstractRowIterator(tableSchema) {
                private val timeSeriesIndex = currentTimeSeriesIndex
                private var currentRowIndex = -1

                override val currentRow: Row
                    get() = mappedData[timeSeriesIndex][currentRowIndex]

                override fun internalLoadNext(): Boolean {
                    if (currentRowIndex + 1 >= mappedData[timeSeriesIndex].size) return false
                    currentRowIndex++
                    return true
                }
            }
        }

        override fun internalLoadNext(): Boolean {
            if (currentTimeSeriesIndex + 1 >= mappedData.size) return false
            currentTimeSeriesIndex++
            return true
        }
    }