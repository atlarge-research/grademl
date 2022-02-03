package science.atlarge.grademl.query.model

interface Table {

    val schema: TableSchema

    fun timeSeriesIterator(): TimeSeriesIterator

}