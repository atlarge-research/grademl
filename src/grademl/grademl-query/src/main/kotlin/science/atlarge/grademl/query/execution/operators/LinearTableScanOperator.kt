package science.atlarge.grademl.query.execution.operators

import science.atlarge.grademl.query.execution.AccountingRowIterator
import science.atlarge.grademl.query.execution.AccountingTimeSeriesIterator
import science.atlarge.grademl.query.model.RowIterator
import science.atlarge.grademl.query.model.Table
import science.atlarge.grademl.query.model.TableSchema
import science.atlarge.grademl.query.model.TimeSeriesIterator

class LinearTableScanOperator(
    private val table: Table
) : AccountingQueryOperator() {

    override val schema: TableSchema
        get() = table.schema

    override fun createTimeSeriesIterator(): AccountingTimeSeriesIterator<*> =
        LinearTableScanTimeSeriesIterator(table.timeSeriesIterator())

}

private class LinearTableScanTimeSeriesIterator(
    private val input: TimeSeriesIterator
) : AccountingTimeSeriesIterator<LinearTableScanRowIterator>(input.schema) {

    override fun getBoolean(columnIndex: Int) = input.currentTimeSeries.getBoolean(columnIndex)
    override fun getNumeric(columnIndex: Int) = input.currentTimeSeries.getNumeric(columnIndex)
    override fun getString(columnIndex: Int) = input.currentTimeSeries.getString(columnIndex)

    override fun createRowIterator() = LinearTableScanRowIterator(schema)

    override fun resetRowIteratorWithCurrentTimeSeries(rowIterator: LinearTableScanRowIterator) {
        rowIterator.reset(input.currentTimeSeries.rowIterator())
    }

    override fun internalLoadNext() = input.loadNext()

}

private class LinearTableScanRowIterator(schema: TableSchema) : AccountingRowIterator(schema) {

    private lateinit var input: RowIterator

    fun reset(input: RowIterator) {
        this.input = input
    }

    override fun getBoolean(columnIndex: Int) = input.currentRow.getBoolean(columnIndex)
    override fun getNumeric(columnIndex: Int) = input.currentRow.getNumeric(columnIndex)
    override fun getString(columnIndex: Int) = input.currentRow.getString(columnIndex)

    override fun internalLoadNext() = input.loadNext()

}