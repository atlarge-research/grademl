package science.atlarge.grademl.query.model

import science.atlarge.grademl.query.language.Type

interface TimeSeries : Row {

    fun rowIterator(): RowIterator

    // Cannot override toString in interface
    override fun debugPrint(): String {
        val sb = StringBuilder("TimeSeries[")
        val keyColumnIndices = schema.columns.withIndex().filter { it.value.isKey }.map { it.index }
        keyColumnIndices.forEachIndexed { i, columnId ->
            if (i != 0) sb.append(", ")
            sb.append(schema.columns[columnId].identifier)
                .append('=')
            when (schema.columns[columnId].type) {
                Type.BOOLEAN -> sb.append(if (getBoolean(columnId)) "TRUE" else "FALSE")
                Type.NUMERIC -> sb.append(getNumeric(columnId))
                Type.STRING -> sb.append('"').append(getString(columnId)).append('"')
                else -> sb.append("<UNKNOWN>")
            }
        }
        return sb.toString()
    }

}