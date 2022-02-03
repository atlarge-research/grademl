package science.atlarge.grademl.query.execution.util

import science.atlarge.grademl.query.language.Type
import science.atlarge.grademl.query.model.Column
import science.atlarge.grademl.query.model.TableSchema

class ConcreteRowBuilder private constructor() {

    private val columns = mutableListOf<Column>()
    private val timeSeriesIds = mutableListOf<Int>()
    private val booleanValues = mutableListOf<BooleanArray>()
    private val numericValues = mutableListOf<DoubleArray>()
    private val stringValues = mutableListOf<Array<String>>()

    fun timeSeries(vararg timeSeriesIds: Int) {
        this.timeSeriesIds.addAll(timeSeriesIds.toList())
    }

    fun booleanKeyColumn(columnName: String, vararg values: Boolean) = booleanColumn(columnName, true, values)
    fun numericKeyColumn(columnName: String, vararg values: Double) = numericColumn(columnName, true, values)
    fun stringKeyColumn(columnName: String, vararg values: String) =
        stringColumn(columnName, true, values.toList().toTypedArray())

    fun booleanValueColumn(columnName: String, vararg values: Boolean) = booleanColumn(columnName, false, values)
    fun numericValueColumn(columnName: String, vararg values: Double) = numericColumn(columnName, false, values)
    fun stringValueColumn(columnName: String, vararg values: String) =
        stringColumn(columnName, false, values.toList().toTypedArray())

    private fun booleanColumn(columnName: String, isKey: Boolean, values: BooleanArray) {
        columns.add(Column(columnName, Type.BOOLEAN, isKey))
        booleanValues.add(values)
        numericValues.add(doubleArrayOf())
        stringValues.add(emptyArray())
    }

    private fun numericColumn(columnName: String, isKey: Boolean, values: DoubleArray) {
        columns.add(Column(columnName, Type.NUMERIC, isKey))
        booleanValues.add(booleanArrayOf())
        numericValues.add(values)
        stringValues.add(emptyArray())
    }

    private fun stringColumn(columnName: String, isKey: Boolean, values: Array<String>) {
        columns.add(Column(columnName, Type.STRING, isKey))
        booleanValues.add(booleanArrayOf())
        numericValues.add(doubleArrayOf())
        stringValues.add(values)
    }

    private fun build(): Pair<TableSchema, List<List<ConcreteRow>>> {
        val tableSchema = TableSchema(columns)
        val groupedRows = timeSeriesIds.mapIndexed { index, tsId -> index to tsId }
            .groupBy({ it.second }, { it.first })
            .toList()
            .map { it.second }
        val concreteRows = groupedRows.map {
            it.map { row ->
                ConcreteRow(columns.mapIndexed { index, column ->
                    when (column.type) {
                        Type.BOOLEAN -> booleanValues[index][row]
                        Type.NUMERIC -> numericValues[index][row]
                        Type.STRING -> stringValues[index][row]
                        else -> throw IllegalArgumentException()
                    }
                })
            }
        }
        return tableSchema to concreteRows
    }

    companion object {
        fun build(initFn: ConcreteRowBuilder.() -> Unit): Pair<TableSchema, List<List<ConcreteRow>>> {
            val gen = ConcreteRowBuilder()
            gen.initFn()
            return gen.build()
        }
    }

}