package science.atlarge.grademl.query.model

import science.atlarge.grademl.query.language.Type

interface Row {

    val schema: TableSchema

    fun getBoolean(columnIndex: Int): Boolean
    fun getNumeric(columnIndex: Int): Double
    fun getString(columnIndex: Int): String

    // Cannot override toString in interface
    fun debugPrint(): String {
        val sb = StringBuilder("Row[")
        schema.columns.forEachIndexed { index, column ->
            if (index != 0) sb.append(", ")
            sb.append(column.identifier)
                .append('=')
            when (column.type) {
                Type.BOOLEAN -> sb.append(if (getBoolean(index)) "TRUE" else "FALSE")
                Type.NUMERIC -> sb.append(getNumeric(index))
                Type.STRING -> sb.append('"').append(getString(index)).append('"')
                else -> sb.append("<UNKNOWN>")
            }
        }
        sb.append(']')
        return sb.toString()
    }

}