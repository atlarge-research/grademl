package science.atlarge.grademl.query.execution.operators

import science.atlarge.grademl.query.model.TableSchema
import science.atlarge.grademl.query.model.TimeSeriesIterator

interface QueryOperator {

    val schema: TableSchema

    fun execute(): TimeSeriesIterator

}