package science.atlarge.grademl.query.execution.operators

import science.atlarge.grademl.query.execution.AccountingRowIterator
import science.atlarge.grademl.query.execution.AccountingTimeSeriesIterator
import science.atlarge.grademl.query.execution.BooleanPhysicalExpression
import science.atlarge.grademl.query.model.RowIterator
import science.atlarge.grademl.query.model.TableSchema
import science.atlarge.grademl.query.model.TimeSeriesIterator

class FilterOperator(
    private val input: QueryOperator,
    private val timeSeriesCondition: BooleanPhysicalExpression,
    private val rowCondition: BooleanPhysicalExpression
) : AccountingQueryOperator() {

    override val schema: TableSchema
        get() = input.schema

    override fun createTimeSeriesIterator(): AccountingTimeSeriesIterator<*> =
        FilterTimeSeriesIterator(input.execute(), timeSeriesCondition, rowCondition)

}

private class FilterTimeSeriesIterator(
    private val input: TimeSeriesIterator,
    private val timeSeriesCondition: BooleanPhysicalExpression,
    private val rowCondition: BooleanPhysicalExpression
) : AccountingTimeSeriesIterator<FilterRowIterator>(input.schema) {

    private var peekedInputRowIterator: RowIterator? = null

    override fun getBoolean(columnIndex: Int) = input.currentTimeSeries.getBoolean(columnIndex)
    override fun getNumeric(columnIndex: Int) = input.currentTimeSeries.getNumeric(columnIndex)
    override fun getString(columnIndex: Int) = input.currentTimeSeries.getString(columnIndex)

    override fun createRowIterator() = FilterRowIterator(schema, rowCondition)

    override fun resetRowIteratorWithCurrentTimeSeries(rowIterator: FilterRowIterator) {
        rowIterator.input = peekedInputRowIterator ?: input.currentTimeSeries.rowIterator()
        peekedInputRowIterator = null
    }

    override fun internalLoadNext(): Boolean {
        // Find a time series matching the filter condition
        while (input.loadNext()) {
            if (timeSeriesCondition.evaluateAsBoolean(input.currentTimeSeries)) {
                // Check if the time series has any matching rows
                val rowIterator = input.currentTimeSeries.rowIterator()
                while (rowIterator.loadNext()) {
                    // If any row matches the filter condition, return this time series
                    if (rowCondition.evaluateAsBoolean(rowIterator.currentRow)) {
                        // Push back the first matching row
                        rowIterator.pushBack()
                        peekedInputRowIterator = rowIterator
                        return true
                    }
                }
            }
        }
        return false
    }

}

private class FilterRowIterator(
    schema: TableSchema,
    private val rowCondition: BooleanPhysicalExpression
) : AccountingRowIterator(schema) {

    lateinit var input: RowIterator

    override fun getBoolean(columnIndex: Int) = input.currentRow.getBoolean(columnIndex)
    override fun getNumeric(columnIndex: Int) = input.currentRow.getNumeric(columnIndex)
    override fun getString(columnIndex: Int) = input.currentRow.getString(columnIndex)

    override fun internalLoadNext(): Boolean {
        // Find a row matching the filter condition
        while (input.loadNext()) {
            if (rowCondition.evaluateAsBoolean(input.currentRow)) return true
        }
        return false
    }

}